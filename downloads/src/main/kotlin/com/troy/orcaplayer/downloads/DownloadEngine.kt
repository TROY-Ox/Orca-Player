package com.troy.orcaplayer.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.troy.orcaplayer.downloads.logic.Conditions
import com.troy.orcaplayer.downloads.logic.DownloadError
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadKind
import com.troy.orcaplayer.downloads.logic.DownloadPlace
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import com.troy.orcaplayer.downloads.logic.Gate
import com.troy.orcaplayer.downloads.logic.Naming
import com.troy.orcaplayer.downloads.logic.Queue
import com.troy.orcaplayer.downloads.logic.Retry
import com.troy.orcaplayer.downloads.logic.Schedule
import com.troy.orcaplayer.downloads.logic.WaitReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** What to do once the queue has nothing it can run now. */
sealed interface FollowUp {
    data object None : FollowUp
    data class Later(val delayMs: Long, val charging: Boolean) : FollowUp
}

/**
 * Runs the queue (16.2, 16.3): up to the parallel limit at once, under the network, charging,
 * schedule and storage rules, with retries, link refreshes and resume.
 */
internal class DownloadEngine(private val context: Context) {
    private val store = Downloads.store(context)
    private val running = ConcurrentHashMap<String, Job>()
    private val speeds = ConcurrentHashMap<String, Pair<Long, Long>>()

    private fun conditions(item: DownloadItem?): Conditions {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val battery = context.getSystemService(BatteryManager::class.java)
        val now = System.currentTimeMillis()
        return Conditions(
            connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            charging = battery?.isCharging == true,
            minuteOfDay = Schedule.minuteOfDay(now),
            freeBytes = item?.let { Sinks.freeBytes(context, it) } ?: Long.MAX_VALUE,
        )
    }

    suspend fun run(onTick: (List<DownloadItem>) -> Unit): FollowUp = coroutineScope {
        try {
            while (true) {
                val config = store.config
                val now = System.currentTimeMillis()
                val open = Schedule.isOpen(config.startMinute, store.state.value.scheduleWaitingSince, now)
                // Running downloads stop and wait when Wi-Fi or the network goes away.
                running.keys.forEach { id ->
                    val item = store.get(id) ?: return@forEach
                    val c = conditions(null)
                    val reason = when {
                        !c.connected -> WaitReason.NETWORK
                        config.wifiOnly && !c.unmetered -> WaitReason.WIFI
                        else -> null
                    }
                    if (reason != null && item.status == DownloadStatus.RUNNING) store.update(id) { it.copy(status = DownloadStatus.WAITING, waitReason = reason) }
                }
                val parallel = config.parallel.coerceIn(1, 5)
                for (item in Queue.runnable(store.items)) {
                    if (item.id in running) continue
                    val reason = Gate.waitReason(item, config, conditions(item), open, now)
                    if (reason == null && running.size < parallel) {
                        store.update(item.id) { it.copy(status = DownloadStatus.RUNNING, waitReason = null, error = null, errorDetail = null) }
                        running[item.id] = launch(Dispatchers.IO) {
                            try {
                                runItem(item.id)
                            } finally {
                                running.remove(item.id)
                                speeds.remove(item.id)
                                store.clearSpeed(item.id)
                            }
                        }
                    } else {
                        val status = if (reason == null) DownloadStatus.QUEUED else DownloadStatus.WAITING
                        if (item.status != status || item.waitReason != reason) store.update(item.id) { it.copy(status = status, waitReason = reason) }
                    }
                }
                onTick(store.items)
                if (running.isEmpty()) {
                    val waiting = Queue.runnable(store.items)
                    if (waiting.isEmpty()) {
                        if (store.items.none { it.active }) store.setScheduleWaiting(0)
                        return@coroutineScope FollowUp.None
                    }
                    if (config.startMinute != null && store.state.value.scheduleWaitingSince == 0L) store.setScheduleWaiting(now)
                    return@coroutineScope followUp(waiting, now)
                }
                delay(1000)
            }
            @Suppress("UNREACHABLE_CODE")
            FollowUp.None
        } finally {
            store.flush()
        }
    }

