package com.troy.orcaplayer.tools

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.util.Locale

/** Labels are keys so the app can translate them. */
enum class InfoKey {
    FILE, CONTAINER, DURATION, SIZE, BITRATE,
    CODEC, PROFILE, RESOLUTION, FRAME_RATE, BIT_DEPTH, COLOR, HDR, ROTATION,
    CHANNELS, SAMPLE_RATE, LANGUAGE, TITLE,
    TAG_TITLE, TAG_ARTIST, TAG_ALBUM, TAG_DATE, TAG_GENRE, TAG_LOCATION,
}

enum class TrackKind { VIDEO, AUDIO, SUBTITLE, OTHER }

data class TrackInfo(val kind: TrackKind, val fields: List<Pair<InfoKey, String>>)

data class MediaInfo(
    val general: List<Pair<InfoKey, String>>,
    val tracks: List<TrackInfo>,
    val tags: List<Pair<InfoKey, String>>,
    val durationMs: Long = 0,
    /** -1 when unknown (for example a link). */
    val sizeBytes: Long = -1,
    val videoHeight: Int = 0,
    val videoMime: String? = null,
) {
    /** Plain text for "Copy as text", with the labels the app passes in. */
    fun asText(label: (InfoKey) -> String, trackLabel: (TrackKind, Int) -> String): String = buildString {
        general.forEach { (k, v) -> append(label(k)).append(": ").append(v).append('\n') }
        val counts = mutableMapOf<TrackKind, Int>()
        tracks.forEach { t ->
            val n = (counts[t.kind] ?: 0) + 1
            counts[t.kind] = n
            append('\n').append(trackLabel(t.kind, n)).append('\n')
            t.fields.forEach { (k, v) -> append("  ").append(label(k)).append(": ").append(v).append('\n') }
        }
        if (tags.isNotEmpty()) {
            append('\n')
            tags.forEach { (k, v) -> append(label(k)).append(": ").append(v).append('\n') }
        }
    }
}

/** Reads container, track and tag details with the platform extractor and metadata reader. */
object MediaInfoReader {
    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    fun bitrate(bps: Long): String = if (bps >= 1_000_000) String.format(Locale.US, "%.2f Mbps", bps / 1e6) else "${bps / 1000} kbps"

