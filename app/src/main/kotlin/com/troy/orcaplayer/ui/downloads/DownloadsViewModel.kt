package com.troy.orcaplayer.ui.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.Episode
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.Profile
import com.troy.orcaplayer.core.model.SaveDecision
import com.troy.orcaplayer.core.model.SavingPolicy
import com.troy.orcaplayer.core.model.SavingRule
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.settings.DownloadSettings
import com.troy.orcaplayer.core.settings.Settings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.ToolSettings
import com.troy.orcaplayer.data.DownloadPlanner
import com.troy.orcaplayer.data.StreamEntry
import com.troy.orcaplayer.downloads.Downloads
import com.troy.orcaplayer.downloads.StreamFetcher
import com.troy.orcaplayer.downloads.StreamTracks
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadKind
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import com.troy.orcaplayer.downloads.logic.LiveStats
import com.troy.orcaplayer.downloads.logic.SmartSeries
import com.troy.orcaplayer.downloads.logic.TrackChoice
import com.troy.orcaplayer.tools.ExportJob
import com.troy.orcaplayer.tools.ToolJobService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Why a download can't start, or that it needs the one-time saving reminder first. */
enum class DownloadCheck { OK, REMINDER, DRM, SOURCE, SETTINGS, KIDS }

enum class DownloadsTab { ACTIVE, COMPLETED, FAILED }

enum class DownloadSort { NEWEST, NAME, SIZE }

