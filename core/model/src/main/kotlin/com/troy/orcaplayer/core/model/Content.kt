package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TitleType { MOVIE, SERIES, CHANNEL, TV, OTHER }

@Serializable
data class Title(
    val id: String,
    val source: SourceRef,
    val type: TitleType,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val runtime: String? = null,
    val episodes: List<Episode> = emptyList(),
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    /** YouTube IDs of trailers. Opened in the YouTube app or its embedded player, never extracted. */
    val trailers: List<String> = emptyList(),
    /** IMDb ID when known (Stremio IDs usually are), used to merge the same title from several sources. */
    val imdbId: String? = null,
    val year: Int? = null,
    val language: String? = null,
    val country: String? = null,
)

@Serializable
data class Episode(
    val id: String,
    val titleId: String,
    val season: Int? = null,
    val number: Int? = null,
    val name: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val released: String? = null,
)

/** How a stream is opened. Only [URL] plays in the app. */
@Serializable
enum class StreamKind { URL, EXTERNAL, YOUTUBE, TORRENT, UNSUPPORTED }

@Serializable
data class StreamOption(
    /** The playable URL for [StreamKind.URL]; the external link, YouTube ID or info hash otherwise. */
    val url: String,
    val source: SourceRef,
    val name: String? = null,
    val description: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleOption> = emptyList(),
    /** False when the source marks this stream as not downloadable. */
    val downloadable: Boolean = true,
    val drmProtected: Boolean = false,
    /** Optional grouping hint so the next stream of the same group is tried first on fallback. */
    val bingeGroup: String? = null,
    val kind: StreamKind = StreamKind.URL,
    /** Hints from the source for display, subtitle matching and downloads. */
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
    /** Set when the source says what the stream is (HLS or DASH) and the URL doesn't show it. */
    val mimeType: String? = null,
)

@Serializable
data class SubtitleOption(
    val url: String,
    val language: String? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val source: SourceRef? = null,
)
