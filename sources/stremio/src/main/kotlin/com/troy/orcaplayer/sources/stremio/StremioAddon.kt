package com.troy.orcaplayer.sources.stremio

import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.StreamOption
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.sources.api.Catalog
import com.troy.orcaplayer.sources.api.CatalogFilter
import com.troy.orcaplayer.sources.api.ContentSource
import com.troy.orcaplayer.sources.api.Page
import com.troy.orcaplayer.sources.api.PageRequest
import com.troy.orcaplayer.sources.api.SourceCapability
import com.troy.orcaplayer.sources.api.SourceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/** One request's outcome, for the health dot and error rate per addon (15.4). */
data class RequestResult(val url: String, val ok: Boolean, val millis: Long, val fromCache: Boolean, val error: String? = null)

/**
 * A Stremio addon as a [ContentSource]. Only asks about types and IDs the manifest declares,
 * caches by the addon's hints, serves stale data when the addon fails, and never throws anything
 * but [SourceException].
 */
class StremioAddon(
    val manifestUrl: String,
    val manifest: AddonManifest,
    private val http: HttpClient,
    private val cache: CacheStore,
    private val timeoutMs: Int = 10_000,
    /** Background refreshes for stale-while-revalidate. Null fetches in line instead. */
    private val refreshScope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRequest: (RequestResult) -> Unit = {},
) : AddonClient {

    val base: String = StremioUrls.baseOf(manifestUrl)

    override val ref = SourceRef(SourceKind.STREMIO_ADDON, manifest.id, manifest.name)

    override val capabilities: Set<SourceCapability> = buildSet {
        if (manifest.catalogs.isNotEmpty()) add(SourceCapability.CATALOGS)
        if (manifest.catalogs.any { it.supportsSearch }) add(SourceCapability.SEARCH)
        if (manifest.hasResource("meta")) add(SourceCapability.DETAILS)
        if (manifest.hasResource("stream")) add(SourceCapability.STREAMS)
        if (manifest.hasResource("subtitles")) add(SourceCapability.SUBTITLES)
    }

    override suspend fun catalogs(): List<Catalog> = manifest.catalogs.map { toCatalog(it) }

    override fun toCatalog(c: CatalogDecl) = Catalog(
        sourceId = manifest.id,
        id = c.key,
        type = StremioUrls.typeOf(c.type),
        name = c.name,
        filters = c.extra.filter { it.name != "search" && it.name != "skip" }.map {
            CatalogFilter(it.name, it.name.replaceFirstChar { ch -> ch.uppercase() }, it.options, it.isRequired)
        },
        supportsSearch = c.supportsSearch,
    )

    private fun declOf(catalog: Catalog): CatalogDecl =
        manifest.catalogs.firstOrNull { it.key == catalog.id } ?: throw SourceException(ref, "Unknown catalog ${catalog.id}")

    override suspend fun catalogPage(catalog: Catalog, filters: Map<String, String>, page: PageRequest): Page<Title> {
        val decl = declOf(catalog)
        val missing = decl.requiredExtras.filter { it.name != "search" && filters[it.name].isNullOrEmpty() }
        if (missing.isNotEmpty()) throw SourceException(ref, "Choose ${missing.joinToString { it.name }} first")
        val extras = LinkedHashMap<String, String>()
        decl.extra.forEach { x -> filters[x.name]?.takeIf { it.isNotEmpty() && x.name != "skip" }?.let { extras[x.name] = it } }
        if (page.skip > 0) {
            if (!decl.supportsSkip) return Page(emptyList(), null)
            extras["skip"] = page.skip.toString()
        }
        val url = StremioUrls.resource(base, "catalog", decl.type, decl.id, extras)
        val body = fetch(url, CacheClass.CATALOG)
        val items = parse { StremioParse.metas(body, ref, StremioUrls.typeOf(decl.type)) }
        val next = if (decl.supportsSkip && items.isNotEmpty()) PageRequest(page.skip + items.size, page.limit) else null
        return Page(items, next)
    }

    override suspend fun search(query: String, type: TitleType?): List<Title> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val targets = manifest.catalogs.filter { it.supportsSearch && (type == null || StremioUrls.typeOf(it.type) == type) }
        val out = LinkedHashMap<String, Title>()
        var firstError: SourceException? = null
        for (c in targets) {
            try {
                val url = StremioUrls.resource(base, "catalog", c.type, c.id, mapOf("search" to q))
                val body = fetch(url, CacheClass.CATALOG)
                parse { StremioParse.metas(body, ref, StremioUrls.typeOf(c.type)) }.forEach { out.putIfAbsent(it.type.name + it.id, it) }
            } catch (e: SourceException) {
                if (firstError == null) firstError = e
            }
        }
        if (out.isEmpty() && firstError != null) throw firstError
        return out.values.toList()
    }

    override suspend fun details(type: TitleType, titleId: String): Title? {
        val t = typeFor(type, "meta", titleId) ?: return null
        val body = fetch(StremioUrls.resource(base, "meta", t, titleId), CacheClass.META)
        return parse { StremioParse.metaDetail(body, ref, type) }
    }

    override suspend fun streams(type: TitleType, videoId: String): List<StreamOption> {
        val t = typeFor(type, "stream", videoId) ?: return emptyList()
        val body = fetch(StremioUrls.resource(base, "stream", t, videoId), CacheClass.STREAM)
        return parse { StremioParse.streams(body, ref) }
    }

    override suspend fun subtitles(type: TitleType, videoId: String): List<SubtitleOption> = subtitlesFor(type, videoId)

    /** Subtitles, sending the file's hash, size and name when known so the addon can match it (9.6). */
    override suspend fun subtitlesFor(type: TitleType, videoId: String, videoHash: String?, videoSize: Long?, filename: String?): List<SubtitleOption> {
        val t = typeFor(type, "subtitles", videoId) ?: return emptyList()
        val extras = buildMap {
            videoHash?.let { put("videoHash", it) }
            videoSize?.let { put("videoSize", it.toString()) }
            filename?.let { put("filename", it) }
        }
        val body = fetch(StremioUrls.resource(base, "subtitles", t, videoId, extras), CacheClass.SUBTITLES)
        return parse { StremioParse.subtitles(body, ref) }
    }

    override fun supports(resource: String, type: TitleType, id: String): Boolean = typeFor(type, resource, id) != null

    /** Lists from a directory addon's addon_catalog resource (15.5). */
    override suspend fun addonCatalog(catalog: CatalogDecl): List<StremioParse.AddonListing> {
        val body = fetch(StremioUrls.resource(base, "addon_catalog", catalog.type, catalog.id), CacheClass.CATALOG)
        return parse { StremioParse.addonCatalog(body) }
    }

    /** The addon's own type name for a resource, or null when it doesn't serve this type or ID. */
    private fun typeFor(type: TitleType, resource: String, id: String): String? {
        val wanted = StremioUrls.typeName(type)
        if (manifest.supports(resource, wanted, id)) return wanted
        // Addons use their own type names ("anime", "events"): match them by what they map to.
        val decl = manifest.resources.firstOrNull { it.name == resource } ?: return null
        val types = decl.types ?: manifest.types
        return types.firstOrNull { StremioUrls.typeOf(it) == type && manifest.supports(resource, it, id) }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: StremioParse.BadResponse) {
        throw SourceException(ref, e.message ?: "Bad response", e)
    }

    private suspend fun fetch(url: String, cls: CacheClass): String {
        val now = clock()
        val cached = cache.read(url)
        if (cached != null && now < cached.freshUntil) {
            onRequest(RequestResult(url, ok = true, millis = 0, fromCache = true))
            return cached.body
        }
        if (cached != null && now < cached.revalidateUntil && refreshScope != null) {
            refreshScope.launch { runCatching { network(url, cls) } }
            onRequest(RequestResult(url, ok = true, millis = 0, fromCache = true))
            return cached.body
        }
        return try {
            network(url, cls)
        } catch (e: SourceException) {
            if (cached != null && now < cached.errorUntil) cached.body else throw e
        }
    }

    private suspend fun network(url: String, cls: CacheClass): String {
        val started = clock()
        val response = try {
            http.get(url, emptyMap(), timeoutMs)
        } catch (e: IOException) {
            val msg = if (e is java.net.SocketTimeoutException) "Timed out" else (e.message ?: e.javaClass.simpleName)
            onRequest(RequestResult(url, ok = false, millis = clock() - started, fromCache = false, error = msg))
            throw SourceException(ref, msg, e)
        }
        val took = clock() - started
        if (response.code !in 200..299) {
            onRequest(RequestResult(url, ok = false, millis = took, fromCache = false, error = "HTTP ${response.code}"))
            throw SourceException(ref, "HTTP ${response.code}")
        }
        if (LenientJson.parse(response.body).obj() == null) {
            onRequest(RequestResult(url, ok = false, millis = took, fromCache = false, error = "Broken JSON"))
            throw SourceException(ref, "The addon sent something that isn't JSON")
        }
        onRequest(RequestResult(url, ok = true, millis = took, fromCache = false))
        cache.write(url, CachePolicy.entry(response.body, clock(), cls, StremioParse.cacheHints(response.body)))
        return response.body
    }

    companion object {
        /** Fetches and reads a manifest for the install preview. Throws [ManifestException] with a reason the user can read. */
        suspend fun fetchManifest(http: HttpClient, input: String, timeoutMs: Int = 10_000): Pair<String, AddonManifest> {
            val url = StremioUrls.normalizeManifestUrl(input) ?: throw ManifestException(ManifestException.Reason.NOT_A_LINK)
            val resp = try {
                http.get(url, emptyMap(), timeoutMs)
            } catch (e: java.net.SocketTimeoutException) {
                throw ManifestException(ManifestException.Reason.TIMEOUT, e)
            } catch (e: IOException) {
                throw ManifestException(ManifestException.Reason.NETWORK, e)
            }
            if (resp.code !in 200..299) throw ManifestException(ManifestException.Reason.HTTP, detail = resp.code.toString())
            val manifest = try {
                AddonManifest.parse(resp.body)
            } catch (e: IllegalArgumentException) {
                throw ManifestException(ManifestException.Reason.NOT_A_MANIFEST, e)
            }
            return url to manifest
        }
    }
}

class ManifestException(val reason: Reason, cause: Throwable? = null, val detail: String? = null) : Exception(reason.name + (detail?.let { " $it" } ?: ""), cause) {
    enum class Reason { NOT_A_LINK, NETWORK, TIMEOUT, HTTP, NOT_A_MANIFEST }
}
