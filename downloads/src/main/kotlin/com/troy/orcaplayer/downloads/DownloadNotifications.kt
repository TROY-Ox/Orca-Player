package com.troy.orcaplayer.downloads

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import com.troy.orcaplayer.downloads.logic.Format
import com.troy.orcaplayer.downloads.logic.LiveStats

/** One grouped progress notification with pause and cancel, plus finished and failed notices (16.7). */
object DownloadNotifications {
    const val CHANNEL_PROGRESS = "downloads"
    const val CHANNEL_DONE = "downloads_done"
    const val PROGRESS_ID = 4201
    const val PAUSED_ID = 4202
    private const val GROUP = "orca_downloads"

    /** Set on the launch intent so the app opens the Downloads screen. */
    const val EXTRA_OPEN_DOWNLOADS = "open_downloads"

    private fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.dl_channel_progress), NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, context.getString(R.string.dl_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.putExtra(EXTRA_OPEN_DOWNLOADS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 7, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(context: Context, action: String, code: Int, id: String? = null): PendingIntent {
        val i = Intent(context, DownloadActionReceiver::class.java).setAction(action)
        id?.let { i.putExtra(DownloadActionReceiver.EXTRA_ID, it) }
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progress(context: Context, items: List<DownloadItem>, live: Map<String, LiveStats>): Notification {
        channels(context)
        val running = items.filter { it.status == DownloadStatus.RUNNING }
        val waiting = items.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.WAITING }
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
        if (running.isEmpty()) {
            b.setContentTitle(context.getString(R.string.dl_notif_waiting, waiting)).setProgress(0, 0, true)
        } else {
            val speed = running.sumOf { live[it.id]?.speed ?: 0 }
            val fractions = running.mapNotNull { it.fraction }
            b.setContentTitle(context.resources.getQuantityString(R.plurals.dl_notif_running, running.size, running.size))
            b.setContentText(running.joinToString(" · ") { it.fileName.substringBeforeLast('.') } + " · " + Format.bytes(speed) + "/s")
            if (fractions.size == running.size) b.setProgress(1000, (fractions.average() * 1000).toInt(), false) else b.setProgress(0, 0, true)
        }
        b.addAction(0, context.getString(R.string.dl_pause_all), action(context, DownloadActionReceiver.ACTION_PAUSE_ALL, 1))
        b.addAction(0, context.getString(R.string.dl_cancel_all), action(context, DownloadActionReceiver.ACTION_CANCEL_ALL, 2))
        return b.build()
    }

    fun updateProgress(context: Context, items: List<DownloadItem>, live: Map<String, LiveStats>) {
        if (!canNotify(context)) return
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(PROGRESS_ID, progress(context, items, live)) }
    }

    /** After a run: shows "paused" with Resume and Cancel when the user paused downloads. */
    fun afterRun(context: Context, items: List<DownloadItem>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(PROGRESS_ID)
        val paused = items.count { it.status == DownloadStatus.PAUSED }
        if (paused == 0 || !canNotify(context)) {
            nm.cancel(PAUSED_ID)
            return
        }
        channels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setSilent(true)
            .setGroup(GROUP)
            .setContentTitle(context.resources.getQuantityString(R.plurals.dl_notif_paused, paused, paused))
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.dl_resume_all), action(context, DownloadActionReceiver.ACTION_RESUME_ALL, 3))
            .addAction(0, context.getString(R.string.dl_cancel_all), action(context, DownloadActionReceiver.ACTION_CANCEL_ALL, 4))
            .build()
        runCatching { nm.notify(PAUSED_ID, n) }
    }

    private fun notice(context: Context, item: DownloadItem, title: Int) {
        if (!canNotify(context)) return
        channels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setContentTitle(context.getString(title))
            .setContentText(item.fileName)
            .setContentIntent(openApp(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(item.id.hashCode(), n) }
    }

    fun completed(context: Context, item: DownloadItem?) {
        item?.let { notice(context, it, R.string.dl_notif_done) }
    }

    fun failed(context: Context, item: DownloadItem?) {
        item?.let { notice(context, it, R.string.dl_notif_failed) }
    }

    /** Smart downloads with "ask first": an episode was watched; delete it? */
    fun askDelete(context: Context, item: DownloadItem) {
        if (!canNotify(context)) return
        channels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setContentTitle(context.getString(R.string.dl_notif_watched))
            .setContentText(item.fileName)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.dl_delete), action(context, DownloadActionReceiver.ACTION_DELETE, item.id.hashCode(), item.id))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(item.id.hashCode(), n) }
    }
}
