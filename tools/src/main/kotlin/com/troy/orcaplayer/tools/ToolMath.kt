package com.troy.orcaplayer.tools

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/** Small, testable helpers for the tools. */
object ToolMath {
    /** Parses "1:02:03.5", "02:03", "123.25" or "1:02:03,500" into milliseconds, or null. */
    fun parseTime(text: String): Long? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for ((i, p) in parts.withIndex()) {
            val v = p.toDoubleOrNull() ?: return null
            if (v < 0) return null
            if (i < parts.lastIndex && (v != kotlin.math.floor(v))) return null
            if (i > 0 && v >= 60) return null
            total = total * 60 + v
        }
        return (total * 1000).roundToLong()
    }

    /** "1:02:03.500" or "02:03.500" for the clip editor fields. */
    fun formatTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        val f = t % 1000
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, f) else String.format(Locale.US, "%02d:%02d.%03d", m, s, f)
    }

    private val unsafe = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")
    private val spaces = Regex("\\s+")

    private fun safe(title: String, fallback: String) = title.replace(unsafe, " ").replace(spaces, " ").trim().ifEmpty { fallback }.take(80)

    /** A file name with the source title and the time range, safe on every file system. */
    fun clipName(title: String, startMs: Long, endMs: Long, ext: String): String {
        fun stamp(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) String.format(Locale.US, "%dh%02dm%02ds", s / 3600, (s / 60) % 60, s % 60) else String.format(Locale.US, "%02dm%02ds", s / 60, s % 60)
        }
        val base = safe(title, "Clip")
        return "$base ${stamp(startMs)}-${stamp(endMs)}.$ext"
    }

    fun outputName(title: String, suffix: String, ext: String): String {
        val base = safe(title, "Video")
        return "$base $suffix.$ext"
    }

    /** Video bitrate that makes a file of about [targetBytes], after the audio and ~2% container overhead. */
    fun videoBitrateFor(targetBytes: Long, durationMs: Long, audioBps: Int = 128_000): Int {
        if (durationMs <= 0) return 0
        val totalBits = targetBytes * 8 * 0.98
        val videoBps = totalBits / (durationMs / 1000.0) - audioBps
        return max(150_000.0, videoBps).toInt()
    }

    /** Expected output size for a video and audio bitrate. */
    fun estimateBytes(videoBps: Int, audioBps: Int, durationMs: Long): Long =
        ((videoBps.toLong() + audioBps) * durationMs / 8_000.0 * 1.02).roundToLong()

    /** Sleep timer fade: full volume until [fadeMs] before the end, then a smooth ramp to silence. */
    fun fadeVolume(remainingMs: Long, fadeMs: Long = 10_000): Float {
        if (remainingMs >= fadeMs) return 1f
        if (remainingMs <= 0) return 0f
        val x = remainingMs.toFloat() / fadeMs
        return x * x * (3 - 2 * x)
    }

    /** The real start of a fast (copy) clip: the last keyframe at or before [startMs]. */
    fun snapToKeyframe(keyframesMs: List<Long>, startMs: Long): Long =
        keyframesMs.lastOrNull { it <= startMs } ?: keyframesMs.firstOrNull() ?: startMs
}
