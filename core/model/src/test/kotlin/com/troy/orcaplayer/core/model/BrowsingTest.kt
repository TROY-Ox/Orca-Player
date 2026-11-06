package com.troy.orcaplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsingTest {
    @Test fun pinHashVerifies() {
        val salt = PinLock.newSalt()
        val h = PinLock.hash("2468", salt)
        assertTrue(PinLock.verify("2468", h, salt))
        assertFalse(PinLock.verify("2469", h, salt))
        assertNotEquals(h, PinLock.hash("2468", PinLock.newSalt()))
        assertTrue(PinLock.verify("anything", null, null))
    }

    @Test fun pinFormat() {
        assertTrue(PinLock.isValid("1234"))
        assertTrue(PinLock.isValid("12345678"))
        assertFalse(PinLock.isValid("123"))
        assertFalse(PinLock.isValid("12a4"))
    }

    @Test fun pinSlowsDown() {
        assertEquals(0, PinLock.lockoutMs(2))
        assertEquals(30_000, PinLock.lockoutMs(3))
        assertEquals(60_000, PinLock.lockoutMs(4))
        assertEquals(15 * 60_000L, PinLock.lockoutMs(50))
        assertEquals(10_000, PinLock.waitLeft(3, lastFailureAt = 1_000, now = 21_000))
        assertEquals(0, PinLock.waitLeft(3, lastFailureAt = 1_000, now = 99_000))
    }

    @Test fun kidsRules() {
        val open = KidsRules()
        assertTrue(open.allowsCatalog("a|b"))
        assertTrue(open.allowsFolder(null))
        val r = KidsRules(catalogs = setOf("a|b"), folders = setOf("f1"), titles = setOf("MOVIE/tt1"))
        assertTrue(r.allowsCatalog("a|b"))
        assertFalse(r.allowsCatalog("a|c"))
        assertFalse(r.allowsFolder(null))
        assertTrue(r.allowsFolder("f1"))
        assertTrue(r.allowsTitle("MOVIE", "tt1"))
        assertFalse(r.allowsTitle("SERIES", "tt1"))
    }

    @Test fun watchLimit() {
        assertNull(WatchLimit.remainingMs(0, 999_999))
        assertEquals(60_000L, WatchLimit.remainingMs(2, 60_000))
        assertTrue(WatchLimit.reached(1, 61_000))
        assertFalse(WatchLimit.reached(0, 61_000))
    }

    @Test fun chapterNames() {
        assertEquals(SkipKind.INTRO, SkipMarks.kindOfChapter("Opening"))
        assertEquals(SkipKind.INTRO, SkipMarks.kindOfChapter("Intro"))
        assertEquals(SkipKind.RECAP, SkipMarks.kindOfChapter("Previously on"))
        assertEquals(SkipKind.CREDITS, SkipMarks.kindOfChapter("End Credits"))
        assertNull(SkipMarks.kindOfChapter("Chapter 2"))
        assertNull(SkipMarks.kindOfChapter("Development"))
    }

    @Test fun rangesFromChapters() {
        val r = SkipMarks.fromChapters(listOf("Intro" to 0L, "Part A" to 90_000L, "Credits" to 1_300_000L), 1_400_000)
        assertEquals(listOf(SkipRange(SkipKind.INTRO, 0, 90_000), SkipRange(SkipKind.CREDITS, 1_300_000, 1_400_000)), r)
        assertEquals(SkipKind.INTRO, SkipMarks.active(30_000, r)?.kind)
        assertNull(SkipMarks.active(89_500, r))
        assertNull(SkipMarks.active(200_000, r))
        assertNull(SkipMarks.mark(SkipKind.INTRO, 10_000, 12_000))
    }

    @Test fun nextUp() {
        assertEquals(1_300_000, NextUp.showAtMs(1_400_000, 1_300_000, 10))
        assertEquals(1_385_000, NextUp.showAtMs(1_400_000, null, 10))
        assertEquals(1_380_000, NextUp.showAtMs(1_400_000, null, 20))
        assertEquals(7, NextUp.secondsLeft(13_000, 10_000, 10))
    }

    @Test fun continueWatching() {
        assertFalse(ContinueWatching.shouldShow(5_000, 100_000, 0.9))
        assertTrue(ContinueWatching.shouldShow(50_000, 100_000, 0.9))
        assertFalse(ContinueWatching.shouldShow(95_000, 100_000, 0.9))
        assertEquals(0.5f, ContinueWatching.progress(50, 100))
    }

    @Test
    fun nextEpisodeFollowsSeasonOrderAndSkipsSpecials() {
        val eps = listOf(
            Episode("s1e2", "t", 1, 2), Episode("s0e1", "t", 0, 1), Episode("s1e1", "t", 1, 1),
            Episode("s2e1", "t", 2, 1), Episode("s0e2", "t", 0, 2),
        )
        assertEquals("s1e2", NextUp.nextEpisode(eps, "s1e1")?.id)
        assertEquals("s2e1", NextUp.nextEpisode(eps, "s1e2")?.id)
        assertEquals(null, NextUp.nextEpisode(eps, "s2e1"))
        assertEquals("s0e2", NextUp.nextEpisode(eps, "s0e1")?.id)
        assertEquals(null, NextUp.nextEpisode(eps, "missing"))
    }
}