    private fun followUp(waiting: List<DownloadItem>, now: Long): FollowUp {
        val config = store.config
        val delays = waiting.map { item ->
            when (item.waitReason) {
                WaitReason.RETRY -> (item.nextAttemptAt - now).coerceAtLeast(1_000)
                WaitReason.SCHEDULE -> config.startMinute?.let { Schedule.delayUntil(it, now) } ?: 60_000L
                WaitReason.STORAGE -> 30 * 60_000L
                WaitReason.NETWORK, WaitReason.WIFI -> 15_000L
                WaitReason.CHARGING, null -> 15_000L
            }
        }
        val charging = waiting.all { it.waitReason == WaitReason.CHARGING }
        return FollowUp.Later(delays.minOrNull() ?: 60_000L, charging)
    }

    private fun stopper(id: String, job: Job?): () -> Boolean = {
        val s = store.get(id)?.status
        s != DownloadStatus.RUNNING || job?.isActive == false
    }

    private suspend fun runItem(id: String) {
        val job = coroutineContext[Job]
        val start = store.get(id) ?: return
        try {
            when (start.kind) {
                DownloadKind.FILE -> runFile(id, stopper(id, job))
                DownloadKind.HLS, DownloadKind.DASH -> runStream(id, stopper(id, job))
            }
            finish(id)
        } catch (e: Stopped) {
            afterStop(id)
        } catch (e: IsStream) {
            // The link was a stream after all: drop the file and download it as a stream.
            val item = store.get(id) ?: return
            Sinks.delete(context, item.outputUri, item.outputPath)
            store.update(id) {
                it.copy(kind = e.kind, mimeType = StreamFetcher.mimeOf(e.kind), place = DownloadPlace.PRIVATE, outputUri = null, outputPath = null, bytesDone = 0, totalBytes = -1)
            }
            runItem(id)
        } catch (e: FetchFailure) {
            failed(id, e)
        } catch (e: StorageFailure) {
            store.update(id) { it.copy(status = DownloadStatus.FAILED, error = DownloadError.STORAGE, errorDetail = e.message) }
            DownloadNotifications.failed(context, store.get(id))
        } catch (e: CancellationException) {
            afterStop(id)
            throw e
        } catch (e: Exception) {
            failed(id, FetchFailure(DownloadError.UNKNOWN, retryable = true, message = e.message ?: e.javaClass.simpleName))
        }
    }

    /** Leaves a paused or waiting state alone; a download stopped by the system goes back to the queue. */
    private fun afterStop(id: String) {
        val item = store.get(id)
        when {
            item == null -> Unit // Deleted: Downloads.delete removes the files.
            item.status == DownloadStatus.RUNNING -> store.update(id) { it.copy(status = DownloadStatus.QUEUED) }
            else -> store.flush()
        }
    }

    // Files

