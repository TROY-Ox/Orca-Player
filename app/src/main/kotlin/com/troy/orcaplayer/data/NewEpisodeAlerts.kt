package com.troy.orcaplayer.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.troy.orcaplayer.MainActivity
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.data.MyListRepository
import com.troy.orcaplayer.core.model.TitleType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlertsEntryPoint {
    fun hub(): ContentHub
    fun myList(): MyListRepository
}

/**
 * New-episode alerts (17.8): every few hours, checks the series in every profile's My List and
 * tells the user when one has more episodes than last time. The first check only records the count.
 */
class NewEpisodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, AlertsEntryPoint::class.java)
        val hub = ep.hub()
        val list = ep.myList()
        val series = runCatching { list.everything() }.getOrDefault(emptyList())
            .filter { it.titleType == TitleType.SERIES.name && it.titleId != null }
        val notified = mutableSetOf<String>()
        for (entry in series) {
            val id = entry.titleId ?: continue
            val title = runCatching { hub.details(TitleType.SERIES, id, null) }.getOrNull() ?: continue
            val regular = title.episodes.filter { (it.season ?: 1) != 0 }
            val count = regular.size
            if (count == 0) continue
            val last = regular.maxWithOrNull(compareBy({ it.season ?: 1 }, { it.number ?: 0 }))
            if (entry.knownEpisodes >= 0 && count > entry.knownEpisodes && notified.add(id)) {
                NewEpisodeAlerts.notify(applicationContext, id, title.name, last?.let { e ->
                    listOfNotNull(e.season?.let { "S$it" }, e.number?.let { "E$it" }).joinToString("") + (e.name?.let { " · $it" } ?: "")
                }, count - entry.knownEpisodes)
            }
            if (count != entry.knownEpisodes) runCatching { list.setKnownEpisodes(entry.profileId, entry.itemKey, count, last?.id) }
        }
        return Result.success()
    }
}

object NewEpisodeAlerts {
    private const val WORK = "orca.new_episodes"
    const val CHANNEL = "new_episodes"

    /** Opens a title's details from the notification: "TYPE/id". */
    const val EXTRA_OPEN_TITLE = "orca.open_title"

    /** Schedules the check every [hours], or stops it when null. */
    fun schedule(context: Context, hours: Int?) {
        val wm = WorkManager.getInstance(context)
        if (hours == null || hours <= 0) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<NewEpisodeWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun notify(context: Context, titleId: String, name: String, episode: String?, newCount: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_TITLE, "${TitleType.SERIES.name}/$titleId")
        val pi = PendingIntent.getActivity(context, titleId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.resources.getQuantityString(R.plurals.alerts_new_episodes, newCount, newCount) + (episode?.let { " · $it" } ?: "")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(WORK, titleId.hashCode(), n) }
    }
}
