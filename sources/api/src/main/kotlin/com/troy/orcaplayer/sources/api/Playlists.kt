package com.troy.orcaplayer.sources.api

import kotlinx.serialization.Serializable

/** One entry of an M3U playlist. */
@Serializable
data class PlaylistEntry(
    val url: String,
    val name: String? = null,
    val logo: String? = null,
    val group: String? = null,
    val durationSec: Long? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** M3U and M3U8 lists (13). */
object Playlists {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    /**
     * ".m3u8" is used both for HLS streams and for plain playlists. Any #EXT-X- tag means HLS:
     * play the file itself. Otherwise it is a playlist.
     */
    fun isHls(text: String): Boolean = text.lineSequence().take(400).any { it.trimStart().startsWith("#EXT-X-") }

    fun looksLikeM3u(text: String): Boolean {
        val head = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return head.startsWith("#EXTM3U") || head.lineSequence().take(50).any { it.startsWith("#EXTINF") }
    }

    fun parse(text: String, baseUrl: String? = null): List<PlaylistEntry> {
        val out = ArrayList<PlaylistEntry>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var duration: Long? = null
        val headers = LinkedHashMap<String, String>()
        for (rawLine in text.removePrefix("﻿").lineSequence()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() || line.startsWith("#EXTM3U") -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    val comma = lastTopLevelComma(body)
                    val meta = if (comma >= 0) body.substring(0, comma) else body
                    name = if (comma >= 0) body.substring(comma + 1).trim().ifEmpty { null } else null
                    duration = meta.trim().split(' ').firstOrNull()?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
                    val attrs = attr.findAll(meta).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.ifBlank { null }
                    group = attrs["group-title"]?.ifBlank { null }
                    if (name == null) name = attrs["tvg-name"]?.ifBlank { null }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-origin" -> headers["Origin"] = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = resolve(line, baseUrl)
                    out += PlaylistEntry(url, name, logo, group, duration, headers.toMap())
                    name = null; logo = null; group = null; duration = null
                    headers.clear()
                }
            }
        }
        return out
    }

    /** The comma before the title, skipping commas inside quoted attribute values. */
    private fun lastTopLevelComma(s: String): Int {
        var quoted = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return -1
    }

    private fun resolve(line: String, base: String?): String {
        if (base == null || line.contains("://")) return line
        val root = base.substringBeforeLast('/', base)
        return if (line.startsWith("/")) {
            val scheme = base.substringBefore("://")
            val host = base.substringAfter("://").substringBefore('/')
            "$scheme://$host$line"
        } else {
            "$root/$line"
        }
    }

    fun export(entries: List<PlaylistEntry>): String = buildString {
        append("#EXTM3U\n")
        entries.forEach { e ->
            append("#EXTINF:").append(e.durationSec ?: -1)
            e.logo?.let { append(" tvg-logo=\"").append(it.replace("\"", "")).append('"') }
            e.group?.let { append(" group-title=\"").append(it.replace("\"", "")).append('"') }
            append(',').append(e.name?.replace('\n', ' ') ?: e.url).append('\n')
            e.headers["Referer"]?.let { append("#EXTVLCOPT:http-referrer=").append(it).append('\n') }
            e.headers["User-Agent"]?.let { append("#EXTVLCOPT:http-user-agent=").append(it).append('\n') }
            append(e.url).append('\n')
        }
    }
}

/** Recognises media links in pasted or copied text (13). */
object MediaLinks {
    private val urlRe = Regex("""(?i)\b(?:https?|rtsp|rtmps?|stremio)://[^\s<>"']+""")
    private val mediaExt = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "flv", "wmv", "3gp",
        "m3u8", "m3u", "mpd", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "pls",
    )

    /** The first link in [text], or null. */
    fun firstLink(text: String): String? = urlRe.find(text)?.value?.trimEnd('.', ',', ')', ']', ';')

    enum class Kind { HLS, DASH, PLAYLIST, STREMIO_MANIFEST, RTSP, RTMP, MEDIA, PAGE }

    fun kindOf(url: String): Kind {
        val u = url.lowercase()
        val path = u.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when {
            u.startsWith("stremio://") || path.endsWith("/manifest.json") -> Kind.STREMIO_MANIFEST
            u.startsWith("rtsp://") -> Kind.RTSP
            u.startsWith("rtmp") -> Kind.RTMP
            ext == "m3u8" -> Kind.HLS
            ext == "mpd" -> Kind.DASH
            ext == "m3u" || ext == "pls" -> Kind.PLAYLIST
            ext in mediaExt -> Kind.MEDIA
            else -> Kind.PAGE
        }
    }

    /** Worth offering to play from the clipboard: a media file, stream or playlist, not an ordinary page. */
    fun isLikelyMedia(url: String): Boolean = kindOf(url) !in setOf(Kind.PAGE, Kind.STREMIO_MANIFEST)
}