    private fun colorText(f: MediaFormat): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < 24) return null to null
        val standard = f.intOrNull(MediaFormat.KEY_COLOR_STANDARD)
        val transfer = f.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        val std = when (standard) {
            MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
            MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
            MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT.601"
            else -> null
        }
        val hdr = when (transfer) {
            MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
            MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
            else -> null
        }
        return std to hdr
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    private fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
    private fun MediaFormat.stringOrNull(key: String): String? = if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null
    private fun MediaFormat.floatOrNull(key: String): Float? = if (containsKey(key)) {
        runCatching { getFloat(key) }.getOrNull() ?: runCatching { getInteger(key).toFloat() }.getOrNull()
    } else null

    private fun codecName(mime: String): String = when (mime) {
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "H.265 (HEVC)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/eac3-joc" -> "E-AC-3 (Atmos)"
        "audio/true-hd" -> "TrueHD"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "audio/raw" -> "PCM"
        else -> mime.substringAfter('/').uppercase()
    }

    fun read(context: Context, uri: Uri, headers: Map<String, String> = emptyMap()): MediaInfo {
        val general = mutableListOf<Pair<InfoKey, String>>()
        val tags = mutableListOf<Pair<InfoKey, String>>()
        var sizeBytes = -1L
        var name: String? = null
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) sizeBytes = c.getLong(1)
                    }
                }
            }
        } else if (uri.scheme == "file" || uri.scheme == null) {
            uri.path?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { name = it.name; sizeBytes = it.length() }
        }
        name = name ?: uri.lastPathSegment
        name?.let { general += InfoKey.FILE to it }

        var durationMs = 0L
        val mmr = MediaMetadataRetriever()
        try {
            if (uri.scheme == "http" || uri.scheme == "https") mmr.setDataSource(uri.toString(), headers) else mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { general += InfoKey.CONTAINER to it.substringAfter('/').uppercase() }
            durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            fun tag(key: Int, k: InfoKey) = mmr.extractMetadata(key)?.takeIf { it.isNotBlank() }?.let { tags += k to it.trim() }
            tag(MediaMetadataRetriever.METADATA_KEY_TITLE, InfoKey.TAG_TITLE)
            tag(MediaMetadataRetriever.METADATA_KEY_ARTIST, InfoKey.TAG_ARTIST)
            tag(MediaMetadataRetriever.METADATA_KEY_ALBUM, InfoKey.TAG_ALBUM)
            tag(MediaMetadataRetriever.METADATA_KEY_DATE, InfoKey.TAG_DATE)
            tag(MediaMetadataRetriever.METADATA_KEY_GENRE, InfoKey.TAG_GENRE)
            tag(MediaMetadataRetriever.METADATA_KEY_LOCATION, InfoKey.TAG_LOCATION)
        } catch (_: Exception) {
        } finally {
            runCatching { mmr.release() }
        }

        val tracks = mutableListOf<TrackInfo>()
        var videoHeight = 0
        var videoMime: String? = null
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, headers)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.stringOrNull(MediaFormat.KEY_MIME) ?: continue
                val kind = when {
                    mime.startsWith("video/") -> TrackKind.VIDEO
                    mime.startsWith("audio/") -> TrackKind.AUDIO
                    mime.startsWith("text/") || mime.contains("subrip") || mime.contains("vtt") || mime.contains("ssa") -> TrackKind.SUBTITLE
                    else -> TrackKind.OTHER
                }
                val fields = mutableListOf<Pair<InfoKey, String>>(InfoKey.CODEC to codecName(mime))
                f.longOrNull(MediaFormat.KEY_DURATION)?.let { if (durationMs == 0L) durationMs = it / 1000 }
                if (kind == TrackKind.VIDEO) {
                    val w = f.intOrNull(MediaFormat.KEY_WIDTH)
                    val h = f.intOrNull(MediaFormat.KEY_HEIGHT)
                    if (w != null && h != null) fields += InfoKey.RESOLUTION to "$w×$h"
                    if (videoMime == null) {
                        videoMime = mime
                        videoHeight = minOf(w ?: 0, h ?: 0).takeIf { it > 0 } ?: (h ?: 0)
                    }
                    f.floatOrNull(MediaFormat.KEY_FRAME_RATE)?.let { fields += InfoKey.FRAME_RATE to String.format(Locale.US, "%.3f fps", it).replace(".000", "") }
                    f.intOrNull(MediaFormat.KEY_PROFILE)?.let { fields += InfoKey.PROFILE to it.toString() }
                    val (std, hdr) = colorText(f)
                    std?.let { fields += InfoKey.COLOR to it }
                    hdr?.let { fields += InfoKey.HDR to it }
                    if (std == "BT.2020" || hdr?.startsWith("HDR") == true || hdr == "HLG") fields += InfoKey.BIT_DEPTH to "10-bit"
                    if (Build.VERSION.SDK_INT >= 23) f.intOrNull(MediaFormat.KEY_ROTATION)?.takeIf { it != 0 }?.let { fields += InfoKey.ROTATION to "$it°" }
                }
                if (kind == TrackKind.AUDIO) {
                    f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.let { fields += InfoKey.CHANNELS to it.toString() }
                    f.intOrNull(MediaFormat.KEY_SAMPLE_RATE)?.let { fields += InfoKey.SAMPLE_RATE to String.format(Locale.US, "%.1f kHz", it / 1000.0) }
                }
                f.intOrNull(MediaFormat.KEY_BIT_RATE)?.let { fields += InfoKey.BITRATE to bitrate(it.toLong()) }
                f.stringOrNull(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() && it != "und" }?.let {
                    fields += InfoKey.LANGUAGE to (Locale.forLanguageTag(it).displayLanguage.ifEmpty { it })
                }
                f.stringOrNull("title")?.let { fields += InfoKey.TITLE to it }
                tracks += TrackInfo(kind, fields)
            }
        } catch (_: Exception) {
        } finally {
            ex.release()
        }

        if (durationMs > 0) general += InfoKey.DURATION to duration(durationMs)
        if (sizeBytes > 0) {
            general += InfoKey.SIZE to size(sizeBytes)
            if (durationMs > 0) general += InfoKey.BITRATE to bitrate(sizeBytes * 8 * 1000 / durationMs)
        }
        return MediaInfo(general, tracks, tags, durationMs, sizeBytes, videoHeight, videoMime)
    }
}
