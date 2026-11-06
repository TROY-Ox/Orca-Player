package com.troy.orcaplayer.sources.cloudstream

import com.troy.orcaplayer.core.model.Episode
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.StreamKind
import com.troy.orcaplayer.core.model.StreamOption
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.sources.api.Catalog
import com.troy.orcaplayer.sources.api.Page
import com.troy.orcaplayer.sources.api.PageRequest
import com.troy.orcaplayer.sources.api.SourceCapability
import com.troy.orcaplayer.sources.api.SourceException
import com.troy.orcaplayer.sources.stremio.AddonClient
import com.troy.orcaplayer.sources.stremio.AddonManifest
import com.troy.orcaplayer.sources.stremio.BehaviorHints
import com.troy.orcaplayer.sources.stremio.CatalogDecl
import com.troy.orcaplayer.sources.stremio.ExtraDecl
import com.troy.orcaplayer.sources.stremio.RequestResult
import com.troy.orcaplayer.sources.stremio.ResourceDecl
import com.troy.orcaplayer.sources.stremio.StremioUrls
import java.util.concurrent.ConcurrentHashMap

/** CloudStream content types as the app's title types (19.3). */
object CsTypes {
    fun of(name: String?): TitleType = when (name) {
        "Movie", "AnimeMovie", "Documentary", "Torrent", "Music", "AudioBook", "Audio", "Video", "CustomMedia" -> TitleType.MOVIE
        "TvSeries", "Cartoon", "Anime", "OVA", "AsianDrama", "Podcast" -> TitleType.SERIES
        "Live" -> TitleType.TV
        else -> TitleType.OTHER
    }

    fun isAdult(types: List<String>) = "NSFW" in types
}

/**
 * The Addon Manager entry for one extension provider: a Stremio-style manifest whose catalogs
 * are its main page sections, plus search (15.6).
 */
object ProviderManifest {
    const val SEARCH_ID = "search"

