package com.troy.orcaplayer.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.troy.orcaplayer.core.model.EpisodeInfo
import com.troy.orcaplayer.core.model.MediaKey
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.downloads.logic.DownloadConfig
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadKind
import com.troy.orcaplayer.downloads.logic.DownloadMeta
import com.troy.orcaplayer.downloads.logic.DownloadPlace
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import com.troy.orcaplayer.downloads.logic.DownloadStore
import com.troy.orcaplayer.downloads.logic.EpisodeRef
import com.troy.orcaplayer.downloads.logic.Naming
import com.troy.orcaplayer.downloads.logic.SmartDelete
import com.troy.orcaplayer.downloads.logic.SmartPlan
import com.troy.orcaplayer.downloads.logic.SmartSeries
import com.troy.orcaplayer.downloads.logic.SpeedLimiter
import com.troy.orcaplayer.downloads.logic.SubtitleFile
import com.troy.orcaplayer.downloads.logic.TrackChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A fresh link for the same title and episode, from the same source (16.3). */
data class FreshLink(val url: String, val headers: Map<String, String> = emptyMap())

/** An episode of a series, in watching order, with its resume key. */
data class EpisodeTarget(val ref: EpisodeRef, val mediaKeyId: String)

/** Everything needed to start a download. */
data class NewDownload(
    val url: String,
    val meta: DownloadMeta,
    val mediaKey: MediaKey?,
    val profileId: String,
    val headers: Map<String, String> = emptyMap(),
    /** Null works it out from the link (and later from the server's answer). */
    val kind: DownloadKind? = null,
    val subtitles: List<SubtitleFile> = emptyList(),
    val tracks: TrackChoice? = null,
    val place: DownloadPlace? = null,
    val folderUri: String? = null,
    val startNow: Boolean = false,
    val smart: Boolean = false,
    /** The file name the source gave, used for its extension. */
    val filename: String? = null,
    val sizeHint: Long? = null,
)

/**
 * What the downloader needs from sources: fresh links for expired ones and the next episodes for
 * smart downloads. The app sets it at start-up.
 */
interface DownloadBridge {
    suspend fun freshLink(item: DownloadItem): FreshLink?
    suspend fun episodes(series: SmartSeries): List<EpisodeTarget>
    suspend fun buildEpisode(series: SmartSeries, episode: EpisodeTarget): NewDownload?
}

/** The one download manager for every source (16). */
object Downloads {
    private const val WORK = "orca-downloads"
    private const val WORK_LATER = "orca-downloads-later"

    @Volatile var bridge: DownloadBridge? = null
    @Volatile var userAgent: String = "ORCA Player"

    internal val limiter = SpeedLimiter()
    internal val runLock = Mutex()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var store: DownloadStore? = null

    fun store(context: Context): DownloadStore = store ?: synchronized(this) {
        store ?: DownloadStore(File(context.applicationContext.filesDir, "downloads.json")).also {
            store = it
            limiter.bytesPerSecond = it.config.speedLimit
        }
    }

    /** Called at start-up: continues downloads that were queued or running when the app stopped. */
    fun init(context: Context) {
        val s = store(context)
        if (s.items.any { it.active }) kick(context)
    }

    /** New options from Settings. Changing the network rule restarts the queue under the new rule. */
    fun configure(context: Context, config: DownloadConfig) {
        val s = store(context)
        val old = s.config
        if (old == config) return
        s.setConfig(config)
        limiter.bytesPerSecond = config.speedLimit
        if (s.items.any { it.active }) {
            val restart = old.wifiOnly != config.wifiOnly || old.chargingOnly != config.chargingOnly || old.startMinute != config.startMinute
            kick(context, replace = restart)
        }
    }

    // Starting