    private suspend fun runFile(id: String, stop: () -> Boolean) = withContext(Dispatchers.IO) {
        val fetcher = FileFetcher(Downloads.limiter, Downloads.userAgent)
        var item = store.get(id) ?: throw Stopped()
        if ((item.outputUri == null && item.outputPath == null) || !Sinks.exists(context, item)) {
            // First start: learn the real size, type and name before creating the file.
            val probe = fetcher.probe(item.url, item.headers)
            StreamFetcher.kindOf(item.url, probe.contentType)?.let { throw IsStream(it) }
            val ext = Naming.extensionFor(item.url, probe.contentType, probe.disposition)
            val name = item.fileName.substringBeforeLast('.', item.fileName) + "." + ext
            store.update(id) {
                it.copy(fileName = name, mimeType = Naming.mimeForExtension(ext), totalBytes = if (probe.total > 0) probe.total else it.totalBytes, rangeSupported = probe.acceptsRanges, bytesDone = 0, segments = emptyList())
            }
            item = store.get(id) ?: throw Stopped()
            checkSpace(item)
            val created = Sinks.create(context, item)
            store.update(id) { it.copy(outputUri = created.uri, outputPath = created.path) }
            item = store.get(id) ?: throw Stopped()
        }
        checkSpace(item)
        val config = store.config
        Sinks.open(context, item).use { sink ->
            val size = runCatching { sink.channel.size() }.getOrDefault(0L)
            val offset = if (item.segments.isNotEmpty()) 0 else minOf(item.bytesDone, size)
            if (offset == 0L && item.segments.isEmpty()) runCatching { sink.channel.truncate(0) }
            var lastSpaceCheck = offset
            fetcher.run(
                url = item.url,
                headers = item.headers,
                out = sink.channel,
                offset = offset,
                validator = item.etag ?: item.lastModified,
                segments = item.segments,
                multiConnection = config.multiConnection,
                stop = stop,
                onProbe = { p, restarted ->
                    store.update(id) {
                        it.copy(
                            totalBytes = if (p.total > 0) p.total else it.totalBytes,
                            rangeSupported = p.acceptsRanges,
                            etag = p.etag ?: it.etag,
                            lastModified = p.lastModified ?: it.lastModified,
                            restartedNotice = it.restartedNotice || restarted,
                            bytesDone = if (restarted) 0 else it.bytesDone,
                        )
                    }
                },
                onProgress = { pr ->
                    track(id, pr.done)
                    store.update(id, force = false) {
                        it.copy(bytesDone = pr.done, totalBytes = if (pr.total > 0) pr.total else it.totalBytes, segments = pr.segments, attempts = 0)
                    }
                    if (pr.done - lastSpaceCheck > 64L * 1024 * 1024) {
                        lastSpaceCheck = pr.done
                        if (Sinks.freeBytes(context, item) < config.reserveBytes) {
                            store.update(id) { it.copy(status = DownloadStatus.PAUSED, error = DownloadError.NO_SPACE) }
                        }
                    }
                },
            )
        }
        if (store.get(id)?.status != DownloadStatus.RUNNING) throw Stopped()
    }

    private fun checkSpace(item: DownloadItem) {
        val free = Sinks.freeBytes(context, item)
        val need = if (item.totalBytes > 0) item.totalBytes - item.bytesDone else 0
        if (free - need < store.config.reserveBytes) throw FetchFailure(DownloadError.NO_SPACE)
    }

    private fun track(id: String, bytes: Long) {
        val now = System.nanoTime()
        val prev = speeds[id]
        if (prev == null) {
            speeds[id] = now to bytes
            return
        }
        val dt = (now - prev.first) / 1e9
        if (dt < 1.0) return
        val speed = ((bytes - prev.second) / dt).toLong().coerceAtLeast(0)
        val old = store.live.value[id]?.speed ?: speed
        store.setSpeed(id, (old * 0.6 + speed * 0.4).toLong())
        speeds[id] = now to bytes
    }

    // Streams

    private suspend fun runStream(id: String, stop: () -> Boolean) = withContext(Dispatchers.IO) {
        var item = store.get(id) ?: throw Stopped()
        if (item.tracks == null) {
            val tracks = StreamFetcher.tracks(item.url, item.kind, item.headers, Downloads.userAgent)
            if (tracks.drmProtected) throw FetchFailure(DownloadError.NOT_ALLOWED, message = "protected")
            val height = item.meta.quality?.filter { it.isDigit() }?.toIntOrNull()
            store.update(id) { it.copy(tracks = tracks.defaultChoice(height)) }
            item = store.get(id) ?: throw Stopped()
        }
        checkSpace(item)
        val config = store.config
        var lastSpaceCheck = 0L
        StreamFetcher.run(context, item, Downloads.limiter, Downloads.userAgent, stop) { percent, bytes ->
            track(id, bytes)
            store.update(id, force = false) { it.copy(percent = percent, bytesDone = bytes, attempts = 0) }
            if (bytes - lastSpaceCheck > 64L * 1024 * 1024) {
                lastSpaceCheck = bytes
                if (Sinks.freeBytes(context, item) < config.reserveBytes) store.update(id) { it.copy(status = DownloadStatus.PAUSED, error = DownloadError.NO_SPACE) }
            }
        }
    }

    // Finishing

