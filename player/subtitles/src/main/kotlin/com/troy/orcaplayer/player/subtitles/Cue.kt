package com.troy.orcaplayer.player.subtitles

/** One subtitle cue in the app's own model. All renderers, delay, sync and the editor share it. */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    /** Plain text, lines separated by '\n'. Styling tags are removed. */
    val text: String,
    val italic: Boolean = false,
    val bold: Boolean = false,
    /** ASS alignment 1..9 (numpad layout), 2 = bottom centre. */
    val alignment: Int = 2,
)

enum class SubtitleFormat { SRT, VTT, ASS, UNKNOWN }

/** Cues sorted by start, with delay and frame-rate speed correction applied at lookup. */
class CueTimeline(cues: List<SubtitleCue>) {
    val cues: List<SubtitleCue> = cues.sortedBy { it.startMs }

    /**
     * Cues visible at [positionMs]. [delayMs] > 0 shows subtitles later. [speed] corrects files made
     * for another frame rate, for example 25.0 / 23.976 when a 25 fps file plays over 23.976 fps video.
     */
    fun at(positionMs: Long, delayMs: Long = 0, speed: Double = 1.0): List<SubtitleCue> {
        val t = ((positionMs - delayMs) * speed).toLong()
        // Binary search for the last cue starting at or before t, then scan back over overlaps.
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return emptyList()
        val out = ArrayList<SubtitleCue>(2)
        var i = idx
        var scanned = 0
        while (i >= 0 && scanned < 50) {
            val c = cues[i]
            if (c.endMs > t) out.add(c)
            i--
            scanned++
        }
        return out.asReversed()
    }

    companion object {
        val Empty = CueTimeline(emptyList())
    }
}
