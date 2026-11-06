package com.troy.orcaplayer.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMathTest {
    @Test
    fun readsDolbyVisionProfiles() {
        assertEquals(5, DolbyVision.profileOf("dvhe.05.06"))
        assertEquals(8, DolbyVision.profileOf("dvh1.08.07"))
        assertEquals(10, DolbyVision.profileOf("dav1.10.09"))
        assertNull(DolbyVision.profileOf("hvc1.2.4.L153"))
        assertTrue(DolbyVision.hasNoFallback(5))
        assertFalse(DolbyVision.hasNoFallback(8))
    }

    @Test
    fun profileFiveRanksBelowSdrWithoutDolbyVision() {
        val dv5 = StreamRanking.score(HdrKind.DOLBY_VISION, 5, 2160, deviceHasDolbyVision = false, displayHdr = true)
        val hdr10 = StreamRanking.score(HdrKind.HDR10, null, 2160, deviceHasDolbyVision = false, displayHdr = true)
        val sdr = StreamRanking.score(HdrKind.SDR, null, 1080, deviceHasDolbyVision = false, displayHdr = true)
        assertTrue(dv5 < sdr)
        assertTrue(sdr < hdr10)
        val dv5Supported = StreamRanking.score(HdrKind.DOLBY_VISION, 5, 2160, deviceHasDolbyVision = true, displayHdr = true)
        assertTrue(dv5Supported > hdr10)
    }

    private val modes = listOf(
        DisplayModeInfo(1, 1920, 1080, 60f),
        DisplayModeInfo(2, 1920, 1080, 50f),
        DisplayModeInfo(3, 1920, 1080, 23.976f),
        DisplayModeInfo(4, 1920, 1080, 24f),
        DisplayModeInfo(5, 3840, 2160, 24f),
        DisplayModeInfo(6, 1920, 1080, 120f),
    )

    @Test
    fun picksMatchingRefreshRate() {
        val current = modes[0]
        assertEquals(3, FrameRateMatch.pick(23.976f, modes, current)?.id)
        assertEquals(6, FrameRateMatch.pick(24f, modes, current)?.id)
        assertEquals(2, FrameRateMatch.pick(25f, modes, current)?.id)
        // 60 Hz already fits 30 fps (120 is preferred only when not already fitting).
        assertNull(FrameRateMatch.pick(30f, modes, current))
        assertNull(FrameRateMatch.pick(60f, modes, current))
    }

    @Test
    fun keepsResolution() {
        val only4k = listOf(DisplayModeInfo(1, 3840, 2160, 60f), DisplayModeInfo(2, 1920, 1080, 24f))
        assertNull(FrameRateMatch.pick(24f, only4k, only4k[0]))
    }
}
