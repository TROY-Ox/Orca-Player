package com.troy.orcaplayer.player.subtitles

object SubtitleParser {
    fun detectFormat(text: String, fileName: String? = null): SubtitleFormat {
        val name = fileName?.lowercase().orEmpty()
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').take(200)
        return when {
            head.startsWith("WEBVTT") || name.endsWith(".vtt") -> SubtitleFormat.VTT
            head.startsWith("[Script Info]", ignoreCase = true) || name.endsWith(".ass") || name.endsWith(".ssa") -> SubtitleFormat.ASS
            name.endsWith(".srt") || Regex("""\d+\s*\r?\n\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""").containsMatchIn(head) -> SubtitleFormat.SRT
            head.contains("-->") -> SubtitleFormat.SRT
            else -> SubtitleFormat.UNKNOWN
        }
    }

    fun parse(text: String, fileName: String? = null): CueTimeline {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = when (detectFormat(clean, fileName)) {
            SubtitleFormat.SRT, SubtitleFormat.UNKNOWN -> parseSrtOrVtt(clean)
            SubtitleFormat.VTT -> parseSrtOrVtt(clean)
            SubtitleFormat.ASS -> parseAss(clean)
        }
        return CueTimeline(cues)
    }

    private val timing = Regex("""((?:\d+:)?\d{1,2}:\d{2}(?:[,.]\d{1,3})?)\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}(?:[,.]\d{1,3})?)""")
    private val tag = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /** Parses "01:02:03,456", "02:03.456" or "1:02:03.4". */
    fun parseTimestamp(s: String): Long {
        val parts = s.trim().replace(',', '.').split(':')
        var h = 0L
        val m: Long
        val secPart: String
        if (parts.size == 3) {
            h = parts[0].toLong(); m = parts[1].toLong(); secPart = parts[2]
        } else {
            m = parts[0].toLong(); secPart = parts[1]
        }
        val sec = secPart.substringBefore('.').toLong()
        val fracRaw = secPart.substringAfter('.', "")
        val ms = when (fracRaw.length) {
            0 -> 0L
            1 -> fracRaw.toLong() * 100
            2 -> fracRaw.toLong() * 10
            else -> fracRaw.take(3).toLong()
        }
        return ((h * 60 + m) * 60 + sec) * 1000 + ms
    }

    private fun parseSrtOrVtt(text: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val match = timing.find(lines[i])
            if (match == null) { i++; continue }
            val start = runCatching { parseTimestamp(match.groupValues[1]) }.getOrNull()
            val end = runCatching { parseTimestamp(match.groupValues[2]) }.getOrNull()
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                // A missing blank line before the next cue: stop at the next timing line.
                if (timing.containsMatchIn(lines[i])) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (start == null || end == null) continue
            val raw = body.toString()
            val italic = raw.contains("<i>", ignoreCase = true) || raw.contains("{\\i1}")
            val bold = raw.contains("<b>", ignoreCase = true) || raw.contains("{\\b1}")
            val plain = tag.replace(raw, "").trim()
            if (plain.isNotEmpty()) out.add(SubtitleCue(start, end, plain, italic, bold))
        }
        return out
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        var format: List<String> = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        var inEvents = false
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.startsWith("[")) { inEvents = t.equals("[Events]", ignoreCase = true); continue }
            if (!inEvents) continue
            if (t.startsWith("Format:", ignoreCase = true)) {
                format = t.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!t.startsWith("Dialogue:", ignoreCase = true)) continue
            val fields = t.substringAfter(':').split(',', limit = format.size).map { it.trim() }
            if (fields.size < format.size) continue
            val get = { key: String -> fields.getOrNull(format.indexOf(key)) }
            val start = get("start")?.let { runCatching { parseTimestamp(it) }.getOrNull() } ?: continue
            val end = get("end")?.let { runCatching { parseTimestamp(it) }.getOrNull() } ?: continue
            val rawText = get("text") ?: continue
            val alignment = Regex("""\\an(\d)""").find(rawText)?.groupValues?.get(1)?.toIntOrNull() ?: 2
            val italic = rawText.contains("\\i1")
            val bold = rawText.contains("\\b1")
            val plain = tag.replace(rawText, "")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00A0")
                .trim()
            if (plain.isNotEmpty()) out.add(SubtitleCue(start, end, plain, italic, bold, alignment))
        }
        return out
    }
}
