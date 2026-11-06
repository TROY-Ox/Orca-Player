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

@Serializable
data class StreamOption(
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