    fun enqueue(context: Context, request: NewDownload): DownloadItem {
        val s = store(context)
        val config = s.config
        val kind = request.kind ?: StreamFetcher.kindOf(request.url) ?: DownloadKind.FILE
        val place = if (kind != DownloadKind.FILE) DownloadPlace.PRIVATE else request.place ?: config.place
        val folder = if (place == DownloadPlace.FOLDER) request.folderUri ?: config.folderUri else null
        val ext = if (kind == DownloadKind.FILE) Naming.extensionFor(request.filename ?: request.url, null, null) else null
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            kind = kind,
            url = request.url,
            headers = request.headers,
            meta = request.meta.copy(mediaKeyId = request.meta.mediaKeyId ?: request.mediaKey?.id),
            mediaKey = request.mediaKey,
            profileId = request.profileId,
            place = if (place == DownloadPlace.FOLDER && folder == null) DownloadPlace.PUBLIC else place,
            folderUri = folder,
            fileName = Naming.fileName(config, request.meta, ext),
            mimeType = if (kind == DownloadKind.FILE) ext?.let(Naming::mimeForExtension) else StreamFetcher.mimeOf(kind),
            totalBytes = request.sizeHint ?: -1,
            subtitles = if (config.saveSubtitles) request.subtitles.distinctBy { it.url } else emptyList(),
            tracks = request.tracks,
            startNow = request.startNow,
            smart = request.smart,
        )
        val added = s.add(item)
        kick(context)
        return added
    }

    /** Starts the queue, or makes sure it runs again after the current run ends. */
    fun kick(context: Context, replace: Boolean = false) {
        val config = store(context).config
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Runs the queue again later: after a retry wait, at the start time, or once charging. */
    internal fun scheduleLater(context: Context, delayMs: Long, charging: Boolean) {
        val config = store(context).config
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(charging)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_LATER, ExistingWorkPolicy.REPLACE, request)
    }

    // Controls (16.2)

    fun pause(context: Context, id: String) {
        store(context).update(id) { if (it.active) it.copy(status = DownloadStatus.PAUSED, waitReason = null) else it }
    }

    fun resume(context: Context, id: String) {
        store(context).update(id) {
            if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.WAITING) {
                it.copy(status = DownloadStatus.QUEUED, error = null, errorDetail = null, attempts = 0, nextAttemptAt = 0, waitReason = null)
            } else it
        }
        kick(context)
    }

    fun retry(context: Context, id: String) {
        store(context).update(id) { it.copy(refreshes = 0) }
        resume(context, id)
    }

    /** Ignores the start time and charging-only for this one. */
    fun startNow(context: Context, id: String) {
        store(context).update(id) { it.copy(startNow = true, status = if (it.status == DownloadStatus.COMPLETED) it.status else DownloadStatus.QUEUED, waitReason = null, nextAttemptAt = 0) }
        kick(context)
    }

    fun pauseAll(context: Context) {
        store(context).updateAll { if (it.active) it.copy(status = DownloadStatus.PAUSED, waitReason = null) else it }
    }

    fun resumeAll(context: Context) {
        store(context).updateAll {
            if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.WAITING) it.copy(status = DownloadStatus.QUEUED, waitReason = null, attempts = 0, nextAttemptAt = 0) else it
        }
        kick(context)
    }

    /** Stops every active download and removes it with its partial file. */
    fun cancelAll(context: Context) {
        store(context).items.filter { it.active || it.status == DownloadStatus.PAUSED }.forEach { delete(context, it.id) }
    }

    fun move(context: Context, id: String, toIndex: Int) = store(context).move(id, toIndex)

    /** Removes the download and its files (the partial file, or the finished file and its subtitles). */
    fun delete(context: Context, id: String) {
        val s = store(context)
        val item = s.get(id) ?: return
        s.remove(id)
        scope.launch {
            // A running download notices it is gone and stops first.
            if (item.status == DownloadStatus.RUNNING) kotlinx.coroutines.delay(1_500)
            cleanup(context, item)
        }
    }

    internal fun cleanup(context: Context, item: DownloadItem) {
        if (item.isStream) StreamFetcher.remove(context, item, userAgent)
        else Sinks.delete(context, item.outputUri, item.outputPath)
        item.subtitles.forEach { sub -> sub.savedUri?.let { Sinks.delete(context, it, null) } }
    }

    /** Deletes the file and downloads it again from the start. */
    fun downloadAgain(context: Context, id: String) {
        val s = store(context)
        val item = s.get(id) ?: return
        scope.launch {
            cleanup(context, item)
            s.update(id) {
                it.copy(
                    status = DownloadStatus.QUEUED, bytesDone = 0, percent = -1f, segments = emptyList(), outputUri = null, outputPath = null,
                    error = null, errorDetail = null, attempts = 0, refreshes = 0, nextAttemptAt = 0, completedAt = 0,
                    subtitles = it.subtitles.map { sub -> sub.copy(savedUri = null) }, etag = null, lastModified = null, rangeSupported = null,
                )
            }
            kick(context)
        }
    }

    /** Moves a finished file download into the app's private folder (16.7). */
    suspend fun moveToPrivate(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val s = store(context)
        val item = s.get(id) ?: return@withContext false
        if (item.status != DownloadStatus.COMPLETED || item.isStream || item.place == DownloadPlace.PRIVATE) return@withContext false
        runCatching {
            val moved = Sinks.moveToPrivate(context, item)
            s.update(id) { it.copy(place = DownloadPlace.PRIVATE, folderUri = null, outputUri = moved.uri, outputPath = moved.path) }
        }.isSuccess
    }

    // Smart downloads (16.6)

    fun setSmart(context: Context, series: SmartSeries, on: Boolean) {
        store(context).setSmart(series, on)
        if (on) scope.launch { topUp(context, series) }
    }

    /** Called when a video is watched to the end. */
    fun onWatched(context: Context, mediaKeyId: String) {
        val s = store(context)
        s.markWatched(mediaKeyId)
        val item = s.items.firstOrNull { it.meta.mediaKeyId == mediaKeyId && it.status == DownloadStatus.COMPLETED } ?: return
        val seriesKey = item.seriesKey ?: return
        val series = s.smartFor(seriesKey, item.profileId) ?: return
        when (s.config.smartDelete) {
            SmartDelete.AUTO -> delete(context, item.id)
            SmartDelete.ASK -> DownloadNotifications.askDelete(context, item)
            SmartDelete.OFF -> Unit
        }
        scope.launch { topUp(context, series) }
    }

    internal suspend fun topUp(context: Context, series: SmartSeries) {
        val b = bridge ?: return
        val s = store(context)
        val episodes = runCatching { b.episodes(series) }.getOrNull() ?: return
        val ordered = SmartPlan.order(episodes.map { it.ref })
        val byId = episodes.associateBy { it.ref.id }
        val mine = s.items.filter { it.seriesKey == series.seriesKey && it.profileId == series.profileId }
        val watched = s.state.value.watched
        val ready = mine.filter { it.status != DownloadStatus.FAILED && it.meta.mediaKeyId !in watched }.mapNotNull { it.meta.episodeId }.toSet()
        val watchedIds = episodes.filter { it.mediaKeyId in watched }.map { it.ref.id }.toSet()
        val known = watchedIds + mine.mapNotNull { it.meta.episodeId }
        val after = ordered.lastOrNull { it.id in known }?.id
        val next = SmartPlan.next(ordered, ready, after, s.config.smartKeep.coerceIn(1, 10), watchedIds)
        for (ref in next) {
            val target = byId[ref.id] ?: continue
            val request = runCatching { b.buildEpisode(series, target) }.getOrNull() ?: continue
            enqueue(context, request.copy(smart = true))
        }
    }

    // Playing

    /** Downloads of one title, for the "Downloaded" badge. */
    fun completedKeys(context: Context): Set<String> =
        store(context).items.filter { it.status == DownloadStatus.COMPLETED }.mapNotNull { it.meta.mediaKeyId }.toSet()

    val DOWNLOAD_SOURCE = SourceRef(SourceKind.DOWNLOAD, "downloads", "Downloads")

    /** The PlayRequest item for a finished download: the file itself, or the stored stream. */
    fun playItem(item: DownloadItem): PlayItem? {
        if (item.status != DownloadStatus.COMPLETED) return null
        val uri = if (item.isStream) item.url else item.outputUri ?: return null
        val m = item.meta
        val code = listOfNotNull(m.season?.let { "S$it" }, m.episode?.let { "E$it" }).joinToString("")
        return PlayItem(
            uri = uri,
            title = m.title,
            key = item.mediaKey ?: MediaKey.Link("download:${item.id}"),
            source = DOWNLOAD_SOURCE,
            headers = if (item.isStream) item.headers else emptyMap(),
            subtitle = listOfNotNull(code.ifEmpty { null }, m.episodeTitle).joinToString(" · ").ifEmpty { null },
            episode = if (m.isEpisode && m.titleId != null) EpisodeInfo(m.titleId, m.episodeId ?: "", m.season, m.episode, m.episodeTitle) else null,
            subtitles = item.subtitles.mapNotNull { s -> s.savedUri?.let { SubtitleOption(it, s.language, s.label) } },
            canSave = true,
            downloadable = true,
            mimeType = if (item.isStream) item.mimeType ?: StreamFetcher.mimeOf(item.kind) else null,
            streamKeys = item.tracks?.keys.orEmpty(),
        )
    }

    /** Lists an HLS or DASH stream's tracks so the user can pick before downloading. Blocking. */
    fun tracks(url: String, kind: DownloadKind, headers: Map<String, String>): StreamTracks =
        StreamFetcher.tracks(url, kind, headers, userAgent)

    /** Asks the server what a link is, to tell files from HLS and DASH streams without a file extension. Blocking. */
    fun detectKind(url: String, headers: Map<String, String>): DownloadKind {
        StreamFetcher.kindOf(url)?.let { return it }
        val probe = runCatching { FileFetcher(limiter, userAgent).probe(url, headers) }.getOrNull() ?: return DownloadKind.FILE
        return StreamFetcher.kindOf(url, probe.contentType) ?: DownloadKind.FILE
    }

    /** Bytes used by downloads, for the storage bar. */
    fun bytesUsed(context: Context): Long {
        val files = store(context).items.filter { !it.isStream && it.status == DownloadStatus.COMPLETED }.sumOf { it.totalBytes.coerceAtLeast(it.bytesDone) }
        return files + com.troy.orcaplayer.player.engine.OfflineCache.bytesUsed(context)
    }
}
