package com.troy.orcaplayer.downloads.logic

import java.net.URLDecoder

/**
 * File names from the naming template (16.5). Characters that file systems reject are removed;
 * every other letter, Arabic included, is kept.
 */
object Naming {
    const val MOVIE_TEMPLATE = "{title} ({year})"
    const val EPISODE_TEMPLATE = "{series} S{season}E{episode} {episode_title}"
    const val FALLBACK = "Download"
    private const val MAX_LENGTH = 120

    private val placeholder = Regex("\\{([a-z_]+)\\}")
    private val invalid = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")

    fun render(template: String, meta: DownloadMeta): String {
        val values = mapOf(
            "title" to (if (meta.isEpisode) meta.episodeTitle else meta.title),
            "series" to meta.title,
            "year" to meta.year?.toString(),
            "season" to meta.season?.let { pad(it) },
            "episode" to meta.episode?.let { pad(it) },
            "episode_title" to meta.episodeTitle,
            "quality" to meta.quality,
        )
        // "S{season}E{episode}" becomes "S01E02", "E02" without a season, or nothing.
        val code = when {
            meta.season != null && meta.episode != null -> "S${pad(meta.season)}E${pad(meta.episode)}"
            meta.episode != null -> "E${pad(meta.episode)}"
            else -> ""
        }
        val filled = placeholder.replace(template.replace("S{season}E{episode}", code)) { m -> values[m.groupValues[1]].orEmpty() }
        return tidy(filled)
    }

    private fun pad(n: Int) = if (n in 0..9) "0$n" else n.toString()

    /** Removes what an empty placeholder leaves behind: "()", doubled spaces and dangling separators. */
    private fun tidy(s: String): String {
        var out = s
            .replace(Regex("\\(\\s*\\)"), "")
            .replace(Regex("\\[\\s*\\]"), "")
        out = out.replace(Regex("\\s+"), " ").trim()
        out = out.trim('-', '·', '.', ' ', '_')
        return out
    }

    fun sanitize(name: String): String {
        var s = invalid.replace(name, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (s.codePointCount(0, s.length) > MAX_LENGTH) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_LENGTH)).trim()
        }
        return s.ifEmpty { FALLBACK }
    }

    /** The file name for a download, with [extension] (no dot). */
    fun fileName(config: DownloadConfig, meta: DownloadMeta, extension: String?): String {
        val template = if (meta.isEpisode) config.episodeTemplate else config.movieTemplate
        val base = sanitize(render(template.ifBlank { if (meta.isEpisode) EPISODE_TEMPLATE else MOVIE_TEMPLATE }, meta))
        return if (extension.isNullOrBlank()) base else "$base.$extension"
    }

    /** "name.ar.srt" next to "name.mkv"; a second file in the same language becomes "name.ar.2.srt". */
    fun subtitleName(videoName: String, language: String?, extension: String, taken: Set<String>): String {
        val base = videoName.substringBeforeLast('.', videoName)
        val lang = language?.lowercase()?.filter { it.isLetterOrDigit() || it == '-' }?.takeIf { it.isNotEmpty() }
        val stem = if (lang != null) "$base.$lang" else base
        var name = "$stem.$extension"
        var n = 2
        while (name in taken) {
            name = "$stem.$n.$extension"
            n++
        }
        return name
    }

    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "mpg", "mpeg", "wmv", "flv", "3gp", "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav")

    /** The extension from the server's file name, the link, or the MIME type, in that order. */
    fun extensionFor(url: String, mime: String?, disposition: String?): String {
        disposition?.let(::fileNameFromDisposition)?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in videoExtensions }?.let { return it }
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        path.substringAfterLast('.', "").lowercase().takeIf { it in videoExtensions }?.let { return it }
        return when (mime?.substringBefore(';')?.trim()?.lowercase()) {
            "video/x-matroska", "video/matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/x-msvideo", "video/avi" -> "avi"
            "video/quicktime" -> "mov"
            "video/mp2t" -> "ts"
            "audio/mp4" -> "m4a"
            "audio/mpeg" -> "mp3"
            else -> "mp4"
        }
    }

    fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> "video/mp4"
    }

    /** Reads filename*= (RFC 5987) or filename= from a Content-Disposition header. */
    fun fileNameFromDisposition(header: String): String? {
        val star = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (star != null) {
            val charset = star.groupValues[1].ifBlank { "UTF-8" }
            return runCatching { URLDecoder.decode(star.groupValues[2].trim().trim('"'), charset) }.getOrNull()
        }
        val plain = Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(header) ?: return null
        return (plain.groupValues[2].ifEmpty { plain.groupValues[1] }).trim().takeIf { it.isNotEmpty() }
    }

    /** The subtitle file extension from its link or MIME type. */
    fun subtitleExtension(url: String, mime: String?): String {
        val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (ext in setOf("srt", "vtt", "ass", "ssa")) return ext
        return when (mime?.lowercase()) {
            "text/vtt" -> "vtt"
            "text/x-ssa", "text/x-ass" -> "ass"
            else -> "srt"
        }
    }
}
