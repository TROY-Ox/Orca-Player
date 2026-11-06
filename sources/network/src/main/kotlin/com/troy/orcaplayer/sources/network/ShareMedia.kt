package com.troy.orcaplayer.sources.network

/** Which files on a share the player can open. */
object ShareMedia {
    val video = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "wmv", "flv", "mpg", "mpeg", "3gp", "ogv", "vob")
    val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "alac", "ape", "mka")

    fun isPlayable(e: ShareEntry): Boolean {
        if (e.isFolder) return false
        e.mimeType?.let { if (it.startsWith("video/") || it.startsWith("audio/")) return true }
        return e.name.substringAfterLast('.', "").lowercase().let { it in video || it in audio }
    }

    fun isAudio(e: ShareEntry): Boolean =
        e.mimeType?.startsWith("audio/") == true || e.name.substringAfterLast('.', "").lowercase() in audio

    fun mimeType(e: ShareEntry): String? = e.mimeType ?: when (e.name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
}
