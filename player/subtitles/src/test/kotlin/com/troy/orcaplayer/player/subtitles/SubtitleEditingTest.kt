package com.troy.orcaplayer.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleEditingTest {
    private val cues = listOf(
        SubtitleCue(1_000, 2_000, "One"),
        SubtitleCue(10_000, 12_000, "Two\nlines"),
        SubtitleCue(100_000, 101_500, "Three", italic = true),
    )

    @Test
    fun srtRoundTrips() {
        val text = SubtitleWriter.srt(cues)
        assertTrue(text.startsWith("1\n00:00:01,000 --> 00:00:02,000\nOne\n"))
        val back = SubtitleParser.parse(text, "a.srt").cues
        assertEquals(3, back.size)
        assertEquals(10_000, back[1].startMs)
        assertEquals("Two\nlines", back[1].text)
        assertTrue(back[2].italic)
    }

    @Test
    fun vttAndAssParseBack() {
        assertEquals(3, SubtitleParser.parse(SubtitleWriter.vtt(cues), "a.vtt").cues.size)
        val ass = SubtitleParser.parse(SubtitleWriter.ass(cues), "a.ass").cues
        assertEquals(3, ass.size)
        assertEquals(101_500, ass[2].endMs)
    }

    @Test
    fun twoPointSyncStretchesBetween() {
        // A 25 fps file over 23.976 fps video drifts: line 1 should be at 1.2 s, line 3 at 104 s.
        val out = SubtitleEdits.twoPointSync(cues, 0, 1_200, 2, 104_000)
        assertEquals(1_200, out[0].startMs)
        assertEquals(104_000, out[2].startMs)
        assertTrue(out[1].startMs in 10_500..11_500)
    }

    @Test
    fun splitMergeShift() {
        val split = SubtitleEdits.split(cues, 1)
        assertEquals(4, split.size)
        assertEquals("Two", split[1].text)
        assertEquals("lines", split[2].text)
        assertEquals(split[1].endMs, split[2].startMs)
        val merged = SubtitleEdits.mergeWithNext(split, 1)
        assertEquals("Two\nlines", merged[1].text)
        assertEquals(12_000, merged[1].endMs)
        assertEquals(500, SubtitleEdits.shift(cues, -500)[0].startMs)
        val (replaced, n) = SubtitleEdits.replaceAll(cues, "two", "2")
        assertEquals(1, n)
        assertEquals("2\nlines", replaced[1].text)
    }
}
