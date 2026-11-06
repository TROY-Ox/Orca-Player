package com.troy.orcaplayer.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs cut, convert, compress and extract jobs in the background with a progress notification and
 * a Cancel action. It stops itself when the queue is empty.
 */
class ToolJobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var runner: ToolRunner

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        JobsRepository.init(this)
        ensureChannel(this)
        runner = ToolRunner(this) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        goForeground(notification(null))
        scope.launch {
            JobsRepository.jobs.collect { list ->
                val running = list.firstOrNull { it.status == JobStatus.RUNNING }
                if (running != null) getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(running))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(notification(JobsRepository.jobs.value.firstOrNull { it.status == JobStatus.RUNNING }))
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let(runner::cancel)
        }
        runner.kick()
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }
    }

    private fun notification(job: JobState?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tool_job)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentTitle(getString(R.string.job_running))
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.putExtra(EXTRA_OPEN_JOBS, true)
            b.setContentIntent(PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (job != null) {
            b.setContentText(job.spec.title)
            if (job.progress >= 0) b.setProgress(100, job.progress, false) else b.setProgress(0, 0, true)
            val cancel = Intent(this, ToolJobService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, job.id)
            b.addAction(0, getString(R.string.job_cancel), PendingIntent.getService(this, job.id.hashCode(), cancel, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    override fun onDestroy() {
        runner.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "tool_jobs"
        const val NOTIFICATION_ID = 4101
        const val ACTION_CANCEL = "com.troy.orcaplayer.tools.CANCEL"
        const val EXTRA_ID = "job_id"
        /** Set on the launch intent from the notification, so the app can open the jobs list. */
        const val EXTRA_OPEN_JOBS = "open_jobs"

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.job_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }

        /** Queues a job and makes sure the service runs it. */
        fun enqueue(context: Context, spec: JobSpec): JobState {
            JobsRepository.init(context)
            val job = JobsRepository.add(spec)
            ContextCompat.startForegroundService(context, Intent(context, ToolJobService::class.java))
            return job
        }

        fun cancel(context: Context, id: String) {
            ContextCompat.startForegroundService(context, Intent(context, ToolJobService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id))
        }
    }
}
