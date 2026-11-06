package com.troy.orcaplayer.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {
    private val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hello</i>\r\nworld\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\nمرحبا\r\n"

    @Test
    fun parsesSrt() {
        val t = SubtitleParser.parse(srt, "movie.ar.srt")
        assertEquals(2, t.cues.size)
        assertEquals(1000L, t.cues[0].startMs)
        assertEquals(3500L, t.cues[0].endMs)
        assertEquals("Hello\nworld", t.cues[0].text)
        assertTrue(t.cues[0].italic)
        assertEquals("مرحبا", t.cues[1].text)
    }

    @Test
    fun parsesVtt() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000 align:middle\nHi\n"
        val t = SubtitleParser.parse(vtt)
        assertEquals(1, t.cues.size)
        assertEquals(1000L, t.cues[0].startMs)
    }

    @Test
    fun parsesAss() {
        val ass = """
            [Script Info]
            Title: x

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:02.00,Default,,0,0,0,,{\an8}{\i1}Top, line\Nsecond
        """.trimIndent()
        val c = SubtitleParser.parse(ass).cues.single()
        assertEquals(1500L, c.startMs)
        assertEquals("Top, line\nsecond", c.text)
        assertEquals(8, c.alignment)
        assertTrue(c.italic)
    }

    @Test
    fun timelineAppliesDelayAndSpeed() {
        val t = SubtitleParser.parse(srt)
        assertEquals("Hello\nworld", t.at(2000).single().text)
        assertTrue(t.at(2000, delayMs = 1500).isEmpty())
        assertEquals(1, t.at(3000, delayMs = 1500).size)
        assertTrue(t.at(500).isEmpty())
    }

    @Test
    fun decodesWindows1256() {
        val bytes = "مرحبا".toByteArray(charset("windows-1256"))
        assertEquals("مرحبا", ArabicText.decode(bytes))
        assertEquals("مرحبا", ArabicText.decode("مرحبا".toByteArray()))
    }

    @Test
    fun fixesPunctuation() {
        assertEquals("مرحبا.", ArabicText.fixPunctuation(".مرحبا"))
        assertEquals(".hello", ArabicText.fixPunctuation(".hello"))
    }
}
