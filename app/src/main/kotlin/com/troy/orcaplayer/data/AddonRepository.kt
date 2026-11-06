package com.troy.orcaplayer.data

import android.content.Context
import com.troy.orcaplayer.BuildConfig
import com.troy.orcaplayer.core.model.Profile
import com.troy.orcaplayer.sources.stremio.AddonClient
import com.troy.orcaplayer.sources.stremio.AddonManifest
import com.troy.orcaplayer.sources.stremio.CatalogDecl
import com.troy.orcaplayer.sources.stremio.HttpClient
import com.troy.orcaplayer.sources.stremio.ManifestChanges
import com.troy.orcaplayer.sources.stremio.RequestResult
import com.troy.orcaplayer.sources.stremio.StremioAddon
import com.troy.orcaplayer.sources.stremio.UrlConnectionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
enum class CatalogPlace { HOME, DISCOVER, HIDDEN }

@Serializable
enum class RowStyle { POSTER, LANDSCAPE, LARGE, TOP10, HERO }

/** The user's choices for one catalog of one addon (15.3). */
@Serializable
data class CatalogPref(
    val key: String,
    val name: String? = null,
    val place: CatalogPlace? = null,
    val order: Int? = null,
    val rowStyle: RowStyle = RowStyle.POSTER,
    val defaultGenre: String? = null,
    /** Position among every add-on's Home rows, set on the Home rows screen (17.1). */
    val homeOrder: Int? = null,
)

@Serializable
data class InstalledAddon(
    val manifestUrl: String,
    val manifest: AddonManifest,
    val enabled: Boolean = true,
    val installedAt: Long = 0,
    val lastChecked: Long = 0,
    /** The last manifest check failed with this; the last good manifest is kept (15.4). */
    val lastError: String? = null,
    /** Set when a re-check found a new version, cleared when the user has seen it. */
    val updatedFrom: String? = null,
    val changes: List<String> = emptyList(),
    /** Profiles this addon is switched off for. */
    val offForProfiles: Set<String> = emptySet(),
    /** Kids profiles this addon is switched on for (off for kids by default). */
    val onForKids: Set<String> = emptySet(),
    val useCatalogs: Boolean = true,
    val useDetails: Boolean = true,
    val useStreams: Boolean = true,
    val useSubtitles: Boolean = true,
    val inSearch: Boolean = true,
    val allowDownloads: Boolean = true,
    val timeoutSec: Int? = null,
    val catalogs: List<CatalogPref> = emptyList(),
) {
    val id: String get() = manifest.id
    val adult: Boolean get() = manifest.behaviorHints.adult

    fun activeFor(profile: Profile?): Boolean {
        if (!enabled) return false
        val p = profile ?: return true
        if (p.isKids) return !adult && p.id in onForKids
        return p.id !in offForProfiles
    }

    fun prefFor(key: String): CatalogPref = catalogs.firstOrNull { it.key == key } ?: CatalogPref(key)

    /** Catalogs in the user's order with their effective place. */
    fun effectiveCatalogs(): List<Pair<CatalogDecl, CatalogPref>> = manifest.catalogs
        .mapIndexed { i, c -> Triple(c, prefFor(c.key), i) }
        .sortedBy { (_, p, i) -> p.order ?: i }
        .map { (c, p, _) -> c to p.copy(place = placeOf(c, p)) }

    companion object {
        /** Catalogs that need an extra never go on Home (14.1); search-only catalogs don't show as rows at all. */
        fun placeOf(c: CatalogDecl, p: CatalogPref): CatalogPlace = when {
            c.searchOnly -> CatalogPlace.HIDDEN
            p.place == CatalogPlace.HOME && c.requiredExtras.any { it.name != "search" } && p.defaultGenre == null -> CatalogPlace.DISCOVER
            p.place != null -> p.place
            c.requiredExtras.any { it.name != "search" } -> CatalogPlace.DISCOVER
            else -> CatalogPlace.HOME
        }
    }
}

