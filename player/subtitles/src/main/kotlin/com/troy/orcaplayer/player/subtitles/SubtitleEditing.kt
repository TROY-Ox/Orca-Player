package com.troy.orcaplayer.player.subtitles

import kotlin.math.roundToLong

/** Writes cues as SRT, VTT or ASS. */
object SubtitleWriter {
    fun write(cues: List<SubtitleCue>, format: SubtitleFormat): String = when (format) {
        SubtitleFormat.VTT -> vtt(cues)
        SubtitleFormat.ASS -> ass(cues)
        else -> srt(cues)
    }

    private fun clock(ms: Long, sep: Char): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        val f = t % 1000
        return "%02d:%02d:%02d%c%03d".format(h, m, s, sep, f)
    }

    private fun styled(c: SubtitleCue, html: Boolean): String {
        var text = c.text
        if (html) {
            if (c.bold) text = "<b>$text</b>"
            if (c.italic) text = "<i>$text</i>"
        }
        return text
    }

    fun srt(cues: List<SubtitleCue>): String = buildString {
        cues.sortedBy { it.startMs }.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(clock(c.startMs, ',')).append(" --> ").append(clock(c.endMs, ',')).append('\n')
            append(styled(c, html = true)).append("\n\n")
        }
    }

    fun vtt(cues: List<SubtitleCue>): String = buildString {
        append("WEBVTT\n\n")
        cues.sortedBy { it.startMs }.forEach { c ->
            append(clock(c.startMs, '.')).append(" --> ").append(clock(c.endMs, '.')).append('\n')
            append(styled(c, html = true)).append("\n\n")
        }
    }

    private fun assClock(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%d:%02d:%02d.%02d".format(t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, (t % 1000) / 10)
    }

    fun ass(cues: List<SubtitleCue>): String = buildString {
        append("[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\n\n")
        append("[V4+ Styles]\n")
        append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        append("Style: Default,Arial,64,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,1,2,40,40,50,1\n\n")
        append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        cues.sortedBy { it.startMs }.forEach { c ->
            val tags = buildString {
                if (c.alignment != 2) append("\\an").append(c.alignment)
                if (c.bold) append("\\b1")
                if (c.italic) append("\\i1")
            }
            val text = (if (tags.isNotEmpty()) "{$tags}" else "") + c.text.replace("\n", "\\N")
            append("Dialogue: 0,").append(assClock(c.startMs)).append(',').append(assClock(c.endMs)).append(",Default,,0,0,0,,").append(text).append('\n')
        }
    }
}

/** Editing operations for the subtitle editor. Every function returns a new list, sorted by start. */
object SubtitleEdits {
    fun shift(cues: List<SubtitleCue>, deltaMs: Long): List<SubtitleCue> =
        cues.map { it.copy(startMs = (it.startMs + deltaMs).coerceAtLeast(0), endMs = (it.endMs + deltaMs).coerceAtLeast(0)) }.sorted()

    /**
     * Two-point sync: cue [a] should start at [aTargetMs] and cue [b] at [bTargetMs]. Everything is
     * mapped by the straight line through those two points, which fixes drift from frame-rate differences.
     */
    fun twoPointSync(cues: List<SubtitleCue>, a: Int, aTargetMs: Long, b: Int, bTargetMs: Long): List<SubtitleCue> {
        require(a in cues.indices && b in cues.indices && a != b)
        val a0 = cues[a].startMs.toDouble()
        val b0 = cues[b].startMs.toDouble()
        if (a0 == b0) return shift(cues, aTargetMs - cues[a].startMs)
        val scale = (bTargetMs - aTargetMs) / (b0 - a0)
        fun map(t: Long): Long = (aTargetMs + (t - a0) * scale).roundToLong().coerceAtLeast(0)
        return cues.map { it.copy(startMs = map(it.startMs), endMs = map(it.endMs)) }.sorted()
    }

    fun delete(cues: List<SubtitleCue>, index: Int): List<SubtitleCue> = cues.filterIndexed { i, _ -> i != index }

    fun add(cues: List<SubtitleCue>, cue: SubtitleCue): List<SubtitleCue> = (cues + cue).sorted()

    fun update(cues: List<SubtitleCue>, index: Int, cue: SubtitleCue): List<SubtitleCue> =
        cues.mapIndexed { i, c -> if (i == index) cue else c }.sorted()

    /** Splits a cue at [atMs] (inside it); the text is split at the line break nearest the middle, or in half by words. */
    fun split(cues: List<SubtitleCue>, index: Int, atMs: Long? = null): List<SubtitleCue> {
        val c = cues.getOrNull(index) ?: return cues
        val at = (atMs ?: ((c.startMs + c.endMs) / 2)).coerceIn(c.startMs + 1, (c.endMs - 1).coerceAtLeast(c.startMs + 1))
        val lines = c.text.split('\n')
        val (first, second) = if (lines.size > 1) {
            val mid = lines.size / 2
            lines.take(mid).joinToString("\n") to lines.drop(mid).joinToString("\n")
        } else {
            val words = c.text.split(' ')
            val mid = (words.size / 2).coerceAtLeast(1)
            words.take(mid).joinToString(" ") to words.drop(mid).joinToString(" ")
        }
        val out = cues.toMutableList()
        out[index] = c.copy(endMs = at, text = first)
        out.add(index + 1, c.copy(startMs = at, text = second.ifEmpty { first }))
        return out.sorted()
    }

    /** Merges cue [index] with the next one. */
    fun mergeWithNext(cues: List<SubtitleCue>, index: Int): List<SubtitleCue> {
        if (index !in 0 until cues.lastIndex) return cues
        val a = cues[index]
        val b = cues[index + 1]
        val merged = a.copy(endMs = maxOf(a.endMs, b.endMs), text = a.text + "\n" + b.text)
        return (cues.take(index) + merged + cues.drop(index + 2)).sorted()
    }

    /** Replaces [find] with [replace] in every cue. Returns the new list and how many cues changed. */
    fun replaceAll(cues: List<SubtitleCue>, find: String, replace: String, ignoreCase: Boolean = true): Pair<List<SubtitleCue>, Int> {
        if (find.isEmpty()) return cues to 0
        var changed = 0
        val out = cues.map { c ->
            val t = c.text.replace(find, replace, ignoreCase)
            if (t != c.text) changed++
            c.copy(text = t)
        }
        return out to changed
    }

    fun search(cues: List<SubtitleCue>, query: String): List<Int> =
        if (query.isBlank()) emptyList() else cues.indices.filter { cues[it].text.contains(query, ignoreCase = true) }

    private fun List<SubtitleCue>.sorted() = sortedBy { it.startMs }
}