/** Shared by the Downloads screen and every place a download starts (16.1, 16.7). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val settingsRepo: SettingsRepository,
    val planner: DownloadPlanner,
) : ViewModel() {
    private val store = Downloads.store(context)

    val items: StateFlow<List<DownloadItem>> = store.state.map { it.items }.stateIn(viewModelScope, SharingStarted.Eagerly, store.items)
    val live: StateFlow<Map<String, LiveStats>> = store.live
    val smart: StateFlow<List<SmartSeries>> = store.state.map { it.smart }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<SettingsValues> = profiles.currentProfileId.flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsValues.Defaults)

    val profile: StateFlow<Profile?> = combine(profiles.profiles, profiles.currentProfileId) { list, id -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val profileId: String get() = profiles.currentProfileId.value

    /** The saving rule (3.7) plus the kids rule (16.8) for one download. */
    fun check(source: SourceRef, drm: Boolean, downloadable: Boolean): DownloadCheck {
        val v = settings.value
        if (profile.value?.isKids == true && !v[DownloadSettings.KidsAllowed]) return DownloadCheck.KIDS
        val policy = SavingPolicy(savingEnabled = v[Settings.SavingEnabled], reminderAccepted = v[ToolSettings.SaveReminderSeen])
        return when (SavingRule.canSave(source, drm, downloadable, policy)) {
            SaveDecision.ALLOWED -> DownloadCheck.OK
            SaveDecision.NEEDS_REMINDER -> DownloadCheck.REMINDER
            SaveDecision.DENIED_DRM -> DownloadCheck.DRM
            SaveDecision.DENIED_BY_SOURCE -> DownloadCheck.SOURCE
            SaveDecision.DENIED_BY_SETTINGS -> DownloadCheck.SETTINGS
        }
    }

    fun acceptReminder() {
        viewModelScope.launch { settingsRepo.set(profileId, ToolSettings.SaveReminderSeen, true) }
    }

    /** True when downloads wait for a start time, so "Start now" is worth offering. */
    val hasSchedule: Boolean get() = settings.value[DownloadSettings.StartTime] != "off" || settings.value[DownloadSettings.ChargingOnly]

    // Starting

    fun kindOf(url: String): DownloadKind? = StreamFetcher.kindOf(url)

    suspend fun detectKind(url: String, headers: Map<String, String>): DownloadKind =
        withContext(Dispatchers.IO) { Downloads.detectKind(url, headers) }

    suspend fun tracks(url: String, kind: DownloadKind, headers: Map<String, String>): StreamTracks? =
        withContext(Dispatchers.IO) { runCatching { Downloads.tracks(url, kind, headers) }.getOrNull() }

    suspend fun subtitlesFor(title: Title, episode: Episode?, entry: StreamEntry): List<SubtitleOption> =
        planner.subtitles(title.type, episode?.id ?: title.id, entry)

    fun downloadStream(title: Title, episode: Episode?, entry: StreamEntry, subtitles: List<SubtitleOption>, tracks: TrackChoice?, startNow: Boolean, kind: DownloadKind?) {
        val request = planner.request(title, episode, entry, profileId, subtitles, tracks, startNow).copy(kind = kind)
        Downloads.enqueue(context, request)
    }

    /** The movie, one episode or several, each with the best stream for [height]. Calls [done] with how many were queued. */
    fun downloadBest(title: Title, episodes: List<Episode?>, height: Int?, done: (Int) -> Unit) {
        viewModelScope.launch {
            val n = runCatching { planner.downloadBest(title, episodes, height, profileId) }.getOrDefault(0)
            done(n)
        }
    }

    fun downloadItem(item: PlayItem, kind: DownloadKind?, tracks: TrackChoice? = null, startNow: Boolean = false) {
        Downloads.enqueue(context, planner.request(item, profileId).copy(kind = kind, tracks = tracks, startNow = startNow))
    }

    fun setSmart(title: Title, height: Int?, on: Boolean) {
        Downloads.setSmart(context, planner.smartSeries(title, height, profileId), on)
    }

    fun isSmart(titleId: String): Boolean = smart.value.any { it.seriesKey == titleId && it.profileId == profileId }

    // Controls

    fun pause(id: String) = Downloads.pause(context, id)
    fun resume(id: String) = Downloads.resume(context, id)
    fun retry(id: String) = Downloads.retry(context, id)
    fun startNow(id: String) = Downloads.startNow(context, id)
    fun delete(id: String) = Downloads.delete(context, id)
    fun again(id: String) = Downloads.downloadAgain(context, id)
    fun pauseAll() = Downloads.pauseAll(context)
    fun resumeAll() = Downloads.resumeAll(context)
    fun move(id: String, to: Int) = Downloads.move(context, id, to)

    fun moveToPrivate(id: String, done: (Boolean) -> Unit) {
        viewModelScope.launch { done(Downloads.moveToPrivate(context, id)) }
    }

    /** Saves a finished HLS or DASH download as one MP4 file through the tools queue (16.4). */
    fun exportMp4(item: DownloadItem): Boolean {
        if (!item.isStream || item.status != DownloadStatus.COMPLETED) return false
        ToolJobService.enqueue(
            context,
            ExportJob(item.url, item.fileName.substringBeforeLast('.'), item.mimeType ?: StreamFetcher.mimeOf(item.kind) ?: "", item.tracks?.keys.orEmpty(), item.headers),
        )
        return true
    }

    fun play(item: DownloadItem): PlayRequest? = Downloads.playItem(item)?.let { PlayRequest(listOf(it), 0, profileId) }

    /** Every finished episode of the same series after [item], so they play in a row. */
    fun playSeries(item: DownloadItem): PlayRequest? {
        val first = Downloads.playItem(item) ?: return null
        val key = item.seriesKey ?: return PlayRequest(listOf(first), 0, profileId)
        val rest = items.value.filter { it.seriesKey == key && it.status == DownloadStatus.COMPLETED && it.id != item.id }
            .sortedWith(compareBy({ it.meta.season ?: 0 }, { it.meta.episode ?: 0 }))
            .filter { (it.meta.season ?: 0) > (item.meta.season ?: 0) || ((it.meta.season ?: 0) == (item.meta.season ?: 0) && (it.meta.episode ?: 0) > (item.meta.episode ?: 0)) }
            .mapNotNull { Downloads.playItem(it) }
        return PlayRequest(listOf(first) + rest, 0, profileId)
    }

    fun bytesUsed(): Long = Downloads.bytesUsed(context)
}