    private suspend fun finish(id: String) = withContext(Dispatchers.IO) {
        val item = store.get(id) ?: throw Stopped()
        val done = if (item.isStream) Sinks.Created(null, null) else Sinks.finish(context, item)
        val finished = item.copy(outputUri = done.uri ?: item.outputUri, outputPath = done.path)
        val videoName = if (item.isStream) item.fileName else Sinks.displayName(context, finished)
        val fetcher = FileFetcher(Downloads.limiter, Downloads.userAgent)
        val taken = mutableSetOf<String>()
        val subs = finished.subtitles.map { sub ->
            if (sub.savedUri != null) return@map sub
            runCatching {
                val bytes = fetcher.fetchBytes(sub.url, sub.headers)
                val name = Naming.subtitleName(videoName, sub.language, Naming.subtitleExtension(sub.url, null), taken).also { taken += it }
                sub.copy(savedUri = if (item.isStream) {
                    val f = java.io.File(Sinks.privateSubtitleDir(context), "${item.id}-$name")
                    f.writeBytes(bytes)
                    android.net.Uri.fromFile(f).toString()
                } else Sinks.saveSubtitle(context, finished, name, bytes))
            }.getOrDefault(sub)
        }
        store.update(id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                outputUri = finished.outputUri,
                outputPath = finished.outputPath,
                fileName = videoName,
                percent = 100f,
                bytesDone = if (it.isStream) it.bytesDone else maxOf(it.bytesDone, it.totalBytes),
                totalBytes = if (it.isStream) it.bytesDone else maxOf(it.bytesDone, it.totalBytes),
                subtitles = subs,
                error = null,
                errorDetail = null,
                waitReason = null,
            )
        }
        if (store.config.notifyFinished) DownloadNotifications.completed(context, store.get(id))
    }

    // Failures (16.3)

    private suspend fun failed(id: String, e: FetchFailure) {
        val item = store.get(id) ?: return
        if (item.status != DownloadStatus.RUNNING) return
        val config = store.config
        val canRefresh = item.meta.videoId != null && item.refreshes < 3 && Downloads.bridge != null
        val stalled = e.retryable && item.attempts >= 2
        if (canRefresh && (e.error == DownloadError.EXPIRED || stalled) && refresh(id)) return
        when {
            e.error == DownloadError.NO_SPACE ->
                store.update(id) { it.copy(status = DownloadStatus.PAUSED, error = DownloadError.NO_SPACE) }
            e.retryable && item.attempts < config.maxRetries -> {
                val attempt = item.attempts + 1
                store.update(id) {
                    it.copy(status = DownloadStatus.WAITING, waitReason = WaitReason.RETRY, attempts = attempt, nextAttemptAt = System.currentTimeMillis() + Retry.delayMs(attempt), errorDetail = e.message)
                }
            }
            else -> {
                store.update(id) { it.copy(status = DownloadStatus.FAILED, error = e.error, errorDetail = e.message ?: e.code.takeIf { c -> c > 0 }?.toString()) }
                DownloadNotifications.failed(context, store.get(id))
            }
        }
    }

    /**
     * Asks the source for a fresh link for the same title and episode. A file of the same size
     * continues from the same byte; anything else starts again (16.3).
     */
    private suspend fun refresh(id: String): Boolean {
        val item = store.get(id) ?: return false
        val fresh = runCatching { Downloads.bridge?.freshLink(item) }.getOrNull() ?: return false
        if (fresh.url == item.url && fresh.headers == item.headers) return false
        val sameSize = withContext(Dispatchers.IO) {
            if (item.isStream) false
            else runCatching { FileFetcher(Downloads.limiter, Downloads.userAgent).probe(fresh.url, fresh.headers) }.getOrNull()
                ?.let { p -> item.totalBytes > 0 && p.total == item.totalBytes } == true
        }
        if (item.isStream) withContext(Dispatchers.IO) { StreamFetcher.remove(context, item, Downloads.userAgent) }
        store.update(id) {
            it.copy(
                url = fresh.url,
                headers = fresh.headers,
                status = DownloadStatus.QUEUED,
                refreshes = it.refreshes + 1,
                attempts = 0,
                nextAttemptAt = 0,
                etag = null,
                lastModified = null,
                bytesDone = if (sameSize) it.bytesDone else 0,
                segments = if (sameSize) it.segments else emptyList(),
                percent = if (it.isStream) -1f else it.percent,
                tracks = if (it.isStream) null else it.tracks,
            )
        }
        return true
    }
}