@Serializable
data class AddonList(
    val addons: List<InstalledAddon> = emptyList(),
    /** "addonId|catalogKey" of the catalog the user marked as trending, for search suggestions (14.4). */
    val trending: String? = null,
    /** "addonId|catalogKey" of the catalog the featured area takes its titles from (17.2). */
    val hero: String? = null,
    val lastAutoCheck: Long = 0,
)

enum class HealthState { UNKNOWN, OK, SLOW, FAILING }

data class Health(
    val ok: Int = 0,
    val failed: Int = 0,
    val avgMs: Long = 0,
    val lastAt: Long = 0,
    val lastError: String? = null,
    val lastOk: Boolean = true,
) {
    val state: HealthState get() = when {
        ok + failed == 0 -> HealthState.UNKNOWN
        !lastOk && (failed > ok || failed >= 3) -> HealthState.FAILING
        !lastOk -> HealthState.FAILING
        avgMs > 3_000 -> HealthState.SLOW
        else -> HealthState.OK
    }
    val errorRate: Float get() = if (ok + failed == 0) 0f else failed.toFloat() / (ok + failed)
}

/**
 * Installed Stremio addons: order, per-source settings, health and the client objects. Installed
 * sources are shared by every profile; each profile chooses which are active (15.5).
 */
class AddonRepository(context: Context) {
    private val app = context.applicationContext
    private val store = JsonFile(File(app.filesDir, "sources/addons.json"), AddonList.serializer(), AddonList())
    val list: StateFlow<AddonList> = store.state

