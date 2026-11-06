package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable

/**
 * The only input the player accepts. Sources build a PlayRequest and hand it to the player,
 * they never call the player directly.
 */
@Serializable
data class PlayRequest(
    val items: List<PlayItem>,
    val startIndex: Int = 0,
    val profileId: String = Profile.DEFAULT_ID,
) {
    init {
        require(items.isNotEmpty()) { "A PlayRequest needs at least one item" }
        require(startIndex in items.indices) { "startIndex $startIndex is outside the queue" }
    }
}

@Serializable
data class PlayItem(
    /** http(s), rtsp, rtmp, content or file URI. */
    val uri: String,
    val title: String,
    val key: MediaKey,
    val source: SourceRef,
    val headers: Map<String, String> = emptyMap(),
    val subtitle: String? = null,
    val episode: EpisodeInfo? = null,
    val subtitles: List<SubtitleOption> = emptyList(),
    /** Start position in milliseconds. Null means "use smart resume". */
    val startPositionMs: Long? = null,
    val canSave: Boolean = false,
    val drmProtected: Boolean = false,
    val downloadable: Boolean = true,
) {
    init {
        require(UriSchemes.isAllowed(uri)) { "Scheme not allowed: $uri" }
    }
}

@Serializable
data class EpisodeInfo(
    val titleId: String,
    val episodeId: String,
    val season: Int? = null,
    val number: Int? = null,
    val name: String? = null,
)

/** From sources, open only http, https, rtsp, rtmp, content and file links. */
object UriSchemes {
    val allowed = setOf("http", "https", "rtsp", "rtmp", "content", "file")

    fun schemeOf(uri: String): String? {
        val i = uri.indexOf(':')
        if (i <= 0) return null
        val scheme = uri.substring(0, i)
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return scheme.lowercase()
    }

    fun isAllowed(uri: String): Boolean = schemeOf(uri) in allowed

    /** True for plain http links, which get an "insecure connection" badge. */
    fun isInsecure(uri: String): Boolean = schemeOf(uri) == "http"
}