    fun build(p: ProviderInfo, plugin: PluginEntry?): AddonManifest {
        val types = p.types.map { StremioUrls.typeName(CsTypes.of(it)) }.distinct().ifEmpty { listOf("movie") }
        val main = types.first()
        val prefix = ExtensionIds.prefix(p.key)
        val sections = p.sections.mapIndexed { i, s -> CatalogDecl(main, "s$i", s.name.ifBlank { p.name }) }
        val search = CatalogDecl(main, SEARCH_ID, p.name, listOf(ExtraDecl("search", isRequired = true)))
        return AddonManifest(
            id = "cs3." + p.key,
            version = (plugin?.version ?: 0).toString(),
            name = p.name,
            description = listOfNotNull(plugin?.description, p.language?.uppercase(), plugin?.authors?.takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · ").ifBlank { null },
            logo = plugin?.iconUrl,
            types = types,
            resources = listOf(
                ResourceDecl("catalog"),
                ResourceDecl("meta", idPrefixes = listOf(prefix)),
                ResourceDecl("stream", idPrefixes = listOf(prefix)),
            ),
            catalogs = sections + search,
            idPrefixes = listOf(prefix),
            behaviorHints = BehaviorHints(adult = CsTypes.isAdult(p.types)),
        )
    }

    fun manifestUrl(p: ProviderInfo) = "cs3:" + p.key
}

/** One extension provider as an installed source (19.4). */
class CloudStreamClient(
    private val host: ExtensionHost,
    private val providerKey: String,
    private val manifest: AddonManifest,
    private val timeoutMs: Long,
    private val onRequest: (RequestResult) -> Unit = {},
) : AddonClient {
    override val ref = SourceRef(SourceKind.CLOUDSTREAM_EXTENSION, manifest.id, manifest.name)

    override val capabilities: Set<SourceCapability> =
        setOf(SourceCapability.CATALOGS, SourceCapability.SEARCH, SourceCapability.DETAILS, SourceCapability.STREAMS)

    /** Discover pages: the page number each "skip" stands for. */
    private val pages = ConcurrentHashMap<String, Int>()

    /** Movies' loadLinks data, found when their details were loaded. */
    private val dataFor = ConcurrentHashMap<String, String>()

    private suspend fun <T> timed(what: String, block: suspend () -> T): T {
        val started = System.currentTimeMillis()
        return try {
            block().also { onRequest(RequestResult(what, true, System.currentTimeMillis() - started, false)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            onRequest(RequestResult(what, false, System.currentTimeMillis() - started, false, e.message))
            throw SourceException(ref, e.message ?: "Extension error", e)
        }
    }

    override suspend fun catalogs(): List<Catalog> = manifest.catalogs.map(::toCatalog)

    override fun toCatalog(c: CatalogDecl) = Catalog(
        sourceId = manifest.id,
        id = c.key,
        type = StremioUrls.typeOf(c.type),
        name = c.name,
        supportsSearch = c.id == ProviderManifest.SEARCH_ID,
    )

    override suspend fun catalogPage(catalog: Catalog, filters: Map<String, String>, page: PageRequest): Page<Title> {
        val section = catalog.id.substringAfter('/').removePrefix("s").toIntOrNull() ?: return Page(emptyList(), null)
        val number = if (page.skip == 0) 1 else pages["${catalog.id}|${page.skip}"] ?: return Page(emptyList(), null)
        val r = timed("main page") { host.mainPage(providerKey, section, number, timeoutMs) }
        val items = r.sections.flatMap { it.items }.map(::title).distinctBy { it.id }
        val next = if (r.hasNext && items.isNotEmpty()) {
            val skip = page.skip + items.size
            pages["${catalog.id}|$skip"] = number + 1
            PageRequest(skip, page.limit)
        } else {
            null
        }
        return Page(items, next)
    }

    override suspend fun search(query: String, type: TitleType?): List<Title> {
        val r = timed("search") { host.search(providerKey, query, timeoutMs) }
        return r.items.map(::title).filter { type == null || it.type == type }
    }

    override suspend fun details(type: TitleType, titleId: String): Title? {
        val (isEpisode, url) = ExtensionIds.parse(providerKey, titleId) ?: return null
        if (isEpisode) return null
        val d = timed("details") { host.details(providerKey, url, timeoutMs) }
        d.dataUrl?.let { dataFor[titleId] = it }
        return Title(
            id = titleId,
            source = ref,
            type = CsTypes.of(d.type).let { if (it == TitleType.OTHER && d.episodes.isNotEmpty()) TitleType.SERIES else it },
            name = d.name,
            poster = d.poster,
            background = d.background,
            description = d.plot,
            genres = d.tags,
            runtime = d.durationMin?.let { "$it min" },
            year = d.year,
            releaseInfo = d.year?.toString(),
            imdbId = d.ids.entries.firstOrNull { it.key.equals("imdb", true) }?.value?.takeIf { it.startsWith("tt") },
            episodes = d.episodes.mapIndexed { i, e ->
                Episode(
                    id = ExtensionIds.episode(providerKey, e.data),
                    titleId = titleId,
                    season = e.season,
                    number = e.episode ?: (i + 1),
                    name = listOfNotNull(e.name, if (e.dubbed) "(Dub)" else null).joinToString(" ").ifBlank { null },
                    thumbnail = e.poster,
                    overview = e.description,
                )
            }.distinctBy { it.id },
        )
    }

    override suspend fun streams(type: TitleType, videoId: String): List<StreamOption> {
        val (isEpisode, value) = ExtensionIds.parse(providerKey, videoId) ?: return emptyList()
        val data = if (isEpisode) {
            value
        } else {
            dataFor[videoId] ?: timed("details") { host.details(providerKey, value, timeoutMs) }.let { d ->
                if (d.torrent) return emptyList()
                d.dataUrl ?: return emptyList()
            }
        }
        val r = timed("links") { host.links(providerKey, data, false, timeoutMs * 3) }
        val subs = r.subtitles.map { SubtitleOption(it.url, language = it.language, label = it.language, source = ref) }
        return r.links.map { l ->
            val headers = l.headers + (l.referer?.let { mapOf("Referer" to it) } ?: emptyMap())
            StreamOption(
                url = l.url,
                source = ref,
                name = listOfNotNull(l.name.takeIf { it.isNotBlank() }, l.quality?.let { "${it}p" }).joinToString(" "),
                description = l.source.takeIf { it != l.name },
                headers = headers,
                subtitles = subs,
                kind = when (l.type) {
                    "TORRENT", "MAGNET" -> StreamKind.TORRENT
                    else -> StreamKind.URL
                },
                mimeType = when (l.type) {
                    "M3U8" -> "application/x-mpegURL"
                    "DASH" -> "application/dash+xml"
                    else -> null
                },
            )
        }
    }

    override suspend fun subtitles(type: TitleType, videoId: String): List<SubtitleOption> = emptyList()

    override suspend fun subtitlesFor(type: TitleType, videoId: String, videoHash: String?, videoSize: Long?, filename: String?) =
        emptyList<SubtitleOption>()

    override fun supports(resource: String, type: TitleType, id: String): Boolean = when (resource) {
        "meta", "stream" -> id.startsWith(ExtensionIds.prefix(providerKey))
        else -> false
    }

    private fun title(i: ItemDto) = Title(
        id = ExtensionIds.title(providerKey, i.url),
        source = ref,
        type = CsTypes.of(i.type).let { if (it == TitleType.OTHER) TitleType.MOVIE else it },
        name = i.name,
        poster = i.poster,
        year = i.year,
    )
}
