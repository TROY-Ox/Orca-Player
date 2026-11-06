package com.troy.orcaplayer.downloads

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.troy.orcaplayer.downloads.logic.DownloadItem
import kotlinx.coroutines.sync.withLock

/**
 * Runs the download queue as a long-running WorkManager job, so downloads continue after the app
 * is closed and come back after a reboot or a network drop (16.3, 16.8).
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private fun info(items: List<DownloadItem>): ForegroundInfo {
        val n = DownloadNotifications.progress(applicationContext, items, Downloads.store(applicationContext).live.value)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(DownloadNotifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(DownloadNotifications.PROGRESS_ID, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(Downloads.store(applicationContext).items)

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = Downloads.store(context)
        if (store.items.none { it.active }) return Result.success()
        // Android may refuse a foreground start from the background; the work still runs and resumes later.
        runCatching { setForeground(info(store.items)) }
        val engine = DownloadEngine(context)
        var lastTick = 0L
        val follow = Downloads.runLock.withLock {
            engine.run { items ->
                val now = System.currentTimeMillis()
                if (now - lastTick >= 1_000) {
                    lastTick = now
                    DownloadNotifications.updateProgress(context, items, store.live.value)
                }
            }
        }
        DownloadNotifications.afterRun(context, store.items)
        if (follow is FollowUp.Later) Downloads.scheduleLater(context, follow.delayMs, follow.charging)
        return Result.success()
    }
}
