package com.troy.orcaplayer.flavor

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import com.troy.orcaplayer.BuildConfig
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.ExtensionBridge
import com.troy.orcaplayer.data.InstalledAddon
import com.troy.orcaplayer.sources.cloudstream.CloudStreamClient
import com.troy.orcaplayer.sources.cloudstream.ExtensionManager
import com.troy.orcaplayer.sources.cloudstream.ProviderInfo
import com.troy.orcaplayer.sources.cloudstream.ProviderManifest
import com.troy.orcaplayer.sources.stremio.AddonClient
import com.troy.orcaplayer.sources.stremio.RequestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The extended build (GPL-3.0, sideload only): CloudStream extensions (19). */
object Flavor {
    const val EXTENSIONS = true

    lateinit var manager: ExtensionManager
        private set
    lateinit var addons: AddonRepository
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var app: Application

    /**
     * Torrent streaming (extended build): torrent streams play from a local address. Created on
     * first use, so nothing torrent-related (the native library included) runs at start-up.
     */
    val torrents: TorrentStreamer by lazy { TorrentStreamer(app, torrentProgress) }

    private val torrentProgress = kotlinx.coroutines.flow.MutableStateFlow<com.troy.orcaplayer.data.TorrentProgress?>(null)

    /** Hands calls to the streamer, creating it only when a torrent is really played. */
    private val lazyResolver = object : com.troy.orcaplayer.data.TorrentResolver {
        override val progress: kotlinx.coroutines.flow.StateFlow<com.troy.orcaplayer.data.TorrentProgress?> = torrentProgress
        @Volatile private var started = false
        override suspend fun resolve(option: com.troy.orcaplayer.core.model.StreamOption): String {
            started = true
            return torrents.resolve(option)
        }
        override fun stop() {
            if (started) runCatching { torrents.stop() }
        }
        override fun needsNotice(): Boolean = !TorrentSettings(app).noticeAccepted
        override fun acceptNotice() {
            TorrentSettings(app).noticeAccepted = true
        }
    }

    fun init(app: Application, addons: AddonRepository) {
        this.addons = addons
        this.app = app
        com.troy.orcaplayer.data.TorrentBridge.resolver = lazyResolver
        // Torrent files of an earlier run are not kept (plain file work, off the main thread).
        scope.launch { runCatching { java.io.File(app.cacheDir, "torrents").deleteRecursively() } }
        manager = ExtensionManager(app, "ORCA Player/${BuildConfig.VERSION_NAME}")
        addons.extensionBridge = object : ExtensionBridge {
            override fun client(a: InstalledAddon, timeoutMs: Int, onRequest: (RequestResult) -> Unit): AddonClient =
                CloudStreamClient(manager.host, a.manifestUrl.removePrefix(ExtensionBridge.SCHEME), a.manifest, timeoutMs.toLong(), onRequest)
        }
        // Every provider an extension registers becomes an entry in the Addon Manager (15.6).
        scope.launch {
            combine(manager.host.providers, manager.state) { p, s -> p to s }.collect { (providers, state) -> sync(providers, state.installed.associateBy { it.internalName }) }
        }
        scope.launch {
            val s = manager.state.value
            if (s.installed.none { it.enabled }) return@launch
            runCatching { manager.host.ensureLoaded() }
            if (s.autoUpdate && unmetered(app)) {
                runCatching { manager.refresh() }
                runCatching { manager.updateAll() }
            }
        }
    }

    private fun unmetered(app: Application): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun sync(providers: List<ProviderInfo>, installed: Map<String, com.troy.orcaplayer.sources.cloudstream.InstalledExtension>) {
        val repoNames = manager.state.value.repos.associate { it.url to it.name }
        providers.forEach { p ->
            val ext = installed[p.internalName]
            val m = ProviderManifest.build(p, ext?.entry, ext?.repoUrl?.let { repoNames[it] })
            val url = ProviderManifest.manifestUrl(p)
            val existing = addons.list.value.addons.firstOrNull { it.manifestUrl == url }
            if (existing == null || existing.manifest != m) addons.install(url, m)
        }
        addons.resetClients()
        // Entries of uninstalled extensions go.
        val entries = addons.list.value.addons.filter { ExtensionBridge.handles(it.manifestUrl) }
        val gone = entries.filter { e -> installed.keys.none { e.manifestUrl.removePrefix(ExtensionBridge.SCHEME).startsWith("$it.") } }
        if (gone.isNotEmpty()) addons.remove(gone.map { it.id }.toSet())
    }

    /** Addon Manager entries that belong to an extension. */
    fun entriesOf(internalName: String): Set<String> = addons.list.value.addons
        .filter { ExtensionBridge.handles(it.manifestUrl) && it.manifestUrl.removePrefix(ExtensionBridge.SCHEME).startsWith("$internalName.") }
        .map { it.id }.toSet()

    @Composable
    fun ExtensionsScreen(onBack: () -> Unit) = com.troy.orcaplayer.flavor.ExtensionsManagerScreen(onBack)
}
