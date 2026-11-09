package com.troy.orcaplayer.downloads

import com.troy.orcaplayer.downloads.logic.Conditions
import com.troy.orcaplayer.downloads.logic.DownloadConfig
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadKind
import com.troy.orcaplayer.downloads.logic.DownloadMeta
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import com.troy.orcaplayer.downloads.logic.DownloadStore
import com.troy.orcaplayer.downloads.logic.EpisodeRef
import com.troy.orcaplayer.downloads.logic.Gate
import com.troy.orcaplayer.downloads.logic.Naming
import com.troy.orcaplayer.downloads.logic.Ranges
import com.troy.orcaplayer.downloads.logic.Retry
import com.troy.orcaplayer.downloads.logic.Schedule
import com.troy.orcaplayer.downloads.logic.SmartPlan
import com.troy.orcaplayer.downloads.logic.SpeedLimiter
import com.troy.orcaplayer.downloads.logic.WaitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class DownloadLogicTest {
    private val movie = DownloadMeta(title = "Dune: Part Two", year = 2024)
    private val episode = DownloadMeta(title = "المسلسل", season = 1, episode = 2, episodeId = "tt1:1:2", episodeTitle = "The Return")

    @Test fun movieName() {
        assertEquals("Dune Part Two (2024).mkv", Naming.fileName(DownloadConfig(), movie, "mkv"))
    }

    @Test fun movieWithoutYear() {
        assertEquals("Dune Part Two.mp4", Naming.fileName(DownloadConfig(), movie.copy(year = null), "mp4"))
    }

    @Test fun episodeNameKeepsArabic() {
        assertEquals("المسلسل S01E02 The Return.mp4", Naming.fileName(DownloadConfig(), episode, "mp4"))
    }

    @Test fun episodeWithoutSeason() {
        assertEquals("المسلسل E02", Naming.render(Naming.EPISODE_TEMPLATE, episode.copy(season = null, episodeTitle = null)))
    }

    @Test fun invalidCharactersRemoved() {
        assertEquals("a b c d", Naming.sanitize("a/b:c?*\"d"))
        assertEquals(Naming.FALLBACK, Naming.sanitize("..."))
    }

    @Test fun subtitleNames() {
        val taken = mutableSetOf<String>()
        val a = Naming.subtitleName("Film (2024).mkv", "ar", "srt", taken).also { taken += it }
        val b = Naming.subtitleName("Film (2024).mkv", "ar", "srt", taken)
        assertEquals("Film (2024).ar.srt", a)
        assertEquals("Film (2024).ar.2.srt", b)
    }

    @Test fun extensions() {
        assertEquals("mkv", Naming.extensionFor("https://x/y/file.MKV?token=1", null, null))
        assertEquals("webm", Naming.extensionFor("https://x/play", "video/webm", null))
        assertEquals("avi", Naming.extensionFor("https://x/play", null, "attachment; filename=\"movie.avi\""))
        assertEquals("ملف.mp4", Naming.fileNameFromDisposition("attachment; filename*=UTF-8''%D9%85%D9%84%D9%81.mp4"))
    }

    @Test fun contentRange() {
        val r = Ranges.parseContentRange("bytes 100-199/1000")!!
        assertEquals(100L, r.start)
        assertEquals(199L, r.end)
        assertEquals(1000L, r.total)
        assertNull(Ranges.parseContentRange("bytes 0-9/*")!!.total)
    }

    @Test fun splitCoversEveryByte() {
        val parts = Ranges.split(100L * 1024 * 1024 + 7, 4)
        assertEquals(4, parts.size)
        assertEquals(0L, parts.first().start)
        assertEquals(100L * 1024 * 1024 + 6, parts.last().end)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
        assertEquals(1, Ranges.split(3L * 1024 * 1024, 4).size)
    }

    @Test fun bigFilesGetMoreConnections() {
        assertEquals(4, Ranges.connectionsFor(100L * 1024 * 1024))
        assertEquals(8, Ranges.connectionsFor(2L * 1024 * 1024 * 1024))
        assertEquals(8, Ranges.split(2L * 1024 * 1024 * 1024, 8).size)
        assertEquals(8, Ranges.split(2L * 1024 * 1024 * 1024, 20).size)
    }

    @Test fun retryGrows() {
        assertEquals(5_000L, Retry.delayMs(1))
        assertEquals(10_000L, Retry.delayMs(2))
        assertEquals(Retry.MAX_MS, Retry.delayMs(30))
        assertTrue(Retry.retryableHttp(503))
        assertFalse(Retry.retryableHttp(404))
    }

    private fun item(total: Long = -1, startNow: Boolean = false, next: Long = 0) =
        DownloadItem("1", DownloadKind.FILE, "https://x/a.mp4", meta = movie, fileName = "a.mp4", totalBytes = total, startNow = startNow, nextAttemptAt = next)

    private val good = Conditions(connected = true, unmetered = true, charging = true, minuteOfDay = 0, freeBytes = 10L shl 30)

    @Test fun gate() {
        val config = DownloadConfig()
        assertNull(Gate.waitReason(item(), config, good, true, 0))
        assertEquals(WaitReason.WIFI, Gate.waitReason(item(), config, good.copy(unmetered = false), true, 0))
        assertNull(Gate.waitReason(item(), config.copy(wifiOnly = false), good.copy(unmetered = false), true, 0))
        assertEquals(WaitReason.NETWORK, Gate.waitReason(item(), config, good.copy(connected = false), true, 0))
        assertEquals(WaitReason.CHARGING, Gate.waitReason(item(), config.copy(chargingOnly = true), good.copy(charging = false), true, 0))
        assertNull(Gate.waitReason(item(startNow = true), config.copy(chargingOnly = true), good.copy(charging = false), false, 0))
        assertEquals(WaitReason.SCHEDULE, Gate.waitReason(item(), config, good, false, 0))
        assertEquals(WaitReason.RETRY, Gate.waitReason(item(next = 100), config, good, true, 50))
        assertEquals(WaitReason.STORAGE, Gate.waitReason(item(total = 10L shl 30), config, good, true, 0))
    }

    @Test fun schedule() {
        val utc = TimeZone.getTimeZone("UTC")
        val tenPm = 22 * 3_600_000L
        assertEquals(3_600_000L, Schedule.delayUntil(23 * 60, tenPm, utc))
        assertEquals(23 * 3_600_000L, Schedule.delayUntil(21 * 60, tenPm, utc))
        assertTrue(Schedule.isOpen(null, 0, tenPm, utc))
        assertFalse(Schedule.isOpen(23 * 60, tenPm, tenPm + 1000, utc))
        assertTrue(Schedule.isOpen(23 * 60, tenPm, tenPm + 3_600_000L, utc))
        // Still open the next morning: the queue runs until it is done.
        assertTrue(Schedule.isOpen(23 * 60, tenPm, tenPm + 10 * 3_600_000L, utc))
    }

    @Test fun smartPlan() {
        val eps = SmartPlan.order(
            listOf(EpisodeRef("s2e1", 2, 1), EpisodeRef("s1e2", 1, 2), EpisodeRef("s1e1", 1, 1), EpisodeRef("sp", 0, 1), EpisodeRef("s1e3", 1, 3)),
        )
        assertEquals(listOf("s1e1", "s1e2", "s1e3", "s2e1", "sp"), eps.map { it.id })
        assertEquals(listOf("s1e3", "s2e1"), SmartPlan.next(eps, ready = emptySet(), after = "s1e2", keep = 2).map { it.id })
        assertEquals(listOf("s2e1"), SmartPlan.next(eps, ready = setOf("s1e3"), after = "s1e2", keep = 2).map { it.id })
        assertTrue(SmartPlan.next(eps, ready = setOf("s1e3", "s2e1"), after = "s1e2", keep = 2).isEmpty())
    }

    @Test fun limiterWaitsAtLimit() {
        var now = 0L
        var slept = 0L
        val l = SpeedLimiter(nanoTime = { now }, sleep = { ms -> slept += ms; now += ms * 1_000_000 })
        l.bytesPerSecond = 1000
        repeat(10) { l.acquire(100) }
        // 1000 bytes at 1000 B/s take about a second.
        assertTrue("slept $slept", slept in 700..1100)
    }

    @Test fun storeSurvivesRestartAndResumes() {
        val f = File.createTempFile("orcadl", ".json").also { it.delete() }
        val s = DownloadStore(f)
        val a = s.add(item())
        s.update(a.id) { it.copy(status = DownloadStatus.RUNNING, bytesDone = 500) }
        val again = DownloadStore(f)
        val restored = again.get(a.id)!!
        assertEquals(DownloadStatus.QUEUED, restored.status)
        assertEquals(500L, restored.bytesDone)
        f.delete()
    }

    @Test fun reorder() {
        val f = File.createTempFile("orcadl", ".json").also { it.delete() }
        val s = DownloadStore(f)
        val a = s.add(item().copy(id = "a"))
        val b = s.add(item().copy(id = "b"))
        val c = s.add(item().copy(id = "c"))
        s.move(c.id, 0)
        assertEquals(listOf("c", "a", "b"), s.items.sortedBy { it.order }.map { it.id })
        assertEquals(setOf(a.id, b.id, c.id), s.items.map { it.id }.toSet())
        f.delete()
    }
}
