package com.troy.orcaplayer.downloads

import com.troy.orcaplayer.downloads.logic.DownloadError
import com.troy.orcaplayer.downloads.logic.DownloadKind
import com.troy.orcaplayer.downloads.logic.LinkErrors
import com.troy.orcaplayer.downloads.logic.Ranges
import com.troy.orcaplayer.downloads.logic.Retry
import com.troy.orcaplayer.downloads.logic.Segment
import com.troy.orcaplayer.downloads.logic.SpeedLimiter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.concurrent.thread

/** A download that can't go on right now. [retryable] failures are tried again after a wait. */
class FetchFailure(
    val error: DownloadError,
    val code: Int = 0,
    val retryable: Boolean = false,
    message: String? = null,
) : Exception(message ?: "$error $code")

/** The link turned out to be an HLS or DASH stream, so it is downloaded with Media3 instead. */
class IsStream(val kind: DownloadKind) : Exception()

/** Paused, cancelled, or the system stopped the work. */
class Stopped : Exception()

/** What the server said about a file. */
data class Probe(
    val code: Int,
    val total: Long,
    val acceptsRanges: Boolean,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?,
    val disposition: String?,
)

/**
 * Plain file downloads over HTTP (16.3): resumes with range requests, restarts from zero when the
 * server can't, and splits large files over up to eight connections.
 */
class FileFetcher(private val limiter: SpeedLimiter, private val userAgent: String) {
    private val maxRedirects = 8

