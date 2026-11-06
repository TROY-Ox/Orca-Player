package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable

/** An A-B repeat section, saved with the video when the user asks. */
@Serializable
data class AbLoop(val aMs: Long, val bMs: Long)

/**
 * Per-video (or per-folder) player choices (12.9). Null means "not set here", so the next level
 * applies: video, then folder, then the profile's setting, then the app default.
 * The equalizer preset and boost live in [AudioStore], with the same precedence.
 */
@Serializable
data class MediaPrefs(
    val subtitleLanguage: String? = null,
    val subtitlesOff: Boolean? = null,
    val subtitleDelayMs: Long? = null,
    val audioLanguage: String? = null,
    val speed: Float? = null,
    val aspect: String? = null,
    val zoom: Float? = null,
    val brightness: Float? = null,
    val audioDelayMs: Long? = null,
    val decoder: String? = null,
    val abLoop: AbLoop? = null,
    /** Picture filters for this video (20.6). */
    val filter: VideoFilter? = null,
) {
    val isEmpty: Boolean get() = this == MediaPrefs()

    /** Field by field: values set here win over [fallback]. */
    fun over(fallback: MediaPrefs): MediaPrefs = MediaPrefs(
        subtitleLanguage = subtitleLanguage ?: fallback.subtitleLanguage,
        subtitlesOff = subtitlesOff ?: fallback.subtitlesOff,
        subtitleDelayMs = subtitleDelayMs ?: fallback.subtitleDelayMs,
        audioLanguage = audioLanguage ?: fallback.audioLanguage,
        speed = speed ?: fallback.speed,
        aspect = aspect ?: fallback.aspect,
        zoom = zoom ?: fallback.zoom,
        brightness = brightness ?: fallback.brightness,
        audioDelayMs = audioDelayMs ?: fallback.audioDelayMs,
        decoder = decoder ?: fallback.decoder,
        abLoop = abLoop ?: fallback.abLoop,
        filter = filter ?: fallback.filter,
    )
}

@Serializable
data class MediaPrefsStore(
    val perMedia: Map<String, MediaPrefs> = emptyMap(),
    val perFolder: Map<String, MediaPrefs> = emptyMap(),
) {
    /** Video values over folder values. Profile and app defaults are applied by the caller for whatever is still null. */
    fun resolve(mediaKey: String?, folderKey: String?): MediaPrefs {
        val video = mediaKey?.let { perMedia[it] } ?: MediaPrefs()
        val folder = folderKey?.let { perFolder[it] } ?: MediaPrefs()
        return video.over(folder)
    }

    fun updateMedia(key: String, change: (MediaPrefs) -> MediaPrefs) = copy(perMedia = bounded(perMedia, key, change(perMedia[key] ?: MediaPrefs())))
    fun updateFolder(key: String, change: (MediaPrefs) -> MediaPrefs) = copy(perFolder = bounded(perFolder, key, change(perFolder[key] ?: MediaPrefs())))
    fun resetMedia(key: String) = copy(perMedia = perMedia - key)
    fun resetFolder(key: String) = copy(perFolder = perFolder - key)

    companion object {
        const val MAX_SAVED = 1000

        private fun bounded(map: Map<String, MediaPrefs>, key: String, value: MediaPrefs): Map<String, MediaPrefs> {
            val next = LinkedHashMap(map)
            next.remove(key)
            if (!value.isEmpty) next[key] = value
            while (next.size > MAX_SAVED) next.remove(next.keys.first())
            return next
        }
    }
}
