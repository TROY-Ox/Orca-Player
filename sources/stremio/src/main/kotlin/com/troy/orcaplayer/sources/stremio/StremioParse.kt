package com.troy.orcaplayer.sources.stremio

import com.troy.orcaplayer.core.model.Episode
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.StreamKind
import com.troy.orcaplayer.core.model.StreamOption
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses addon responses one item at a time: a bad item is skipped, never the whole list (14.2).
 * Every function here returns an empty result for text that isn't JSON at all, and the caller
 * turns that into an error for this addon only.
 */
object StremioParse {

    class BadResponse(message: String) : Exception(message)

    private fun root(text: String): JsonObject =
        LenientJson.parse(text).obj() ?: throw BadResponse("The addon sent something that isn't JSON")

    /** Cache hints from the response body (14.1). Seconds; null when absent. */
    data class CacheHints(val maxAge: Long?, val staleRevalidate: Long?, val staleError: Long?)

    fun cacheHints(text: String): CacheHints {
        val r = LenientJson.parse(text).obj()
        return CacheHints(r.long("cacheMaxAge"), r.long("staleRevalidate"), r.long("staleError"))
    }

    fun metas(text: String, source: SourceRef, fallbackType: TitleType): List<Title> =
        root(text).objects("metas").mapNotNull { runCatching { meta(it, source, fallbackType) }.getOrNull() }

    fun metaDetail(text: String, source: SourceRef, fallbackType: TitleType): Title? =
        root(text).field("meta").obj()?.let { runCatching { meta(it, source, fallbackType) }.getOrNull() }

    fun meta(m: JsonObject, source: SourceRef, fallbackType: TitleType): Title? {
        val id = m.str("id") ?: m.str("imdb_id") ?: return null
        val name = m.str("name") ?: m.str("title") ?: return null
        val type = m.str("type")?.let { StremioUrls.typeOf(it) }?.takeIf { it != TitleType.OTHER } ?: fallbackType
        val links = m.objects("links")
        fun linkNames(category: String) = links.filter { it.str("category").equals(category, ignoreCase = true) }.mapNotNull { it.str("name") }
        val genres = m.strings("genres").ifEmpty { m.strings("genre") }.ifEmpty { linkNames("Genres") }
        val cast = m.strings("cast").ifEmpty { linkNames("Cast") }
        val directors = m.strings("director").ifEmpty { m.strings("directors") }.ifEmpty { linkNames("Directors") }
        val trailers = buildList {
            m.objects("trailers").forEach { t -> t.str("source")?.let { if (t.str("type")?.lowercase() != "clip" || isEmpty()) add(it) } }
            m.objects("trailerStreams").forEach { t -> t.str("ytId")?.let { add(it) } }
        }.distinct()
        val release = m.str("releaseInfo") ?: m.str("year")
        val year = release?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
            ?: m.str("released")?.take(4)?.toIntOrNull()
        val imdb = (m.str("imdb_id") ?: id.takeIf { it.startsWith("tt") })?.substringBefore(':')
        val episodes = m.objects("videos").mapNotNull { v -> runCatching { episode(v, id) }.getOrNull() }
            .sortedWith(compareBy<Episode>({ it.season ?: Int.MAX_VALUE }, { it.number ?: Int.MAX_VALUE }))
        return Title(
            id = id,
            source = source,
            type = type,
            name = name,
            poster = m.str("poster"),
            background = m.str("background"),
            logo = m.str("logo"),
            description = m.str("description"),
            releaseInfo = release,
            genres = genres,
            rating = m.str("imdbRating"),
            runtime = m.str("runtime"),
            episodes = episodes,
            cast = cast,
            directors = directors,
            trailers = trailers,
            imdbId = imdb,
            year = year,
            language = m.str("language"),
            country = m.str("country"),
        )
    }

    private fun episode(v: JsonObject, titleId: String): Episode? {
        val id = v.str("id") ?: return null
        return Episode(
            id = id,
            titleId = titleId,
            season = v.int("season"),
            number = v.int("episode") ?: v.int("number"),
            name = v.str("name") ?: v.str("title"),
            thumbnail = v.str("thumbnail"),
            overview = v.str("overview") ?: v.str("description"),
            released = v.str("released") ?: v.str("firstAired"),
        )
    }

    fun streams(text: String, source: SourceRef): List<StreamOption> =
        root(text).objects("streams").mapNotNull { runCatching { stream(it, source) }.getOrNull() }

    fun stream(s: JsonObject, source: SourceRef): StreamOption? {
        val hints = s.field("behaviorHints").obj()
        val (kind, target) = when {
            s.str("url") != null -> StreamKind.URL to s.str("url")!!
            s.str("ytId") != null -> StreamKind.YOUTUBE to s.str("ytId")!!
            s.str("externalUrl") != null -> StreamKind.EXTERNAL to s.str("externalUrl")!!
            s.str("infoHash") != null -> StreamKind.TORRENT to s.str("infoHash")!!
            else -> return null
        }
        if (kind == StreamKind.URL && !target.startsWith("http://", true) && !target.startsWith("https://", true) &&
            !target.startsWith("rtsp://", true) && !target.startsWith("rtmp://", true)
        ) {
            // magnet:, file: and the like can't be played here.
            return StreamOption(target, source, name = s.str("name") ?: s.str("title"), description = s.str("description") ?: s.str("title"), kind = StreamKind.UNSUPPORTED)
        }
        val headers = hints.field("proxyHeaders").obj().field("request").obj()?.let { h ->
            h.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { p -> p.text()?.let { k to it } } }.toMap()
        }.orEmpty()
        val subtitles = s.objects("subtitles").mapNotNull { subtitle(it, source) }
        return StreamOption(
            url = target,
            source = source,
            name = s.str("name"),
            // "title" is the older name for the description.
            description = s.str("description") ?: s.str("title"),
            headers = headers,
            subtitles = subtitles,
            downloadable = true,
            bingeGroup = hints.str("bingeGroup"),
            kind = kind,
            filename = hints.str("filename"),
            videoSize = hints.long("videoSize"),
            videoHash = hints.str("videoHash"),
            // Torrent streams: the file to play and the trackers ("tracker:udp://…" in "sources").
            fileIndex = if (kind == StreamKind.TORRENT) s.int("fileIdx") ?: s.str("fileIdx")?.toIntOrNull() else null,
            trackers = if (kind == StreamKind.TORRENT) s.strings("sources").filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") } else emptyList(),
        )
    }

    fun subtitles(text: String, source: SourceRef): List<SubtitleOption> =
        root(text).objects("subtitles").mapNotNull { subtitle(it, source) }

    private fun subtitle(o: JsonObject, source: SourceRef): SubtitleOption? {
        val url = o.str("url") ?: return null
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val lang = o.str("lang") ?: o.str("language")
        return SubtitleOption(url = url, language = lang, label = o.str("label") ?: lang?.let { "$it · ${source.sourceName}" } ?: source.sourceName, source = source)
    }

    /** An addon directory entry from addon_catalog (15.5). */
    data class AddonListing(val transportUrl: String, val manifest: AddonManifest?)

    fun addonCatalog(text: String): List<AddonListing> = root(text).objects("addons").mapNotNull { a ->
        runCatching {
            val url = a.str("transportUrl") ?: return@runCatching null
            val manifest = a.field("manifest")?.toString()?.let { runCatching { AddonManifest.parse(it) }.getOrNull() }
            AddonListing(url, manifest)
        }.getOrNull()
    }
}