    val http: HttpClient = UrlConnectionClient("ORCA Player/${BuildConfig.VERSION_NAME}")
    private val cache = DiskCacheStore(File(app.cacheDir, "addon-cache"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _health = MutableStateFlow<Map<String, Health>>(emptyMap())
    val health: StateFlow<Map<String, Health>> = _health.asStateFlow()

    @Volatile var defaultTimeoutSec: Int = 10

    private val clients = HashMap<String, Pair<String, AddonClient>>()

    /** Set by the extended build so CloudStream extensions get their own clients (19). */
    @Volatile var extensionBridge: ExtensionBridge? = null

    /** Drops cached clients, for example after an extension was updated. */
    @Synchronized
    fun resetClients(ids: Set<String>? = null) {
        if (ids == null) clients.clear() else ids.forEach { clients.remove(it) }
    }

    fun addon(id: String): InstalledAddon? = list.value.addons.firstOrNull { it.id == id }

    /** The client for an installed addon, rebuilt when its manifest or timeout changes. */
    @Synchronized
    fun client(a: InstalledAddon): AddonClient {
        val timeout = (a.timeoutSec ?: defaultTimeoutSec) * 1000
        val stamp = "${a.manifestUrl}|${a.manifest.version}|${a.manifest.catalogs.size}|$timeout"
        clients[a.id]?.let { (s, c) -> if (s == stamp) return c }
        val bridge = extensionBridge
        val c = if (ExtensionBridge.handles(a.manifestUrl) && bridge != null) {
            bridge.client(a, timeout) { record(a.id, it) }
        } else {
            StremioAddon(a.manifestUrl, a.manifest, http, cache, timeout, scope, onRequest = { record(a.id, it) })
        }
        clients[a.id] = stamp to c
        return c
    }

    fun active(profile: Profile?): List<InstalledAddon> = list.value.addons.filter { it.activeFor(profile) }

    private fun record(id: String, r: RequestResult) {
        if (r.fromCache) return
        _health.update { m ->
            val h = m[id] ?: Health()
            val n = h.ok + h.failed
            val avg = if (r.ok) ((h.avgMs * minOf(n, 19)) + r.millis) / (minOf(n, 19) + 1) else h.avgMs
            m + (id to Health(
                ok = h.ok + if (r.ok) 1 else 0,
                failed = h.failed + if (r.ok) 0 else 1,
                avgMs = avg,
                lastAt = System.currentTimeMillis(),
                lastError = if (r.ok) h.lastError else r.error,
                lastOk = r.ok,
            ))
        }
    }

    // Installing

    suspend fun preview(input: String): Pair<String, AddonManifest> =
        StremioAddon.fetchManifest(http, input, defaultTimeoutSec * 1000)

    /**
     * Installs, or replaces an install with the same ID in place, keeping its position and
     * settings (15.1). Returns true when it replaced one.
     */
    fun install(url: String, manifest: AddonManifest): Boolean {
        var replaced = false
        store.update { l ->
            val i = l.addons.indexOfFirst { it.id == manifest.id || it.manifestUrl == url }
            if (i >= 0) {
                replaced = true
                val old = l.addons[i]
                val changes = describe(ManifestChanges.between(old.manifest, manifest))
                l.copy(addons = l.addons.toMutableList().also {
                    it[i] = old.copy(
                        manifestUrl = url, manifest = manifest, lastChecked = System.currentTimeMillis(), lastError = null,
                        updatedFrom = if (old.manifest.version != manifest.version) old.manifest.version else old.updatedFrom,
                        changes = if (old.manifest.version != manifest.version) changes else old.changes,
                    )
                })
            } else {
                l.copy(addons = l.addons + InstalledAddon(url, manifest, installedAt = System.currentTimeMillis(), lastChecked = System.currentTimeMillis()))
            }
        }
        return replaced
    }

    data class Removed(val index: Int, val addon: InstalledAddon)

    fun remove(ids: Set<String>): List<Removed> {
        val removed = list.value.addons.withIndex().filter { it.value.id in ids }.map { Removed(it.index, it.value) }
        store.update { l -> l.copy(addons = l.addons.filterNot { it.id in ids }) }
        return removed
    }

    fun restore(removed: List<Removed>) {
        store.update { l ->
            val out = l.addons.toMutableList()
            removed.sortedBy { it.index }.forEach { r ->
                if (out.none { it.id == r.addon.id }) out.add(r.index.coerceIn(0, out.size), r.addon)
            }
            l.copy(addons = out)
        }
    }

    fun setEnabled(ids: Set<String>, enabled: Boolean) = store.update { l ->
        l.copy(addons = l.addons.map { if (it.id in ids) it.copy(enabled = enabled) else it })
    }

    fun move(from: Int, to: Int) = store.update { l ->
        if (from !in l.addons.indices || to !in l.addons.indices) l
        else l.copy(addons = l.addons.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun edit(id: String, change: (InstalledAddon) -> InstalledAddon) = store.update { l ->
        l.copy(addons = l.addons.map { if (it.id == id) change(it) else it })
    }

    fun editCatalog(id: String, key: String, change: (CatalogPref) -> CatalogPref) = edit(id) { a ->
        val pref = change(a.prefFor(key))
        a.copy(catalogs = a.catalogs.filterNot { it.key == key } + pref)
    }

    /** Moves a catalog up or down in this addon's order. */
    fun moveCatalog(id: String, key: String, delta: Int) = edit(id) { a ->
        val order = a.effectiveCatalogs().map { it.first.key }.toMutableList()
        val i = order.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in order.indices) return@edit a
        order.add(j, order.removeAt(i))
        a.copy(catalogs = order.mapIndexed { n, k -> a.prefFor(k).copy(order = n) })
    }

    fun setTrending(addonId: String?, key: String?) = store.update { it.copy(trending = if (addonId != null && key != null) "$addonId|$key" else null) }

    fun setHero(addonId: String?, key: String?) = store.update { it.copy(hero = if (addonId != null && key != null) "$addonId|$key" else null) }

    /** Saves the order of every Home row, and which are hidden, from the Home rows screen. */
    fun setHomeLayout(order: List<String>, hidden: Set<String>) = store.update { list ->
        list.copy(
            addons = list.addons.map { a ->
                val prefs = a.manifest.catalogs.map { decl ->
                    val rowKey = "${a.id}|${decl.key}"
                    val old = a.prefFor(decl.key)
                    val pos = order.indexOf(rowKey).takeIf { it >= 0 }
                    val place = when {
                        rowKey in hidden -> CatalogPlace.DISCOVER
                        pos != null && old.place == CatalogPlace.DISCOVER -> CatalogPlace.HOME
                        else -> old.place
                    }
                    old.copy(homeOrder = pos ?: old.homeOrder, place = place)
                }
                a.copy(catalogs = prefs)
            },
        )
    }

    fun markSeen(id: String) = edit(id) { it.copy(updatedFrom = null, changes = emptyList()) }

    // Updates and health (15.4)

    /** Re-checks every manifest; on start this runs at most once a day, on pull to refresh always. */
    suspend fun refreshManifests(force: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - list.value.lastAutoCheck < 24 * 3600_000L) return@withContext
        store.update { it.copy(lastAutoCheck = now) }
        // Extensions are checked by their own repositories, not by manifest URL.
        list.value.addons.filterNot { ExtensionBridge.handles(it.manifestUrl) }.map { a ->
            async {
                val started = System.currentTimeMillis()
                val result = runCatching { StremioAddon.fetchManifest(http, a.manifestUrl, (a.timeoutSec ?: defaultTimeoutSec) * 1000) }
                record(a.id, RequestResult(a.manifestUrl, result.isSuccess, System.currentTimeMillis() - started, false, result.exceptionOrNull()?.message))
                result.fold(
                    onSuccess = { (_, m) ->
                        edit(a.id) { old ->
                            val changed = old.manifest.version != m.version
                            old.copy(
                                manifest = if (m.id == old.id) m else old.manifest,
                                lastChecked = now, lastError = null,
                                updatedFrom = if (changed) old.manifest.version else old.updatedFrom,
                                changes = if (changed) describe(ManifestChanges.between(old.manifest, m)) else old.changes,
                            )
                        }
                    },
                    onFailure = { e -> edit(a.id) { it.copy(lastChecked = now, lastError = e.message ?: e.javaClass.simpleName) } },
                )
            }
        }.awaitAll()
    }

    private fun describe(c: ManifestChanges): List<String> = buildList {
        c.addedCatalogs.forEach { add("+ $it") }
        c.removedCatalogs.forEach { add("− $it") }
        c.addedResources.forEach { add("+ $it") }
        c.removedResources.forEach { add("− $it") }
    }

    fun clearCache() = cache.clear()

    // Import and export (15.5)

    @Serializable
    private data class ExportFile(val app: String = "ORCA Player", val kind: String = "sources", val version: Int = 1, val addons: List<InstalledAddon>)

    fun export(): String = StoreJson.encodeToString(ExportFile.serializer(), ExportFile(addons = list.value.addons))

    /** Adds the addons from an export (or a plain list of manifest URLs) that aren't installed yet. Returns how many were added. */
    suspend fun import(text: String): Int {
        val fromFile = runCatching { StoreJson.decodeFromString(ExportFile.serializer(), text).addons }.getOrNull()
            ?: runCatching { StoreJson.decodeFromString(ListSerializer(InstalledAddon.serializer()), text) }.getOrNull()
        if (fromFile != null) {
            var added = 0
            store.update { l ->
                val have = l.addons.map { it.id }.toSet()
                val fresh = fromFile.filter { it.id !in have }
                added = fresh.size
                l.copy(addons = l.addons + fresh)
            }
            return added
        }
        // Plain text: one manifest URL per line.
        var added = 0
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            runCatching { preview(line) }.getOrNull()?.let { (url, m) ->
                if (addon(m.id) == null) {
                    install(url, m)
                    added++
                }
            }
        }
        return added
    }

    fun exportText(): String = list.value.addons.joinToString("\n") { it.manifestUrl }
}
