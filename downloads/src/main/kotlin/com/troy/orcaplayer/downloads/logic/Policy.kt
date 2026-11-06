package com.troy.orcaplayer.downloads.logic

import java.util.Calendar
import java.util.TimeZone

/** Growing waits between retries (16.3): 5 s, 10 s, 20 s ... up to 10 minutes. */
object Retry {
    const val BASE_MS = 5_000L
    const val MAX_MS = 10 * 60_000L

    fun delayMs(attempt: Int): Long {
        if (attempt <= 1) return BASE_MS
        val shift = (attempt - 1).coerceAtMost(20)
        return (BASE_MS shl shift).coerceAtMost(MAX_MS)
    }

    /** Server errors and rate limits are worth retrying; other 4xx answers are not. */
    fun retryableHttp(code: Int): Boolean = code == 408 || code == 429 || code in 500..599
}

/** Answers that mean an addon or extension link expired, so a fresh one is needed (16.3). */
object LinkErrors {
    fun expired(code: Int): Boolean = code == 401 || code == 403 || code == 404 || code == 410
}

data class ContentRange(val start: Long, val end: Long, val total: Long?)

object Ranges {
    /** Parses "bytes 100-199/1000" (total may be "*"). */
    fun parseContentRange(header: String?): ContentRange? {
        val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).find(header ?: return null) ?: return null
        return ContentRange(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLongOrNull())
    }

    /** True when the server advertised byte ranges. */
    fun acceptsRanges(header: String?): Boolean = header?.trim()?.equals("bytes", ignoreCase = true) == true

    const val MIN_PART = 4L * 1024 * 1024

    /** Splits [total] bytes into up to [connections] parts of at least [MIN_PART] bytes. */
    fun split(total: Long, connections: Int, minPart: Long = MIN_PART): List<Segment> {
        if (total <= 0) return emptyList()
        val n = connections.coerceIn(1, 4).coerceAtMost(((total + minPart - 1) / minPart).toInt().coerceAtLeast(1))
        val size = total / n
        return (0 until n).map { i ->
            val start = i * size
            val end = if (i == n - 1) total - 1 else start + size - 1
            Segment(start, end)
        }
    }

    fun doneOf(segments: List<Segment>): Long = segments.sumOf { it.done.coerceAtMost(it.length) }
}

/**
 * One speed limit shared by every running download (16.2): a token bucket refilled
 * [bytesPerSecond] times a second, with a burst of a quarter second.
 */
class SpeedLimiter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { ms -> Thread.sleep(ms) },
) {
    @Volatile var bytesPerSecond: Long = 0
        set(value) {
            field = value.coerceAtLeast(0)
            synchronized(this) { tokens = 0.0; last = nanoTime() }
        }

    private var tokens = 0.0
    private var last = nanoTime()

    /** Blocks until [bytes] may be sent. Returns at once when there is no limit. */
    fun acquire(bytes: Int) {
        var need = bytes.toDouble()
        while (true) {
            val limit = bytesPerSecond
            if (limit <= 0) return
            val waitMs: Long
            synchronized(this) {
                val now = nanoTime()
                tokens = (tokens + (now - last) / 1e9 * limit).coerceAtMost(limit / 4.0 + need)
                last = now
                if (tokens >= need) {
                    tokens -= need
                    return
                }
                // Take what there is and wait for the rest.
                need -= tokens
                tokens = 0.0
                waitMs = ((need / limit) * 1000).toLong().coerceIn(1, 1000)
            }
            sleep(waitMs)
        }
    }
}

/** The device state the queue depends on. */
data class Conditions(
    val connected: Boolean,
    val unmetered: Boolean,
    val charging: Boolean,
    /** Local time, minutes after midnight. */
    val minuteOfDay: Int,
    val freeBytes: Long,
)

object Gate {
    /** Why [item] can't run now, or null when it can. */
    fun waitReason(item: DownloadItem, config: DownloadConfig, c: Conditions, scheduleOpen: Boolean, now: Long): WaitReason? = when {
        !c.connected -> WaitReason.NETWORK
        config.wifiOnly && !c.unmetered -> WaitReason.WIFI
        item.nextAttemptAt > now -> WaitReason.RETRY
        !item.startNow && config.chargingOnly && !c.charging -> WaitReason.CHARGING
        !item.startNow && !scheduleOpen -> WaitReason.SCHEDULE
        item.totalBytes > 0 && c.freeBytes - (item.totalBytes - item.bytesDone) < config.reserveBytes -> WaitReason.STORAGE
        c.freeBytes < config.reserveBytes -> WaitReason.STORAGE
        else -> null
    }
}

