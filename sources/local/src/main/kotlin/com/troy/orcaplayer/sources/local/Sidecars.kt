package com.troy.orcaplayer.sources.local

import java.io.File

/** Finds subtitle files next to a video with a matching name, such as movie.srt or movie.ar.srt. */
object Sidecars {
    val extensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp", "xml")

    data class Match(val file: File, val language: String?)

    fun matches(videoName: String, candidates: List<String>): List<Pair<String, String?>> {
        val base = videoName.substringBeforeLast('.').lowercase()
        return candidates.mapNotNull { name ->
            val lower = name.lowercase()
            val ext = lower.substringAfterLast('.', "")
            if (ext !in extensions) return@mapNotNull null
            val stem = lower.substringBeforeLast('.')
            when {
                stem == base -> name to null
                stem.startsWith("$base.") -> name to stem.removePrefix("$base.").substringAfterLast('.').takeIf { it.length in 2..8 }
                else -> null
            }
        }.sortedBy { it.second ?: "" }
    }

    /** Works when the app can read the folder (file paths, older Android versions). Empty otherwise. */
    fun find(videoPath: String?): List<Match> {
        if (videoPath == null) return emptyList()
        val video = File(videoPath)
        val dir = video.parentFile ?: return emptyList()
        val names = runCatching { dir.list()?.toList() }.getOrNull().orEmpty()
        return matches(video.name, names).map { (name, lang) -> Match(File(dir, name), lang) }.filter { it.file.canRead() }
    }
}