    /** Opens [url], following redirects across http and https and keeping the headers. */
    fun connect(url: String, headers: Map<String, String>, range: String? = null, ifRange: String? = null, method: String = "GET"): HttpURLConnection {
        var current = url
        repeat(maxRedirects) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                requestMethod = method
                setRequestProperty("Accept-Encoding", "identity")
                if (headers.keys.none { it.equals("User-Agent", true) }) setRequestProperty("User-Agent", userAgent)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                range?.let { setRequestProperty("Range", it) }
                ifRange?.let { setRequestProperty("If-Range", it) }
            }
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect()
                throw FetchFailure(DownloadError.NETWORK, retryable = true, message = e.message)
            }
            if (code in 300..399 && code != 304) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) throw FetchFailure(DownloadError.HTTP, code)
                current = URL(URL(current), location).toString()
            } else {
                return c
            }
        }
        throw FetchFailure(DownloadError.HTTP, 310, message = "too many redirects")
    }

    private fun probeOf(c: HttpURLConnection, offset: Long): Probe {
        val code = c.responseCode
        val range = Ranges.parseContentRange(c.getHeaderField("Content-Range"))
        val length = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
        val total = when {
            range?.total != null -> range.total
            code == 206 && length >= 0 -> offset + length
            code == 200 -> length
            else -> -1
        }
        return Probe(
            code = code,
            total = total,
            acceptsRanges = code == 206 || Ranges.acceptsRanges(c.getHeaderField("Accept-Ranges")),
            contentType = c.contentType,
            etag = c.getHeaderField("ETag"),
            lastModified = c.getHeaderField("Last-Modified"),
            disposition = c.getHeaderField("Content-Disposition"),
        )
    }

    /** Asks for the first byte only, to learn the size and type without downloading. */
    fun probe(url: String, headers: Map<String, String>): Probe {
        val c = connect(url, headers, range = "bytes=0-0")
        try {
            val p = probeOf(c, 0)
            check(p.code)
            return p
        } finally {
            c.disconnect()
        }
    }

    private fun check(code: Int) {
        when {
            code == 200 || code == 206 -> Unit
            LinkErrors.expired(code) -> throw FetchFailure(DownloadError.EXPIRED, code)
            Retry.retryableHttp(code) -> throw FetchFailure(DownloadError.HTTP, code, retryable = true)
            else -> throw FetchFailure(DownloadError.HTTP, code)
        }
    }

    private fun streamKindOf(contentType: String?): DownloadKind? {
        val t = contentType?.lowercase() ?: return null
        return when {
            "mpegurl" in t -> DownloadKind.HLS
            "dash+xml" in t -> DownloadKind.DASH
            else -> null
        }
    }

    /** Progress as the fetcher reports it. */
    data class Progress(val done: Long, val total: Long, val segments: List<Segment>, val probe: Probe?)

    /**
     * Downloads [url] into [out] from byte [offset]. Calls [onProbe] once with the first response
     * and [onProgress] at most four times a second. Returns the final size.
     */
    fun run(
        url: String,
        headers: Map<String, String>,
        out: FileChannel,
        offset: Long,
        validator: String?,
        segments: List<Segment>,
        multiConnection: Boolean,
        stop: () -> Boolean,
        onProbe: (Probe, restarted: Boolean) -> Unit,
        onProgress: (Progress) -> Unit,
    ): Long {
        if (segments.isNotEmpty()) return runSegments(url, headers, out, segments, stop, onProgress)
        val c = connect(url, headers, range = if (offset > 0) "bytes=$offset-" else null, ifRange = if (offset > 0) validator else null)
        try {
            val code = c.responseCode
            if (code == 416 && offset > 0) {
                // Already complete.
                val total = Ranges.parseContentRange(c.getHeaderField("Content-Range"))?.total ?: offset
                if (total == offset) return offset
            }
            check(code)
            val probe = probeOf(c, offset)
            streamKindOf(probe.contentType)?.let { throw IsStream(it) }
            if (probe.contentType?.startsWith("text/html", true) == true && probe.total in 0..(512 * 1024)) {
                throw FetchFailure(DownloadError.HTTP, code, message = "not a video")
            }
            var start = offset
            var restarted = false
            if (offset > 0) {
                val range = Ranges.parseContentRange(c.getHeaderField("Content-Range"))
                if (code == 200 || range == null || range.start != offset) {
                    // The server ignored the range: start again from zero.
                    start = 0
                    restarted = true
                    out.truncate(0)
                }
            }
            onProbe(probe, restarted)
            // Big files on a server with ranges can use several connections from the start.
            if (multiConnection && start == 0L && probe.acceptsRanges && probe.total > 2 * Ranges.MIN_PART) {
                c.disconnect()
                val parts = Ranges.split(probe.total, Ranges.connectionsFor(probe.total))
                return runSegments(url, headers, out, parts, stop, onProgress)
            }
            val total = probe.total
            var done = start
            var lastReport = 0L
            val buf = ByteArray(256 * 1024)
            c.inputStream.use { input ->
                while (true) {
                    if (stop()) throw Stopped()
                    val n = try {
                        input.read(buf)
                    } catch (e: IOException) {
                        throw FetchFailure(DownloadError.NETWORK, retryable = true, message = e.message)
                    }
                    if (n < 0) break
                    limiter.acquire(n)
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    var pos = done
                    while (bb.hasRemaining()) pos += writeAt(out, bb, pos)
                    done += n
                    val now = System.nanoTime()
                    if (now - lastReport > 250_000_000L) {
                        lastReport = now
                        onProgress(Progress(done, total, emptyList(), probe))
                    }
                }
            }
            onProgress(Progress(done, total, emptyList(), probe))
            if (total > 0 && done < total) throw FetchFailure(DownloadError.NETWORK, retryable = true, message = "connection closed early")
            return done
        } finally {
            c.disconnect()
        }
    }

    private fun writeAt(out: FileChannel, bb: ByteBuffer, pos: Long): Int = try {
        out.write(bb, pos)
    } catch (e: IOException) {
        val msg = e.message.orEmpty()
        if ("ENOSPC" in msg || "No space" in msg) throw FetchFailure(DownloadError.NO_SPACE, message = msg)
        throw FetchFailure(DownloadError.STORAGE, message = msg)
    }

    /** Several connections, each filling its own byte range of the file. */
    private fun runSegments(
        url: String,
        headers: Map<String, String>,
        out: FileChannel,
        segments: List<Segment>,
        stop: () -> Boolean,
        onProgress: (Progress) -> Unit,
    ): Long {
        val total = segments.last().end + 1
        val done = AtomicLongArray(segments.size).also { a -> segments.forEachIndexed { i, s -> a.set(i, s.done.coerceAtMost(s.length)) } }
        val abort = AtomicBoolean(false)
        var failure: Throwable? = null
        val lock = Any()
        fun snapshot() = segments.mapIndexed { i, s -> s.copy(done = done.get(i)) }
        val workers = segments.mapIndexed { i, seg ->
            thread(name = "orca-dl-part-$i") {
                try {
                    if (done.get(i) >= seg.length) return@thread
                    val from = seg.start + done.get(i)
                    val c = connect(url, headers, range = "bytes=$from-${seg.end}")
                    try {
                        val code = c.responseCode
                        check(code)
                        val range = Ranges.parseContentRange(c.getHeaderField("Content-Range"))
                        if (code != 206 || range == null || range.start != from) throw FetchFailure(DownloadError.HTTP, code, retryable = true, message = "range refused")
                        val buf = ByteArray(256 * 1024)
                        c.inputStream.use { input ->
                            while (done.get(i) < seg.length) {
                                if (abort.get() || stop()) throw Stopped()
                                val want = minOf(buf.size.toLong(), seg.length - done.get(i)).toInt()
                                val n = try {
                                    input.read(buf, 0, want)
                                } catch (e: IOException) {
                                    throw FetchFailure(DownloadError.NETWORK, retryable = true, message = e.message)
                                }
                                if (n < 0) throw FetchFailure(DownloadError.NETWORK, retryable = true, message = "connection closed early")
                                limiter.acquire(n)
                                val bb = ByteBuffer.wrap(buf, 0, n)
                                var pos = seg.start + done.get(i)
                                while (bb.hasRemaining()) pos += writeAt(out, bb, pos)
                                done.addAndGet(i, n.toLong())
                            }
                        }
                    } finally {
                        c.disconnect()
                    }
                } catch (t: Throwable) {
                    synchronized(lock) { if (failure == null || failure is Stopped) failure = t }
                    abort.set(true)
                }
            }
        }
        while (workers.any { it.isAlive }) {
            workers.forEach { it.join(250) }
            val s = snapshot()
            onProgress(Progress(Ranges.doneOf(s), total, s, null))
            if (stop()) abort.set(true)
        }
        val s = snapshot()
        onProgress(Progress(Ranges.doneOf(s), total, s, null))
        failure?.let { throw it }
        return total
    }

    /** Small files such as subtitles, read into memory. */
    fun fetchBytes(url: String, headers: Map<String, String>, limit: Int = 8 * 1024 * 1024): ByteArray {
        val c = connect(url, headers)
        try {
            check(c.responseCode)
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw FetchFailure(DownloadError.HTTP, message = "too large")
                }
                out.toByteArray()
            }
            // Gzip-compressed subtitles (some addons serve them this way).
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                return java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            }
            return bytes
        } finally {
            c.disconnect()
        }
    }
}
