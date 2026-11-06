package com.troy.orcaplayer.sources.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBadgesTest {
    @Test fun torrentioStyle() {
        val i = StreamBadges.parse("Torrentio\n4k HDR", "Movie.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos\n👤 42 💾 58.3 GB ⚙️ ThePirateBay")
        assertEquals(2160, i.resolution)
        assertTrue(HdrTag.DOLBY_VISION in i.hdr)
        assertTrue(HdrTag.HDR10 in i.hdr)
        assertTrue(AudioTag.ATMOS in i.audio)
        assertTrue(AudioTag.TRUEHD in i.audio)
        assertEquals("7.1", i.channels)
        assertEquals("HEVC", i.codec)
        assertEquals("REMUX", i.origin)
        assertEquals((58.3 * 1024 * 1024 * 1024).toLong(), i.sizeBytes)
    }

    @Test fun webDlWithDdp() {
        val i = StreamBadges.parse("1080p | WEB-DL | DDP5.1 Atmos | x264")
        assertEquals(1080, i.resolution)
        assertTrue(AudioTag.DDP in i.audio)
        assertEquals("5.1", i.channels)
        assertEquals("AVC", i.codec)
        assertEquals("WEB-DL", i.origin)
        assertTrue(i.hdr.isEmpty())
    }

    @Test fun languagesFromNamesCodesAndFlags() {
        val i = StreamBadges.parse("Arabic Dubbed 720p", "🇸🇦 / 🇬🇧 ENG")
        assertEquals(720, i.resolution)
        assertTrue("ar" in i.languages)
        assertTrue("en" in i.languages)
    }

    @Test fun insecureAndSizeHint() {
        val i = StreamBadges.parse("Direct", url = "http://example.com/a.mp4", sizeHint = 1_000_000L)
        assertTrue(i.insecure)
        assertEquals(1_000_000L, i.sizeBytes)
        assertFalse(StreamBadges.parse("Direct", url = "https://example.com/a.mp4").insecure)
    }

    @Test fun nothingKnown() {
        val i = StreamBadges.parse("Server 1", null)
        assertNull(i.resolution)
        assertNull(i.sizeBytes)
    }

    @Test fun rankingPrefersQualityThenLanguage() {
        val items = listOf("480p", "2160p", "1080p English", "1080p Arabic")
        val sorted = StreamRanking.sort(items, { StreamBadges.parse(it) }, { 0 }, StreamSort.QUALITY, preferredQuality = 1080, preferredLanguage = "ar")
        assertEquals("1080p Arabic", sorted[0])
        assertEquals("1080p English", sorted[1])
        assertEquals("480p", sorted.last())
    }
}
