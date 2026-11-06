package com.troy.orcaplayer.downloads

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.troy.orcaplayer.downloads.logic.DownloadError
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadKind
import com.troy.orcaplayer.downloads.logic.LinkErrors
import com.troy.orcaplayer.downloads.logic.Retry
import com.troy.orcaplayer.downloads.logic.SpeedLimiter
import com.troy.orcaplayer.downloads.logic.TrackChoice
import com.troy.orcaplayer.player.engine.OfflineCache
import com.troy.orcaplayer.player.engine.parseStreamKey
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

enum class TrackType { VIDEO, AUDIO, TEXT }

/** One track of an HLS or DASH stream that can be picked before downloading (16.4). */
data class TrackOption(
    val key: String,
    val type: TrackType,
    val height: Int? = null,
    val bitrate: Int = 0,
    val language: String? = null,
    val label: String? = null,
    val codecs: String? = null,
)

data class StreamTracks(
    val kind: DownloadKind,
    val options: List<TrackOption>,
    /** Protected streams can't be saved (3.7). */
    val drmProtected: Boolean,
) {
    val videos: List<TrackOption> get() = options.filter { it.type == TrackType.VIDEO }.sortedByDescending { it.height ?: 0 }
    val audios: List<TrackOption> get() = options.filter { it.type == TrackType.AUDIO }
    val texts: List<TrackOption> get() = options.filter { it.type == TrackType.TEXT }

    /**
     * The default choice: the best video at or under [maxHeight], every audio track in
     * [audioLanguages] (or all), and every subtitle in [textLanguages] (or all).
     */
    fun defaultChoice(maxHeight: Int?, audioLanguages: Set<String> = emptySet(), textLanguages: Set<String> = emptySet()): TrackChoice {
        val video = videos.firstOrNull { maxHeight == null || (it.height ?: 0) <= maxHeight } ?: videos.lastOrNull()
        val audio = audios.filter { audioLanguages.isEmpty() || it.language?.substringBefore('-')?.lowercase() in audioLanguages }.ifEmpty { audios }
        val text = texts.filter { textLanguages.isEmpty() || it.language?.substringBefore('-')?.lowercase() in textLanguages }
        return choice(video, audio, text)
    }

    fun choice(video: TrackOption?, audio: List<TrackOption>, text: List<TrackOption>): TrackChoice {
        val keys = listOfNotNull(video?.key) + audio.map { it.key } + text.map { it.key }
        val label = listOfNotNull(
            video?.height?.let { "${it}p" },
            audio.mapNotNull { it.language ?: it.label }.distinct().joinToString(", ").takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        return TrackChoice(keys = expand(keys), label = label.ifEmpty { null }, height = video?.height)
    }

    /** DASH keys picked on the first period are repeated for every other period. */
    private var periods: Int = 1
    private fun expand(keys: List<String>): List<String> {
        if (kind != DownloadKind.DASH || periods <= 1) return keys
        return (0 until periods).flatMap { p -> keys.map { k -> k.substringAfter('.').let { rest -> "$p.$rest" } } }
    }

    internal fun withPeriods(n: Int) = apply { periods = n }
}

/** A speed-limited view of another data source, so streams share the download speed limit. */
@OptIn(UnstableApi::class)
class ThrottledDataSource(private val upstream: DataSource, private val limiter: SpeedLimiter) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) limiter.acquire(n)
        return n
    }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory, private val limiter: SpeedLimiter) : DataSource.Factory {
        override fun createDataSource(): DataSource = ThrottledDataSource(upstream.createDataSource(), limiter)
    }
}

/** HLS and DASH downloads with Media3's downloader, into the offline store the player reads (16.4). */
@OptIn(UnstableApi::class)
object StreamFetcher {
    fun kindOf(url: String, mime: String? = null): DownloadKind? {
        val m = mime?.lowercase()
        if (m != null) {
            if ("mpegurl" in m) return DownloadKind.HLS
            if ("dash+xml" in m) return DownloadKind.DASH
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") && "/hls" in path -> DownloadKind.HLS
            path.endsWith(".mpd") -> DownloadKind.DASH
            else -> null
        }
    }

    fun mimeOf(kind: DownloadKind): String? = when (kind) {
        DownloadKind.HLS -> MimeTypes.APPLICATION_M3U8
        DownloadKind.DASH -> MimeTypes.APPLICATION_MPD
        DownloadKind.FILE -> null
    }

    private fun http(headers: Map<String, String>, userAgent: String): DefaultHttpDataSource.Factory {
        val props = headers.filterKeys { !it.equals("User-Agent", true) }
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: userAgent
        return DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(props)
    }

    /** Reads the stream's manifest and lists its tracks. Blocking. */
    fun tracks(url: String, kind: DownloadKind, headers: Map<String, String>, userAgent: String): StreamTracks {
        val source = http(headers, userAgent).createDataSource()
        val uri = Uri.parse(url)
        return try {
            when (kind) {
                DownloadKind.HLS -> hlsTracks(ParsingLoadable.load(source, HlsPlaylistParser(), uri, C.DATA_TYPE_MANIFEST))
                DownloadKind.DASH -> dashTracks(ParsingLoadable.load(source, DashManifestParser(), uri, C.DATA_TYPE_MANIFEST))
                DownloadKind.FILE -> StreamTracks(kind, emptyList(), false)
            }
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            throw failureOf(e)
        } catch (e: IOException) {
            throw FetchFailure(DownloadError.NETWORK, retryable = true, message = e.message)
        }
    }

