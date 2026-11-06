package com.troy.orcaplayer.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** Pause, resume, cancel and delete from the download notifications. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE_ALL -> Downloads.pauseAll(context)
            ACTION_RESUME_ALL -> {
                NotificationManagerCompat.from(context).cancel(DownloadNotifications.PAUSED_ID)
                Downloads.resumeAll(context)
            }
            ACTION_CANCEL_ALL -> {
                NotificationManagerCompat.from(context).cancel(DownloadNotifications.PAUSED_ID)
                Downloads.cancelAll(context)
            }
            ACTION_DELETE -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                NotificationManagerCompat.from(context).cancel(id.hashCode())
                Downloads.delete(context, id)
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.troy.orcaplayer.downloads.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.troy.orcaplayer.downloads.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.troy.orcaplayer.downloads.CANCEL_ALL"
        const val ACTION_DELETE = "com.troy.orcaplayer.downloads.DELETE"
        const val EXTRA_ID = "download_id"
    }
}
