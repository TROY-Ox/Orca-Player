package com.troy.orcaplayer.sources.cloudstream

import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * What crosses between the app and the ":extensions" process (19.3): plain data only, as JSON,
 * so no extension class ever reaches the UI or the player.
 */
object Protocol {
    const val OP_LOAD = 1
    const val OP_MAIN_PAGE = 2
    const val OP_SEARCH = 3
    const val OP_DETAILS = 4
    const val OP_LINKS = 5
    const val OP_CANCEL = 6

    const val KEY_ID = "id"
    const val KEY_JSON = "json"
    const val KEY_ERROR = "error"
}

@Serializable
data class LoadRequest(val files: List<ExtensionFile>)

@Serializable
data class ExtensionFile(val internalName: String, val path: String)

@Serializable
data class LoadResult(val providers: List<ProviderInfo> = emptyList(), val failures: Map<String, String> = emptyMap())

@Serializable
data class SectionInfo(val name: String, val data: String, val horizontal: Boolean = false)

@Serializable
data class ProviderInfo(
    /** Unique across extensions: "internalName.providerName". */
    val key: String,
    val internalName: String,
    val name: String,
    val language: String? = null,
    /** CloudStream TvType names. */
    val types: List<String> = emptyList(),
    val hasMainPage: Boolean = false,
    val sections: List<SectionInfo> = emptyList(),
    val mainUrl: String? = null,
)

@Serializable
data class MainPageRequestDto(val provider: String, val section: Int, val page: Int)

@Serializable
data class SearchRequestDto(val provider: String, val query: String)

@Serializable
data class DetailsRequestDto(val provider: String, val url: String)

@Serializable
data class LinksRequestDto(val provider: String, val data: String, val isCasting: Boolean = false)

@Serializable
data class ItemDto(
    val url: String,
    val name: String,
    val type: String? = null,
    val poster: String? = null,
    val year: Int? = null,
)

@Serializable
data class SectionDto(val name: String, val items: List<ItemDto>)

@Serializable
data class MainPageDto(val sections: List<SectionDto> = emptyList(), val hasNext: Boolean = false)

@Serializable
data class SearchDto(val items: List<ItemDto> = emptyList())

@Serializable
data class EpisodeDto(
    val data: String,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val poster: String? = null,
    val description: String? = null,
    val dubbed: Boolean = false,
)

@Serializable
data class DetailsDto(
    val url: String,
    val name: String,
    val type: String,
    val poster: String? = null,
    val background: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    val tags: List<String> = emptyList(),
    val durationMin: Int? = null,
    /** IMDb, TMDB, MAL and AniList IDs, for merging with other sources (19.3). */
    val ids: Map<String, String> = emptyMap(),
    /** For movies and live streams: what loadLinks needs. */
    val dataUrl: String? = null,
    val episodes: List<EpisodeDto> = emptyList(),
    val torrent: Boolean = false,
)

@Serializable
data class LinkDto(
    val url: String,
    val name: String,
    val source: String,
    val referer: String? = null,
    val quality: Int? = null,
    /** VIDEO, M3U8, DASH, TORRENT or MAGNET. */
    val type: String = "VIDEO",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class SubtitleDto(val url: String, val language: String)

@Serializable
data class LinksDto(val links: List<LinkDto> = emptyList(), val subtitles: List<SubtitleDto> = emptyList())

/** IDs the app uses for extension titles and episodes. They carry the extension's own URL or data. */
object ExtensionIds {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun prefix(providerKey: String) = "cs:$providerKey:"

    fun title(providerKey: String, url: String) = prefix(providerKey) + "t:" + enc.encodeToString(url.toByteArray())

    fun episode(providerKey: String, data: String) = prefix(providerKey) + "e:" + enc.encodeToString(data.toByteArray())

    /** (isEpisode, url or data) for an ID of this provider, or null. */
    fun parse(providerKey: String, id: String): Pair<Boolean, String>? {
        val rest = id.removePrefix(prefix(providerKey)).takeIf { it != id } ?: return null
        val episode = rest.startsWith("e:")
        if (!episode && !rest.startsWith("t:")) return null
        return runCatching { episode to String(dec.decode(rest.substring(2))) }.getOrNull()
    }
}
