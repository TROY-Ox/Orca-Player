package com.troy.orcaplayer.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolMathTest {
    @Test
    fun parsesTimes() {
        assertEquals(3_723_500L, ToolMath.parseTime("1:02:03.5"))
        assertEquals(123_000L, ToolMath.parseTime("02:03"))
        assertEquals(123_250L, ToolMath.parseTime("123.25"))
        assertEquals(3_723_500L, ToolMath.parseTime("1:02:03,500"))
        assertNull(ToolMath.parseTime("1:75"))
        assertNull(ToolMath.parseTime("abc"))
        assertEquals("1:02:03.500", ToolMath.formatTime(3_723_500))
        assertEquals("02:03.000", ToolMath.formatTime(123_000))
    }

    @Test
    fun clipNamesAreSafe() {
        assertEquals("Movie Part 1 01m05s-01m20s.mp4", ToolMath.clipName("Movie: Part/1", 65_000, 80_000, "mp4"))
    }

    @Test
    fun bitrateForTargetSize() {
        // 10 minutes into 100 MB: about 1.2 Mbps of video after 128 kbps audio.
        val bps = ToolMath.videoBitrateFor(100L * 1024 * 1024, 600_000)
        assertTrue(bps in 1_100_000..1_300_000)
        val size = ToolMath.estimateBytes(bps, 128_000, 600_000)
        assertTrue(size in 100L * 1024 * 1024 * 95 / 100..100L * 1024 * 1024 * 105 / 100)
    }

    @Test
    fun fadeAndKeyframes() {
        assertEquals(1f, ToolMath.fadeVolume(20_000))
        assertEquals(0.5f, ToolMath.fadeVolume(5_000), 0.001f)
        assertEquals(0f, ToolMath.fadeVolume(0))
        assertEquals(4_000L, ToolMath.snapToKeyframe(listOf(0, 2_000, 4_000, 6_000), 5_500))
        assertEquals(0L, ToolMath.snapToKeyframe(listOf(0, 2_000), 1_999))
    }
}
