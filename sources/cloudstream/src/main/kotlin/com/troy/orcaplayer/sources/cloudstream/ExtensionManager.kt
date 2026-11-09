package com.troy.orcaplayer.sources.cloudstream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class RepoState(
    val url: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val addedAt: Long = 0,
    val lastChecked: Long = 0,
    val lastError: String? = null,
    val plugins: List<PluginEntry> = emptyList(),
)

@Serializable
data class InstalledExtension(
    val internalName: String,
    val repoUrl: String,
    val entry: PluginEntry,
    val file: String,
    val enabled: Boolean = true,
    val installedAt: Long = 0,
)

@Serializable
data class ExtensionState(
    val repos: List<RepoState> = emptyList(),
    val installed: List<InstalledExtension> = emptyList(),
    /** Update installed extensions when their repository has a newer version (Wi-Fi only). */
    val autoUpdate: Boolean = false,
)

/**
 * Repositories and installed extensions (19.2). Nothing is built in: the user adds every
 * repository, after the trust screen. Files live in app-private storage and are made read-only.
 */
class ExtensionManager(context: Context, private val userAgent: String) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "extensions").apply { mkdirs() }
    private val filesDir = File(dir, "files").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ExtensionState> = _state.asStateFlow()

    /** The extension process, loading the enabled extensions. */
    val host = ExtensionHost(app) {
        _state.value.installed.filter { it.enabled }.map { ExtensionFile(it.internalName, File(filesDir, it.file).absolutePath) }
    }

    private fun read(): ExtensionState = runCatching {
        RepoFormat.json.decodeFromString(ExtensionState.serializer(), stateFile.readText())
    }.getOrDefault(ExtensionState())

    @Synchronized
    private fun update(change: (ExtensionState) -> ExtensionState) {
        _state.update(change)
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(RepoFormat.json.encodeToString(ExtensionState.serializer(), _state.value))
        tmp.renameTo(stateFile)
    }

    private fun get(url: String, maxBytes: Long = 4L * 1024 * 1024): ByteArray {
        // Redirects are followed by hand, because HttpURLConnection won't switch between http and
        // https (GitHub release and CDN links do), and the body is asked for uncompressed.
        var current = url
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            if (code in 300..399 && hops < 8) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) error("HTTP $code")
                current = URL(URL(current), loc).toString()
                hops++
                continue
            }
            break
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val length = c.contentLengthLong
            if (length > maxBytes) error("Too large")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) error("Too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    /** Fetches a repository and its plugin lists, without adding it. */
    suspend fun fetchRepo(input: String): RepoState = withContext(Dispatchers.IO) {
        val url = RepoFormat.normalizeRepoUrl(input) ?: error("Not a repository link")
        val repo = RepoFormat.parseRepo(String(get(url)))
        val plugins = coroutineScope {
            repo.pluginLists.map { ref ->
                async { runCatching { RepoFormat.parsePlugins(String(get(RepoFormat.resolve(url, ref)))) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }.map { it.copy(url = RepoFormat.resolve(url, it.url)) }.distinctBy { it.internalName }
        RepoState(url, repo.name.ifBlank { url }, repo.description, repo.iconUrl, System.currentTimeMillis(), System.currentTimeMillis(), null, plugins)
    }

    fun addRepo(repo: RepoState) = update { s -> s.copy(repos = s.repos.filterNot { it.url == repo.url } + repo) }

    suspend fun removeRepo(url: String) {
        val names = _state.value.installed.filter { it.repoUrl == url }.map { it.internalName }.toSet()
        uninstall(names)
        update { s -> s.copy(repos = s.repos.filterNot { it.url == url }) }
    }

    /** Re-reads every repository. Returns how many have updates. */
    suspend fun refresh(): Int {
        for (r in _state.value.repos) {
            val fresh = runCatching { fetchRepo(r.url) }
            update { s ->
                s.copy(repos = s.repos.map { old ->
                    if (old.url != r.url) old else fresh.fold(
                        { it.copy(addedAt = old.addedAt) },
                        { e -> old.copy(lastChecked = System.currentTimeMillis(), lastError = e.message ?: e.javaClass.simpleName) },
                    )
                })
            }
        }
        return updates().size
    }

    /** Installed extensions whose repository lists a newer, compatible version. */
    fun updates(): List<PluginEntry> {
        val s = _state.value
        return s.installed.mapNotNull { i ->
            s.repos.firstOrNull { it.url == i.repoUrl }?.plugins?.firstOrNull { it.internalName == i.internalName }
                ?.takeIf { it.version > i.entry.version && it.compatible }
        }
    }

    /** Downloads, checks and installs (or updates) [entry] from [repoUrl]. */
    suspend fun install(repoUrl: String, entry: PluginEntry) {
        require(entry.compatible) { "Needs a newer version of the app" }
        withContext(Dispatchers.IO) {
            val bytes = get(entry.url, RepoFormat.MAX_FILE_BYTES)
            // plugins.json "fileSize" is often out of date (repositories rebuild files without
            // updating it) and CloudStream itself ignores it, so it is not checked. A SHA-256 is
            // checked when the repository gives a well-formed one.
            RepoFormat.expectedSha256(entry.fileHash)?.let { h -> require(RepoFormat.sha256(bytes).equals(h, true)) { "The file hash doesn't match the repository" } }
            require(bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) { "Not an extension file" }
            val name = RepoFormat.fileName(entry.internalName)
            val target = File(filesDir, name)
            val tmp = File(filesDir, "$name.part")
            tmp.writeBytes(bytes)
            if (target.exists()) {
                target.setWritable(true)
                target.delete()
            }
            check(tmp.renameTo(target)) { "Couldn't save the extension" }
            target.setReadOnly()
            update { s ->
                val old = s.installed.firstOrNull { it.internalName == entry.internalName }
                val item = InstalledExtension(entry.internalName, repoUrl, entry, name, old?.enabled ?: true, old?.installedAt ?: System.currentTimeMillis())
                s.copy(installed = s.installed.filterNot { it.internalName == entry.internalName } + item)
            }
        }
        host.restart()
    }

    suspend fun updateAll(): Int {
        val list = updates()
        var n = 0
        for (e in list) {
            val repo = _state.value.installed.firstOrNull { it.internalName == e.internalName }?.repoUrl ?: continue
            if (runCatching { install(repo, e) }.isSuccess) n++
        }
        return n
    }

    suspend fun setEnabled(internalName: String, enabled: Boolean) {
        update { s -> s.copy(installed = s.installed.map { if (it.internalName == internalName) it.copy(enabled = enabled) else it }) }
        host.restart()
    }

    suspend fun uninstall(names: Set<String>) {
        if (names.isEmpty()) return
        val gone = _state.value.installed.filter { it.internalName in names }
        update { s -> s.copy(installed = s.installed.filterNot { it.internalName in names }) }
        withContext(Dispatchers.IO) {
            gone.forEach { File(filesDir, it.file).apply { setWritable(true); delete() } }
        }
        host.restart()
    }

    fun setAutoUpdate(on: Boolean) = update { it.copy(autoUpdate = on) }

    fun entryFor(internalName: String): PluginEntry? = _state.value.installed.firstOrNull { it.internalName == internalName }?.entry
}
