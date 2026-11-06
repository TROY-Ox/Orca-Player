package com.troy.orcaplayer.ui.player

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.troy.orcaplayer.core.model.AbLoop
import com.troy.orcaplayer.core.model.MediaPrefs
import com.troy.orcaplayer.core.model.MediaPrefsStore
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.ToolSettings
import com.troy.orcaplayer.tools.ToolMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** A running sleep timer: at a clock time, or at the end of the current video. */
data class SleepTimer(val endAt: Long? = null, val atEndOfVideo: Boolean = false)

/**
 * Player tools that keep state while playing (12.8 and 12.9): A-B repeat, the sleep timer, night
 * mode and per-video settings. Owned by the player's ViewModel.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerExtras(
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
    private val profileId: () -> String,
    private val settings: () -> SettingsValues,
) {
    // A-B repeat

    private val _abStart = MutableStateFlow<Long?>(null)
    /** Point A is set and B is not yet. */
    val abStart: StateFlow<Long?> = _abStart.asStateFlow()
    private val _abLoop = MutableStateFlow<AbLoop?>(null)
    val abLoop: StateFlow<AbLoop?> = _abLoop.asStateFlow()
    private var abMessage: PlayerMessage? = null
    private var abPlayer: ExoPlayer? = null

    fun markA(positionMs: Long) {
        _abStart.value = positionMs
        setLoop(null, save = false)
    }

    /** Sets point B; with no A yet, the loop runs from the start. Returns false when B is before A. */
    fun markB(positionMs: Long): Boolean {
        val a = _abStart.value ?: 0L
        if (positionMs <= a + 200) return false
        _abStart.value = null
        setLoop(AbLoop(a, positionMs), save = true)
        return true
    }

    fun clearAb() {
        _abStart.value = null
        setLoop(null, save = true)
    }

    private fun setLoop(loop: AbLoop?, save: Boolean) {
        _abLoop.value = loop
        abPlayer?.let { armLoop(it) }
        if (save) remember { it.copy(abLoop = loop) }
    }

    /** A message at B seeks back to A each time playback passes B; exact to the frame. */
    fun armLoop(player: ExoPlayer) {
        abMessage?.cancel()
        abMessage = null
        abPlayer = player
        val loop = _abLoop.value ?: return
        abMessage = player.createMessage { _, _ -> player.seekTo(loop.aMs) }
            .setLooper(Looper.getMainLooper())
            .setPosition(player.currentMediaItemIndex, loop.bMs)
            .setDeleteAfterDelivery(false)
            .send()
    }

    // Sleep timer

    private val _sleep = MutableStateFlow<SleepTimer?>(null)
    val sleep: StateFlow<SleepTimer?> = _sleep.asStateFlow()
    private var pauseAtEndBefore: Boolean? = null

    fun startSleep(minutes: Int, player: Player) {
        cancelSleep(player)
        _sleep.value = SleepTimer(endAt = System.currentTimeMillis() + minutes * 60_000L)
    }

    fun sleepAtEnd(player: Player) {
        cancelSleep(player)
        pauseAtEndBefore = player.pauseAtEndOfMediaItemsCompat()
        (player as? ExoPlayer)?.pauseAtEndOfMediaItems = true
        _sleep.value = SleepTimer(atEndOfVideo = true)
    }

    fun extendSleep(minutes: Int) {
        _sleep.update { s -> s?.endAt?.let { s.copy(endAt = it + minutes * 60_000L) } ?: s }
    }

    fun cancelSleep(player: Player) {
        _sleep.value = null
        player.volume = 1f
        pauseAtEndBefore?.let { (player as? ExoPlayer)?.pauseAtEndOfMediaItems = it }
        pauseAtEndBefore = null
    }

    /** Remaining time of the sleep timer, or null when none runs. */
    fun sleepRemaining(player: Player): Long? {
        val s = _sleep.value ?: return null
        return when {
            s.endAt != null -> (s.endAt - System.currentTimeMillis()).coerceAtLeast(0)
            s.atEndOfVideo -> {
                val d = player.duration
                if (d <= 0) null else ((d - player.currentPosition) / player.playbackParameters.speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(0)
            }
            else -> null
        }
    }

    /** Called about four times a second while the player screen is alive. */
    fun tick(player: Player) {
        val s = _sleep.value ?: return
        val remaining = sleepRemaining(player) ?: return
        if (settings()[ToolSettings.SleepFade]) player.volume = ToolMath.fadeVolume(remaining)
        val done = if (s.atEndOfVideo) !player.isPlaying && remaining < 1500 else remaining <= 0
        if (done) {
            player.pause()
            cancelSleep(player)
        }
    }

    private fun Player.pauseAtEndOfMediaItemsCompat(): Boolean = (this as? ExoPlayer)?.pauseAtEndOfMediaItems ?: false

    // Night mode

    private val _night = MutableStateFlow(false)
    val night: StateFlow<Boolean> = _night.asStateFlow()
    var onNightChanged: (Boolean) -> Unit = {}

    fun setNight(on: Boolean) {
        _night.value = on
        onNightChanged(on)
    }

    /** At the start of playback: night mode follows the clock when the schedule is on. */
    fun applySchedule() {
        val v = settings()
        if (!v[ToolSettings.NightSchedule]) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start = v[ToolSettings.NightStart].toIntOrNull() ?: 22
        val end = v[ToolSettings.NightEnd].toIntOrNull() ?: 6
        setNight(ToolSettings.inNightWindow(hour, start, end))
    }

    // Per-video settings

    private val _store = MutableStateFlow(MediaPrefsStore())
    private var loadedFor: String? = null
    private var mediaKey: String? = null
    private var folderKey: String? = null
    private var saveJob: Job? = null

    private val _current = MutableStateFlow(MediaPrefs())
    /** What is saved for the playing video (video values over folder values). */
    val current: StateFlow<MediaPrefs> = _current.asStateFlow()
    private val _hasVideoPrefs = MutableStateFlow(false)
    val hasVideoPrefs: StateFlow<Boolean> = _hasVideoPrefs.asStateFlow()

    /** Loads what is saved for [item] and sets its A-B loop. */
    suspend fun onMediaStarted(item: PlayItem): MediaPrefs {
        val id = profileId()
        if (loadedFor != id) {
            _store.value = repo.mediaPrefs(id).first()
            loadedFor = id
        }
        mediaKey = item.key.id
        folderKey = SoundController.folderOf(item)
        val prefs = if (settings()[ToolSettings.RememberPerVideo]) _store.value.resolve(mediaKey, folderKey) else MediaPrefs()
        _current.value = prefs
        _hasVideoPrefs.value = _store.value.perMedia.containsKey(mediaKey)
        _abStart.value = null
        _abLoop.value = prefs.abLoop
        abPlayer?.let { armLoop(it) }
        return prefs
    }

    /** Saves a change for the playing video, when "Remember settings for each video" is on. */
    fun remember(change: (MediaPrefs) -> MediaPrefs) {
        if (!settings()[ToolSettings.RememberPerVideo]) return
        val key = mediaKey ?: return
        _store.update { it.updateMedia(key, change) }
        _current.value = _store.value.resolve(mediaKey, folderKey)
        _hasVideoPrefs.value = true
        persist()
    }

    /** Copies the playing video's values to its folder, so other videos there start the same. */
    fun saveForFolder() {
        val f = folderKey ?: return
        val values = _current.value
        _store.update { it.updateFolder(f) { values.copy(abLoop = null) } }
        persist()
    }

    fun resetVideo() {
        val key = mediaKey ?: return
        _store.update { it.resetMedia(key) }
        _current.value = _store.value.resolve(mediaKey, folderKey)
        _hasVideoPrefs.value = false
        persist()
    }

    fun resetFolder() {
        val f = folderKey ?: return
        _store.update { it.resetFolder(f) }
        _current.value = _store.value.resolve(mediaKey, folderKey)
        persist()
    }

    val canSaveFolder: Boolean get() = folderKey != null

    private fun persist() {
        val id = profileId()
        val snapshot = _store.value
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            repo.updateMediaPrefs(id) { snapshot }
        }
    }

    fun release() {
        abMessage?.cancel()
        abMessage = null
        abPlayer = null
    }
}