/**
 * The optional start time (16.2). Once the start time has been reached the queue keeps going
 * until it is empty, so the "open" state is remembered by the caller with [openedAt].
 */
object Schedule {
    /** Milliseconds from [now] until the next [startMinute] in [zone]. */
    fun delayUntil(startMinute: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, startMinute / 60)
            set(Calendar.MINUTE, startMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis - now
    }

    /**
     * True when scheduled downloads may run: there's no start time, or the start time passed
     * after the queue was last waiting ([waitingSince]), so it runs until the queue is done.
     */
    fun isOpen(startMinute: Int?, waitingSince: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        if (startMinute == null) return true
        if (waitingSince <= 0) return false
        return now - waitingSince >= delayUntil(startMinute, waitingSince, zone)
    }

    fun minuteOfDay(now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }
}

/** The queue's running order: lower [DownloadItem.order] first, then the oldest. */
object Queue {
    fun runnable(items: List<DownloadItem>): List<DownloadItem> =
        items.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.WAITING }
            .sortedWith(compareBy({ it.order }, { it.createdAt }))

    /** Moves [id] to [toIndex] among the active items and returns new order values for all of them. */
    fun reorder(items: List<DownloadItem>, id: String, toIndex: Int): Map<String, Long> {
        val active = items.filter { it.active || it.status == DownloadStatus.PAUSED }.sortedWith(compareBy({ it.order }, { it.createdAt })).toMutableList()
        val from = active.indexOfFirst { it.id == id }
        if (from < 0) return emptyMap()
        val moved = active.removeAt(from)
        active.add(toIndex.coerceIn(0, active.size), moved)
        return active.mapIndexed { i, it -> it.id to i.toLong() }.toMap()
    }

    fun nextOrder(items: List<DownloadItem>): Long = (items.maxOfOrNull { it.order } ?: -1) + 1
}

/** An episode in watching order. */
data class EpisodeRef(val id: String, val season: Int?, val number: Int?)

/** Smart downloads (16.6): which episodes to fetch next to keep [keep] ready. */
object SmartPlan {
    /**
     * [ordered] is every episode in watching order. [ready] are episodes downloaded or
     * downloading and not yet watched. [after] is the last episode watched or downloaded.
     */
    fun next(ordered: List<EpisodeRef>, ready: Set<String>, after: String?, keep: Int, watched: Set<String> = emptySet()): List<EpisodeRef> {
        val missing = keep - ready.size
        if (missing <= 0) return emptyList()
        val startIndex = after?.let { id -> ordered.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }?.plus(1) ?: 0
        // Never go back before the furthest episode already ready.
        val furthestReady = ordered.indexOfLast { it.id in ready }
        val from = maxOf(startIndex, furthestReady + 1)
        return ordered.drop(from).filter { it.id !in ready && it.id !in watched }.take(missing)
    }

    /** Episodes sorted by season (specials last) and number. */
    fun order(episodes: List<EpisodeRef>): List<EpisodeRef> =
        episodes.sortedWith(compareBy({ if ((it.season ?: 0) == 0) Int.MAX_VALUE else it.season }, { it.number ?: Int.MAX_VALUE }))
}

object Format {
    /** "1.5 GB", "320 MB", "12 KB". Plain digits so both languages read it. */
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        val kb = 1024.0
        return when {
            n < kb -> "$n B"
            n < kb * kb -> "%.0f KB".format(java.util.Locale.ROOT, n / kb)
            n < kb * kb * kb -> "%.1f MB".format(java.util.Locale.ROOT, n / kb / kb)
            else -> "%.2f GB".format(java.util.Locale.ROOT, n / kb / kb / kb)
        }
    }

    /** Seconds left at [speed] bytes per second, or null when it can't be known. */
    fun etaSeconds(done: Long, total: Long, speed: Long): Long? =
        if (total <= 0 || speed <= 0 || done >= total) null else (total - done) / speed
}
