package com.troy.orcaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The test folder of movies and series from the Phase 9 done-when check. */
class NameParserTest {
    private fun p(name: String, vararg folders: String) = NameParser.parse(name, folders.toList())

    @Test
    fun movies() {
        assertEquals(ParsedName("The Matrix", 1999), p("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv", "Movies"))
        assertEquals(ParsedName("Inception", 2010), p("Inception (2010).mkv", "Inception (2010)"))
        assertEquals(ParsedName("2001 A Space Odyssey", 1968), p("2001 A Space Odyssey (1968).mp4"))
        assertEquals(ParsedName("Blade Runner 2049", 2017), p("Blade Runner 2049 (2017) [2160p].mkv"))
        assertEquals(ParsedName("1917", 2019), p("1917.2019.1080p.mkv"))
        assertEquals(ParsedName("Avengers Endgame", 2019), p("Avengers.Endgame.2019.2160p.UHD.BluRay.x265.10bit.HDR.DTS-HD.mkv"))
        assertEquals(ParsedName("Dune Part Two", 2024), p("Dune Part Two 2024 WEB-DL 1080p.mkv"))
        assertEquals(ParsedName("Arrival", 2016), p("movie.mkv", "Arrival (2016)"))
        assertEquals(ParsedName("Some Video"), p("Some Video 1920x1080.mp4"))
        assertFalse(p("Inception (2010).mkv").isEpisode)
    }

    @Test
    fun episodes() {
        assertEquals(ParsedName("Breaking Bad", season = 1, episode = 2), p("Breaking.Bad.S01E02.720p.HDTV.mkv", "Season 1", "Breaking Bad"))
        assertEquals(ParsedName("The Office US", season = 2, episode = 5), p("S02E05.mkv", "Season 2", "The Office (US)"))
        assertEquals(ParsedName("Breaking Bad", season = 1, episode = 2), p("02 - Cat's in the Bag.mkv", "Season 1", "Breaking Bad"))
        assertEquals(ParsedName("Dark", season = 1, episode = 3), p("Episode 3.mp4", "Season 01", "Dark"))
        assertEquals(ParsedName("Friends", season = 1, episode = 5), p("Friends 1x05 The One.avi"))
        assertEquals(ParsedName("Doctor Who", 2005, 3, 1), p("Doctor.Who.2005.S03E01.mkv"))
        assertEquals(ParsedName("Show Name", season = 1, episode = 1, episodeEnd = 2), p("Show.Name.S01E01E02.mkv"))
        assertEquals(ParsedName("The Office", season = 5, episode = 14), p("The Office S05 E14.mkv"))
        assertEquals(ParsedName("Sherlock", season = 0, episode = 1), p("Specials 01.mkv", "Specials", "Sherlock"))
        assertTrue(p("Breaking.Bad.S01E02.mkv").isEpisode)
    }

    @Test
    fun anime() {
        assertEquals(ParsedName("Frieren", episode = 5, absolute = true), p("[SubsPlease] Frieren - 05 (1080p) [ABCD1234].mkv", "Frieren"))
        assertEquals(ParsedName("Frieren", season = 1, episode = 12), p("[Group] Frieren - 12 [1080p].mkv", "Season 1", "Frieren"))
    }

    @Test
    fun keysGroupTheSameShow() {
        assertEquals(NameParser.key("The Office"), NameParser.key("the.office"))
        assertEquals(NameParser.key("Grey's Anatomy"), NameParser.key("Greys Anatomy"))
    }
}