    private fun hlsTracks(playlist: HlsPlaylist): StreamTracks {
        if (playlist !is HlsMultivariantPlaylist) return StreamTracks(DownloadKind.HLS, emptyList(), false)
        val options = mutableListOf<TrackOption>()
        playlist.variants.forEachIndexed { i, v ->
            val f = v.format
            options += TrackOption("0.${HlsMultivariantPlaylist.GROUP_INDEX_VARIANT}.$i", TrackType.VIDEO, f.height.takeIf { it > 0 }, f.bitrate.coerceAtLeast(0), codecs = f.codecs)
        }
        playlist.audios.forEachIndexed { i, r ->
            options += TrackOption("0.${HlsMultivariantPlaylist.GROUP_INDEX_AUDIO}.$i", TrackType.AUDIO, language = r.format.language, label = r.name ?: r.format.label)
        }
        playlist.subtitles.forEachIndexed { i, r ->
            options += TrackOption("0.${HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE}.$i", TrackType.TEXT, language = r.format.language, label = r.name ?: r.format.label)
        }
        // Keep one entry per height: the highest bitrate.
        val videos = options.filter { it.type == TrackType.VIDEO }.groupBy { it.height }.map { (_, list) -> list.maxBy { it.bitrate } }
        return StreamTracks(DownloadKind.HLS, videos + options.filter { it.type != TrackType.VIDEO }, playlist.sessionKeyDrmInitData.isNotEmpty())
    }

    private fun dashTracks(manifest: DashManifest): StreamTracks {
        if (manifest.periodCount == 0) return StreamTracks(DownloadKind.DASH, emptyList(), false)
        val period = manifest.getPeriod(0)
        val options = mutableListOf<TrackOption>()
        var drm = false
        period.adaptationSets.forEachIndexed { a, set ->
            val type = when (set.type) {
                C.TRACK_TYPE_VIDEO -> TrackType.VIDEO
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.TEXT
                else -> null
            } ?: return@forEachIndexed
            set.representations.forEachIndexed { r, rep ->
                val f = rep.format
                if (f.drmInitData != null) drm = true
                // Audio and text: one entry per adaptation set is enough.
                if (type != TrackType.VIDEO && r > 0) return@forEachIndexed
                options += TrackOption("0.$a.$r", type, f.height.takeIf { it > 0 }, f.bitrate.coerceAtLeast(0), f.language, f.label, f.codecs)
            }
        }
        val videos = options.filter { it.type == TrackType.VIDEO }.groupBy { it.height }.map { (_, list) -> list.maxBy { it.bitrate } }
        return StreamTracks(DownloadKind.DASH, videos + options.filter { it.type != TrackType.VIDEO }, drm).withPeriods(manifest.periodCount)
    }

    private fun failureOf(e: HttpDataSource.InvalidResponseCodeException): FetchFailure = when {
        LinkErrors.expired(e.responseCode) -> FetchFailure(DownloadError.EXPIRED, e.responseCode)
        Retry.retryableHttp(e.responseCode) -> FetchFailure(DownloadError.HTTP, e.responseCode, retryable = true)
        else -> FetchFailure(DownloadError.HTTP, e.responseCode)
    }

    private fun request(item: DownloadItem): DownloadRequest =
        DownloadRequest.Builder(item.id, Uri.parse(item.url))
            .setMimeType(item.mimeType ?: mimeOf(item.kind))
            .setStreamKeys(item.tracks?.keys.orEmpty().mapNotNull(::parseStreamKey))
            .build()

    private fun downloader(context: Context, item: DownloadItem, limiter: SpeedLimiter?, userAgent: String): Downloader {
        val upstream: DataSource.Factory = http(item.headers, userAgent).let { h -> limiter?.let { ThrottledDataSource.Factory(h, it) } ?: h }
        val cacheFactory = CacheDataSource.Factory()
            .setCache(OfflineCache.get(context))
            .setUpstreamDataSourceFactory(upstream)
        return DefaultDownloaderFactory(cacheFactory, Executor { it.run() }).createDownloader(request(item))
    }

    /** Downloads every chosen segment, skipping what is already stored. Blocking. */
    fun run(context: Context, item: DownloadItem, limiter: SpeedLimiter, userAgent: String, stop: () -> Boolean, onProgress: (percent: Float, bytes: Long) -> Unit) {
        val d = downloader(context, item, limiter, userAgent)
        val finished = AtomicBoolean(false)
        val watcher = thread(name = "orca-dl-watch") {
            while (!finished.get()) {
                if (stop()) {
                    d.cancel()
                    return@thread
                }
                try {
                    Thread.sleep(300)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
        try {
            d.download { _, bytes, percent -> onProgress(percent, bytes) }
        } catch (e: InterruptedException) {
            throw Stopped()
        } catch (e: Exception) {
            if (stop()) throw Stopped()
            val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is HttpDataSource.InvalidResponseCodeException } as? HttpDataSource.InvalidResponseCodeException
            if (cause != null) throw failureOf(cause)
            val msg = e.message.orEmpty()
            if ("ENOSPC" in msg || "No space" in msg) throw FetchFailure(DownloadError.NO_SPACE, message = msg)
            throw FetchFailure(DownloadError.NETWORK, retryable = true, message = msg)
        } finally {
            finished.set(true)
            watcher.interrupt()
        }
        if (stop()) throw Stopped()
    }

    /** Removes a stream download's stored segments. */
    fun remove(context: Context, item: DownloadItem, userAgent: String) {
        runCatching { downloader(context, item, null, userAgent).remove() }
    }
}
