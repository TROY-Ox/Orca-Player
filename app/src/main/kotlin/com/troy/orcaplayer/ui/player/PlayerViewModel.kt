package com.troy.orcaplayer.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.MediaKey
import com.troy.orcaplayer.core.model.StreamAlternate
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.settings.SourceSettings
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.OnlineSubtitle
import com.troy.orcaplayer.data.OnlineSubtitles
import com.troy.orcaplayer.core.data.ResumePositionEntity
import com.troy.orcaplayer.core.data.ResumeRepository
import com.troy.orcaplayer.core.model.MediaPrefs
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.SaveDecision
import com.troy.orcaplayer.core.model.SavingPolicy
import com.troy.orcaplayer.core.model.SavingRule
import com.troy.orcaplayer.core.model.ToolsLayout
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.PlayerLayout
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.AudioVideoSettings
import com.troy.orcaplayer.core.settings.SettingDef
import com.troy.orcaplayer.core.settings.Settings
import com.troy.orcaplayer.core.settings.ToolSettings
import com.troy.orcaplayer.player.engine.DecoderMode
import com.troy.orcaplayer.player.engine.DolbyVision
import com.troy.orcaplayer.player.engine.EngineNotice
import com.troy.orcaplayer.player.engine.PlaybackStats
import com.troy.orcaplayer.player.engine.VideoCaps
import com.troy.orcaplayer.player.engine.PlaybackEngine
import com.troy.orcaplayer.player.engine.PlaybackService
import com.troy.orcaplayer.player.subtitles.ArabicText
import com.troy.orcaplayer.player.subtitles.CueTimeline
import com.troy.orcaplayer.player.subtitles.SubtitleParser
import com.troy.orcaplayer.sources.local.Sidecars
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class PlaybackUi(
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val title: String = "",
    val index: Int = 0,
    val count: Int = 0,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val isLive: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
    val insecure: Boolean = false,
) {
    val hasNext: Boolean get() = index < count - 1 || repeatMode == Player.REPEAT_MODE_ALL
    val hasPrevious: Boolean get() = index > 0
    val aspect: Float get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 16f / 9f
}

/** The manual online subtitle search (9.6). */
data class OnlineSearch(
    val loading: Boolean = false,
    val results: List<OnlineSubtitle> = emptyList(),
    val languages: List<String> = emptyList(),
    val keyMissing: Boolean = false,
)

/** A loaded external subtitle file. */
data class ExternalSubtitle(val uri: String, val name: String, val timeline: CueTimeline, val encoding: String)

/** "Resumed at 12:34 · Start over" (auto) or "Continue from 12:34?" (ask). */
data class ResumePill(val positionMs: Long, val ask: Boolean, val shownAt: Long = System.currentTimeMillis())

sealed interface PlayerEvent {
    data object Finish : PlayerEvent
    data class Message(val text: String) : PlayerEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val engine: PlaybackEngine,
    private val resume: ResumeRepository,
    private val settingsRepo: SettingsRepository,
    private val profiles: ProfileRepository,
    private val hub: ContentHub,
    private val online: OnlineSubtitles,
    marks: com.troy.orcaplayer.core.data.PlaybackMarksRepository,
) : ViewModel() {
    /** Saves must finish even when the screen closes, so they don't use viewModelScope. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: StateFlow<SettingsValues> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsValues.Defaults)

    val layout: StateFlow<PlayerLayout> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.playerLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerLayout.Default)

    val toolsLayout: StateFlow<ToolsLayout> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.toolsLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ToolsLayout.Default)

    val player: StateFlow<ExoPlayer> = engine.playerFlow
    val stats: StateFlow<PlaybackStats> = engine.stats

    /** Equalizer, effects and volume boost. */
    val sound = SoundController(viewModelScope, context, engine, settingsRepo, profiles.currentProfileId, settings)
    val extras = PlayerExtras(viewModelScope, settingsRepo, { profiles.currentProfileId.value }, { settings.value }).also { ex ->
        ex.onNightChanged = { on -> sound.setNightCompressor(on && settings.value[ToolSettings.NightCompressor]) }
    }

    /** Per-video values for the screen (aspect, zoom, brightness), with a counter so each start applies once. */
    private val _applied = MutableStateFlow(0 to MediaPrefs())
    val applied: StateFlow<Pair<Int, MediaPrefs>> = _applied.asStateFlow()
    val decoderMode: StateFlow<DecoderMode> = engine.decoderMode

    private val _ui = MutableStateFlow(PlaybackUi())
    val ui: StateFlow<PlaybackUi> = _ui.asStateFlow()

    private val _tracks = MutableStateFlow(Tracks.EMPTY)
    val tracks: StateFlow<Tracks> = _tracks.asStateFlow()

    private val _embeddedCues = MutableStateFlow<List<Cue>>(emptyList())
    val embeddedCues: StateFlow<List<Cue>> = _embeddedCues.asStateFlow()

    private val _external = MutableStateFlow<ExternalSubtitle?>(null)
    val external: StateFlow<ExternalSubtitle?> = _external.asStateFlow()

    /** Positive values show subtitles later. */
    private val _subtitleDelayMs = MutableStateFlow(0L)
    val subtitleDelayMs: StateFlow<Long> = _subtitleDelayMs.asStateFlow()

    /** Frame-rate correction for external files, for example 25 / 23.976. */
    private val _subtitleSpeed = MutableStateFlow(1.0)
    val subtitleSpeed: StateFlow<Double> = _subtitleSpeed.asStateFlow()

    private val _subtitlesOff = MutableStateFlow(false)
    val subtitlesOff: StateFlow<Boolean> = _subtitlesOff.asStateFlow()

    private val _resumePill = MutableStateFlow<ResumePill?>(null)
    val resumePill: StateFlow<ResumePill?> = _resumePill.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    var options: LaunchOptions = LaunchOptions()
        private set

    /** Screen lock: all touch is ignored except a small unlock button. */
    val locked = MutableStateFlow(false)

    fun setBackgroundPlay(enabled: Boolean) = setSetting(PlayerSettings.BackgroundPlay, enabled)

    fun <T> setSetting(def: SettingDef<T>, value: T) {
        viewModelScope.launch { settingsRepo.set(profiles.currentProfileId.value, def, value) }
    }

    // Automatic fallback (14.7)
    private var fallbackIndex = -1
    private var fallbackTries = 0
    private var freshTried = false
    private val triedUrls = mutableSetOf<String>()
    private var stallSince = 0L
    private var lastGoodPosition = 0L

    // Online subtitles (9.6)
    private val _onlineNotice = MutableStateFlow<OnlineSubtitle?>(null)
    /** The subtitle fetched automatically, shown with a Change button. */
    val onlineNotice: StateFlow<OnlineSubtitle?> = _onlineNotice.asStateFlow()
    private val _onlineResults = MutableStateFlow<OnlineSearch>(OnlineSearch())
    val onlineResults: StateFlow<OnlineSearch> = _onlineResults.asStateFlow()

    /** Media IDs that already showed the Dolby Vision warning, so it shows once per video. */
    private val dvWarned = mutableSetOf<String>()

    private var attached: ExoPlayer? = null
    private var handledIndex = -1
    private var explicitStart: Pair<Int, Long>? = null
    private var lastSaveAt = 0L

    /** Skip buttons, next episode and the Kids watch limit (17.6, 18). */
    val binge = PlayerBinge(viewModelScope, io, engine, hub, marks)

    /** The profile watching, for Kids rules. */
    val profile: StateFlow<com.troy.orcaplayer.core.model.Profile?> =
        kotlinx.coroutines.flow.combine(profiles.profiles, profiles.currentProfileId) { l, id -> l.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var started = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onTracksChanged(tracks: Tracks) {
            _tracks.value = tracks
        }

        override fun onCues(cueGroup: CueGroup) {
            _embeddedCues.value = cueGroup.cues
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onItemChanged()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveNow()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                saveNow()
                val p = attached ?: return
                if (!p.hasNextMediaItem() && p.repeatMode == Player.REPEAT_MODE_OFF && !binge.onEnded(p, settings.value)) _events.tryEmit(PlayerEvent.Finish)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (tryFallback()) return
            _ui.update { it.copy(error = error.errorCodeName) }
        }
    }

    init {
        viewModelScope.launch {
            engine.notices.collect { n ->
                _events.emit(PlayerEvent.Message(if (n == EngineNotice.DECODED_FOR_EFFECTS) MSG_DECODED_EFFECTS else MSG_DECODED_SPEED))
            }
        }
        viewModelScope.launch {
            // Dolby Vision profile 5 has no fallback: warn when this device can't show it.
            engine.stats.collect { st ->
                val f = st.video ?: return@collect
                val profile = DolbyVision.profileOf(f.codecs) ?: return@collect
                val id = attached?.currentMediaItem?.mediaId ?: return@collect
                if (!DolbyVision.hasNoFallback(profile) || id in dvWarned || !settings.value[AudioVideoSettings.DolbyVisionWarning]) return@collect
                dvWarned += id
                if (!withContext(Dispatchers.IO) { VideoCaps.hasDolbyVisionDecoder() }) _events.emit(PlayerEvent.Message(MSG_DV5))
            }
        }
        viewModelScope.launch {
            engine.playerFlow.collect { p ->
                attached?.removeListener(listener)
                p.addListener(listener)
                attached = p
                extras.armLoop(p)
                _tracks.value = p.currentTracks
                refresh()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(250)
                val p = attached ?: continue
                if (p.isPlaying || _ui.value.positionMs != p.currentPosition) refresh()
                if (p.isPlaying && System.currentTimeMillis() - lastSaveAt > 5_000) saveNow()
                checkStall(p)
                extras.tick(p)
                binge.tick(p, engine.itemAt(p.currentMediaItemIndex), settings.value, profile.value)
                _resumePill.value?.let { pill ->
                    if (System.currentTimeMillis() - pill.shownAt > if (pill.ask) 10_000 else 6_000) _resumePill.value = null
                }
            }
        }
    }

    private fun refresh() {
        val p = attached ?: return
        val size = p.videoSize
        val w = if (size.pixelWidthHeightRatio > 0f) (size.width * size.pixelWidthHeightRatio).toInt() else size.width
        val item = engine.itemAt(p.currentMediaItemIndex)
        _ui.update {
            it.copy(
                playing = p.isPlaying,
                playWhenReady = p.playWhenReady,
                buffering = p.playbackState == Player.STATE_BUFFERING,
                ended = p.playbackState == Player.STATE_ENDED,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: 0,
                bufferedMs = p.bufferedPosition.coerceAtLeast(0),
                title = item?.title ?: p.mediaMetadata.title?.toString().orEmpty(),
                index = p.currentMediaItemIndex,
                count = p.mediaItemCount,
                speed = p.playbackParameters.speed,
                videoWidth = w,
                videoHeight = size.height,
                isLive = p.isCurrentMediaItemLive,
                repeatMode = p.repeatMode,
                shuffle = p.shuffleModeEnabled,
                error = if (p.playerError == null) null else it.error,
                insecure = item?.uri?.startsWith("http:", ignoreCase = true) == true,
            )
        }
    }

    // Starting

    /** Handles the launch intent (or a new one). Returns false through [events] when there's nothing to play. */
    fun start(intent: Intent) {
        viewModelScope.launch {
            val fromApp = PlayerLaunch.take(intent)
            val request = fromApp ?: if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
                withContext(Dispatchers.IO) { runCatching { ExternalIntents.parse(intent, context.contentResolver) }.getOrNull() }
            } else {
                null
            }
            when {
                request != null -> begin(request.first, request.second)
                // Reopened from the notification or after the screen was recreated: keep playing.
                engine.queue.isNotEmpty() && (intent.action == PlaybackService.ACTION_OPEN_PLAYER || started || intent.action == null) -> {
                    started = true
                    handledIndex = engine.player.currentMediaItemIndex
                    refresh()
                }
                else -> _events.emit(PlayerEvent.Finish)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun begin(request: PlayRequest, opts: LaunchOptions) {
        if (started) saveNow()
        started = true
        options = opts
        val v = settingsRepo.values(request.profileId).first()
        val first = request.items[request.startIndex]
        val prefs = extras.onMediaStarted(first)
        engine.setDecoderMode(opts.decoderMode ?: DecoderMode.fromKey(prefs.decoder ?: v[PlayerSettings.Decoder]))
        extras.applySchedule()
        explicitStart = first.startPositionMs?.let { request.startIndex to it }
        val startPos = first.startPositionMs ?: resumeStart(first, v, request.profileId)
        _external.value = null
        _subtitleDelayMs.value = prefs.subtitleDelayMs ?: 0
        _subtitleSpeed.value = 1.0
        _applied.update { (n, _) -> n + 1 to prefs }
        _ui.update { it.copy(error = null) }
        handledIndex = request.startIndex
        val p = engine.player
        engine.setSpeed(prefs.speed ?: v[PlayerSettings.DefaultSpeed].toFloatOrNull() ?: 1f)
        p.pauseAtEndOfMediaItems = !v[PlayerSettings.AutoNext]
        applySubtitleLanguage(p, v)
        applyTrackPrefs(p, prefs)
        sound.onMediaStarted(first)
        engine.setStreamPrefs(
            SourceSettings.parseHeaders(v[SourceSettings.DefaultHeaders]),
            v[SourceSettings.UserAgent],
            v[SourceSettings.BufferSeconds].toIntOrNull() ?: 50,
            v[SourceSettings.CacheSize].toLongOrNull() ?: 200,
        )
        resetFallback(request.startIndex)
        _onlineNotice.value = null
        binge.onItem(first, request.startIndex)
        engine.load(request.items, request.startIndex, startPos)
        refresh()
        // Subtitles sent by the calling app, then files next to the video.
        val subs = opts.subtitles
        if (subs.isNotEmpty()) {
            val pick = subs.firstOrNull { it.first == opts.enabledSubtitle } ?: subs.first()
            loadSubtitle(Uri.parse(pick.first), pick.second)
        } else {
            autoLoadSidecar(first, v)
            autoOnline(first, v)
        }
    }

    private fun applySubtitleLanguage(p: ExoPlayer, v: SettingsValues) {
        val pref = v[PlayerSettings.SubtitleLanguage]
        val builder = p.trackSelectionParameters.buildUpon()
        if (pref == "off") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            _subtitlesOff.value = true
        } else {
            val lang = if (pref == "auto") Locale.getDefault().language else pref
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(lang)
            _subtitlesOff.value = false
        }
        p.trackSelectionParameters = builder.build()
    }

    /** Smart resume: the saved position, unless the video was finished or barely started. */
    private suspend fun resumeStart(item: PlayItem, v: SettingsValues, profileId: String): Long {
        val mode = v[PlayerSettings.Resume]
        if (mode == "off") return 0
        val saved = withContext(Dispatchers.IO) { resume.positionFor(profileId, item.key.id) } ?: return 0
        val threshold = (v[PlayerSettings.WatchedThreshold].toIntOrNull() ?: 90) / 100.0
        val watched = saved.durationMs > 0 && saved.positionMs >= saved.durationMs * threshold
        if (watched || saved.positionMs < 5_000) return 0
        val pos = (saved.positionMs - 2_000).coerceAtLeast(0)
        return if (mode == "ask") {
            _resumePill.value = ResumePill(pos, ask = true)
            0
        } else {
            _resumePill.value = ResumePill(pos, ask = false)
            pos
        }
    }

    private fun onItemChanged() {
        val p = attached ?: return
        val index = p.currentMediaItemIndex
        if (index == handledIndex) return
        handledIndex = index
        _external.value = null
        _subtitleDelayMs.value = 0
        _resumePill.value = null
        val item = engine.itemAt(index) ?: return
        binge.onItem(item, index)
        viewModelScope.launch {
            val v = settings.value
            sound.onMediaStarted(item)
            val prefs = extras.onMediaStarted(item)
            prefs.subtitleDelayMs?.let { _subtitleDelayMs.value = it }
            applyTrackPrefs(p, prefs)
            prefs.speed?.let { engine.setSpeed(it) }
            prefs.decoder?.let { k -> DecoderMode.fromKey(k).takeIf { it != engine.decoderMode.value }?.let(engine::setDecoderMode) }
            _applied.update { (n, _) -> n + 1 to prefs }
            val explicit = explicitStart?.takeIf { it.first == index }?.second
            val pos = explicit ?: resumeStart(item, v, profiles.currentProfileId.value)
            if (pos > 0 && p === attached && p.currentMediaItemIndex == index) p.seekTo(pos)
            resetFallback(index)
            _onlineNotice.value = null
            autoLoadSidecar(item, v)
            autoOnline(item, v)
        }
    }

    // Resume saving

    private val watchedSent = mutableSetOf<String>()

    fun saveNow() {
        val p = attached ?: return
        val item = engine.itemAt(p.currentMediaItemIndex) ?: return
        if (p.isCurrentMediaItemLive) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (p.playbackState == Player.STATE_ENDED) duration else p.currentPosition.coerceIn(0, duration)
        val threshold = (settings.value[PlayerSettings.WatchedThreshold].toIntOrNull() ?: 90) / 100.0
        val stored = if (position >= duration * threshold) duration else position
        lastSaveAt = System.currentTimeMillis()
        val profileId = profiles.currentProfileId.value
        // Watched to the end: smart downloads may delete it and fetch the next episode (16.6).
        if (stored == duration && item.source.kind != com.troy.orcaplayer.core.model.SourceKind.LOCAL && watchedSent.add(item.key.id)) {
            runCatching { com.troy.orcaplayer.downloads.Downloads.onWatched(context, item.key.id) }
        }
        io.launch {
            runCatching {
                resume.save(
                    ResumePositionEntity(
                        profileId = profileId,
                        mediaKey = item.key.id,
                        path = item.uri,
                        title = item.title,
                        positionMs = stored,
                        durationMs = duration,
                        updatedAt = System.currentTimeMillis(),
                        sourceKind = item.source.kind.name,
                        titleType = item.titleType,
                        titleId = item.episode?.titleId ?: (item.key as? com.troy.orcaplayer.core.model.MediaKey.SourceContent)?.titleId,
                        episodeId = item.episode?.episodeId?.takeIf { it.isNotEmpty() },
                        subtitle = item.subtitle,
                        poster = item.poster,
                        hidden = false,
                    ),
                )
            }
        }
    }

    fun resumeFromPill() {
        val pill = _resumePill.value ?: return
        attached?.seekTo(pill.positionMs)
        _resumePill.value = null
    }

    fun startOver() {
        attached?.seekTo(0)
        _resumePill.value = null
    }

    fun dismissPill() {
        _resumePill.value = null
    }

    // Transport

    fun togglePlay() {
        val p = attached ?: return
        when {
            p.playbackState == Player.STATE_ENDED -> { p.seekTo(0); p.play() }
            p.playbackState == Player.STATE_IDLE -> { p.prepare(); p.play() }
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
        refresh()
    }

    fun pause() = attached?.pause()

    fun seekTo(ms: Long) {
        val p = attached ?: return
        val d = _ui.value.durationMs
        p.seekTo(if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0))
        refresh()
    }

    fun seekBy(deltaMs: Long) = seekTo((attached?.currentPosition ?: 0) + deltaMs)

    fun next() {
        attached?.let { binge.playNext(it) }
    }

    fun previous() {
        val p = attached ?: return
        if (p.currentPosition > 5_000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    fun playAt(index: Int) {
        val p = attached ?: return
        if (index in 0 until p.mediaItemCount) {
            saveNow()
            p.seekToDefaultPosition(index)
            p.play()
        }
    }

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 4f)
        engine.setSpeed(s)
        extras.remember { it.copy(speed = s) }
        refresh()
    }

    fun cycleRepeat() {
        val p = attached ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        refresh()
    }

    fun toggleShuffle() {
        val p = attached ?: return
        p.shuffleModeEnabled = !p.shuffleModeEnabled
        refresh()
    }

    fun retry() {
        val p = attached ?: return
        _ui.update { it.copy(error = null) }
        p.prepare()
        p.play()
    }

    fun setDecoder(mode: DecoderMode) {
        _ui.update { it.copy(error = null) }
        engine.setDecoderMode(mode)
        extras.remember { it.copy(decoder = mode.key) }
    }

    // Tools

    /** The playing item, for the tools. */
    fun currentItem(): PlayItem? = attached?.let { engine.itemAt(it.currentMediaItemIndex) }

    fun currentPosition(): Long = attached?.currentPosition ?: 0

    /** The saving rule (3.7) for the playing item. */
    fun saveDecision(): SaveDecision {
        val item = currentItem() ?: return SaveDecision.DENIED_BY_SOURCE
        val v = settings.value
        return SavingRule.canSave(item, SavingPolicy(savingEnabled = v[Settings.SavingEnabled], reminderAccepted = v[ToolSettings.SaveReminderSeen]))
    }

    fun acceptSaveReminder() = setSetting(ToolSettings.SaveReminderSeen, true)

    fun rememberAspect(aspect: String, zoom: Float) = extras.remember { it.copy(aspect = aspect, zoom = zoom.takeIf { z -> z != 1f }) }

    fun rememberBrightness(value: Float) = extras.remember { it.copy(brightness = value) }

    fun markA() = extras.markA(currentPosition())

    fun markB(): Boolean = extras.markB(currentPosition())

    fun startSleep(minutes: Int) = attached?.let { extras.startSleep(minutes, it) }

    fun sleepAtEnd() = attached?.let { extras.sleepAtEnd(it) }

    fun cancelSleep() = attached?.let { extras.cancelSleep(it) }

    fun sleepRemaining(): Long? = attached?.let { extras.sleepRemaining(it) }

    /** Stops playback for good (the screen closed and background play is off). */
    fun stopPlayback() {
        saveNow()
        engine.stop()
    }

    fun finalResult(): Intent {
        val u = _ui.value
        return ExternalIntents.result(u.positionMs, u.durationMs, u.ended)
    }

    // Tracks

    fun selectTrack(group: Tracks.Group, trackIndex: Int) {
        val p = attached ?: return
        val type = group.type
        if (type == C.TRACK_TYPE_TEXT) {
            _external.value = null
            _subtitlesOff.value = false
        }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        val lang = group.getTrackFormat(trackIndex).language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        when (type) {
            C.TRACK_TYPE_AUDIO -> lang?.let { l -> extras.remember { it.copy(audioLanguage = l) } }
            C.TRACK_TYPE_TEXT -> extras.remember { it.copy(subtitleLanguage = lang, subtitlesOff = false) }
        }
    }

    /** Track languages saved for this video win over the profile's preferred languages. */
    private fun applyTrackPrefs(p: ExoPlayer, prefs: MediaPrefs) {
        val b = p.trackSelectionParameters.buildUpon()
        prefs.audioLanguage?.let { b.setPreferredAudioLanguage(it) }
        if (prefs.subtitlesOff == true) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            _subtitlesOff.value = true
        } else {
            prefs.subtitleLanguage?.let { b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(it) }
        }
        p.trackSelectionParameters = b.build()
    }

    /** Auto quality for adaptive streams. */
    fun autoVideoQuality() {
        val p = attached ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
    }

    fun subtitlesOff() {
        extras.remember { it.copy(subtitlesOff = true) }
        val p = attached ?: return
        _external.value = null
        _subtitlesOff.value = true
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    fun setSubtitleDelay(ms: Long) {
        val d = ms.coerceIn(-600_000, 600_000)
        _subtitleDelayMs.value = d
        extras.remember { it.copy(subtitleDelayMs = d) }
    }

    fun setSubtitleSpeed(speed: Double) {
        _subtitleSpeed.value = speed
    }

    // External subtitles

    fun loadSubtitle(uri: Uri, name: String? = null, encoding: String? = null) {
        if (uri.scheme == "http" || uri.scheme == "https") {
            // A subtitle link (URL player, sources): fetch it to the cache first.
            viewModelScope.launch {
                val headers = currentItem()?.headers.orEmpty()
                val file = runCatching { online.fetch(uri.toString(), headers, name ?: uri.lastPathSegment ?: "subtitle") }.getOrNull()
                if (file == null) _events.emit(PlayerEvent.Message(MSG_LOAD_FAILED)) else loadSubtitle(Uri.fromFile(file), name ?: uri.lastPathSegment, encoding)
            }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = input.readBytes()
                        require(buf.size < 20 * 1024 * 1024) { "File too large" }
                        buf
                    } ?: error("Can't open")
                    val chosen = encoding ?: settings.value[PlayerSettings.SubtitleEncoding]
                    // Android's ICU has no charset detector; ArabicText checks UTF-8 and falls back to Windows-1256.
                    val detected: String? = null
                    val text = ArabicText.decode(bytes, chosen, detected)
                    val fileName = name ?: displayName(uri)
                    val timeline = SubtitleParser.parse(text, fileName)
                    ExternalSubtitle(uri.toString(), fileName, timeline, if (chosen != "auto") chosen else if (ArabicText.isValidUtf8(bytes)) "UTF-8" else "windows-1256")
                }
            }
            result.onSuccess { sub ->
                if (sub.timeline.cues.isEmpty()) {
                    _events.emit(PlayerEvent.Message(MSG_NO_CUES))
                    return@onSuccess
                }
                _external.value = sub
                _subtitlesOff.value = false
                // The app's own layer draws external subtitles, so embedded text tracks are turned off.
                attached?.let { p ->
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                }
            }.onFailure {
                _events.emit(PlayerEvent.Message(MSG_LOAD_FAILED))
            }
        }
    }

    /** Re-reads the current external file with another text encoding. */
    fun reloadWithEncoding(encoding: String) {
        val sub = _external.value ?: return
        loadSubtitle(Uri.parse(sub.uri), sub.name, encoding)
    }

    private suspend fun autoLoadSidecar(item: PlayItem, v: SettingsValues) {
        if (!v[PlayerSettings.SubtitleAutoLoad] || item.source.kind != SourceKind.LOCAL) return
        val path = withContext(Dispatchers.IO) { pathOf(Uri.parse(item.uri)) } ?: return
        val matches = withContext(Dispatchers.IO) { Sidecars.find(path) }
        if (matches.isEmpty()) return
        val pref = v[PlayerSettings.SubtitleLanguage].let { if (it == "auto") Locale.getDefault().language else it }
        if (pref == "off") return
        val pick = matches.firstOrNull { it.language == pref } ?: matches.first()
        loadSubtitle(Uri.fromFile(pick.file), pick.file.name)
    }

    private fun pathOf(uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path
        "content" -> runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        else -> null
    }?.takeIf { File(it).isAbsolute }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle"
    }

    /** Fix-punctuation applied at draw time, so the switch works without reloading. */
    fun textFor(text: String): String = if (settings.value[PlayerSettings.FixPunctuation]) ArabicText.fixPunctuation(text) else text

    // Automatic fallback (14.7)

    private fun resetFallback(index: Int) {
        fallbackIndex = index
        fallbackTries = 0
        freshTried = false
        triedUrls.clear()
        engine.itemAt(index)?.let { triedUrls += it.uri }
        stallSince = 0
        lastGoodPosition = 0
    }

    private fun checkStall(p: ExoPlayer) {
        val item = engine.itemAt(p.currentMediaItemIndex) ?: return
        if (p.isPlaying) lastGoodPosition = p.currentPosition
        val network = item.uri.startsWith("http", ignoreCase = true)
        if (!network || p.playbackState != Player.STATE_BUFFERING || !p.playWhenReady) {
            stallSince = 0
            return
        }
        val now = System.currentTimeMillis()
        if (stallSince == 0L) {
            stallSince = now
            return
        }
        val limit = (settings.value[SourceSettings.StallSeconds].toLongOrNull() ?: 15) * 1000
        if (now - stallSince > limit) {
            stallSince = 0
            tryFallback()
        }
    }

    /** Moves to the next stream of the same video, from the same position. False when there's none to try. */
    private fun tryFallback(): Boolean {
        val p = attached ?: return false
        val v = settings.value
        if (!v[SourceSettings.Fallback]) return false
        val index = p.currentMediaItemIndex
        if (index != fallbackIndex) resetFallback(index)
        val item = engine.itemAt(index) ?: return false
        val limit = v[SourceSettings.FallbackRetries].toIntOrNull() ?: 3
        if (fallbackTries >= limit) return false
        val position = maxOf(p.currentPosition, lastGoodPosition)
        val next = item.alternates.firstOrNull { it.uri !in triedUrls }
        if (next != null) {
            switchTo(index, item, next, item.alternates.filter { it !== next && it.uri !in triedUrls }, position)
            return true
        }
        // Out of alternates: for an addon title, ask the sources for fresh links once (expired links).
        val key = item.key as? MediaKey.SourceContent ?: return false
        val target = hub.targetFor(key.id) ?: return false
        if (freshTried) return false
        freshTried = true
        viewModelScope.launch {
            val fresh = runCatching { hub.freshAlternates(target, triedUrls.toSet(), SourceSettings.parseHeaders(v[SourceSettings.DefaultHeaders])) }.getOrDefault(emptyList())
            val first = fresh.firstOrNull()
            if (first == null || attached !== p || p.currentMediaItemIndex != index) {
                _ui.update { it.copy(error = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED") }
                return@launch
            }
            switchTo(index, item, first, fresh.drop(1), position)
        }
        return true
    }

    private fun switchTo(index: Int, item: PlayItem, alt: StreamAlternate, rest: List<StreamAlternate>, position: Long) {
        fallbackTries++
        triedUrls += alt.uri
        val next = item.copy(
            uri = alt.uri,
            headers = alt.headers,
            subtitles = alt.subtitles.ifEmpty { item.subtitles },
            alternates = rest,
        )
        _ui.update { it.copy(error = null) }
        engine.replaceItem(index, next, position)
        _events.tryEmit(PlayerEvent.Message(MSG_FALLBACK))
    }

    // Online subtitles (9.6)

    private fun preferredLanguage(v: SettingsValues): String? =
        v[PlayerSettings.SubtitleLanguage].let { if (it == "off") null else if (it == "auto") Locale.getDefault().language else it }

    /** When no subtitle in the preferred language is loaded, fetch the best match and say so. */
    private fun autoOnline(item: PlayItem, v: SettingsValues) {
        if (!v[SourceSettings.SubtitleAutoDownload]) return
        val lang = preferredLanguage(v) ?: return
        val index = attached?.currentMediaItemIndex ?: return
        viewModelScope.launch {
            // Embedded tracks are known only once the video is prepared.
            delay(3_000)
            if (attached?.currentMediaItemIndex != index || _external.value != null || _subtitlesOff.value) return@launch
            val hasEmbedded = _tracks.value.groups.any { g ->
                g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { i -> LanguageCodes.matches(g.getTrackFormat(i).language, lang) }
            }
            if (hasEmbedded) return@launch
            val results = searchOnline(item, v, listOf(lang))
            val best = results.firstOrNull { LanguageCodes.matches(it.language, lang) } ?: return@launch
            if (attached?.currentMediaItemIndex != index || _external.value != null) return@launch
            if (loadOnlineNow(best, v)) _onlineNotice.value = best
        }
    }

    /** Opens the manual search: stream subtitles, subtitle addons and OpenSubtitles, in [languages]. */
    fun searchOnlineSubtitles(languages: List<String>) {
        val item = currentItem() ?: return
        val v = settings.value
        _onlineResults.value = OnlineSearch(loading = true, languages = languages)
        viewModelScope.launch {
            val results = searchOnline(item, v, languages)
            _onlineResults.value = OnlineSearch(results = results, languages = languages, keyMissing = v[SourceSettings.OpenSubtitlesKey].isBlank())
        }
    }

    fun pickOnline(sub: OnlineSubtitle) {
        viewModelScope.launch {
            if (loadOnlineNow(sub, settings.value)) _onlineNotice.value = null
        }
    }

    fun dismissOnlineNotice() {
        _onlineNotice.value = null
    }

    private suspend fun loadOnlineNow(sub: OnlineSubtitle, v: SettingsValues): Boolean {
        val file = runCatching {
            if (sub.url != null) online.fetch(sub.url, sub.headers, sub.label) else online.download(sub, v[SourceSettings.OpenSubtitlesKey])
        }.getOrNull()
        if (file == null) {
            _events.emit(PlayerEvent.Message(MSG_LOAD_FAILED))
            return false
        }
        loadSubtitle(Uri.fromFile(file), sub.label)
        return true
    }

    private suspend fun searchOnline(item: PlayItem, v: SettingsValues, languages: List<String>): List<OnlineSubtitle> {
        val out = mutableListOf<OnlineSubtitle>()
        fun fromOption(o: SubtitleOption, match: Int) = OnlineSubtitle(
            id = o.url, language = o.language, label = o.label ?: o.language ?: item.title,
            source = o.source ?: item.source, url = o.url, headers = o.headers, match = match,
        )
        item.subtitles.forEach { out += fromOption(it, 90) }
        val key = item.key
        val target = (key as? MediaKey.SourceContent)?.let { hub.targetFor(it.id) }
        if (target != null) {
            runCatching { hub.subtitles(target.type, target.videoId, null, null, null) }.getOrDefault(emptyList())
                .forEach { out += fromOption(it, 70) }
        }
        val apiKey = v[SourceSettings.OpenSubtitlesKey]
        if (apiKey.isNotBlank()) {
            val hash = if (item.source.kind == SourceKind.LOCAL) withContext(Dispatchers.IO) { pathOf(Uri.parse(item.uri))?.let { OnlineSubtitles.hashOf(File(it)) } } else null
            val imdb = target?.videoId?.substringBefore(':')?.takeIf { it.startsWith("tt") }
            val os = runCatching {
                online.searchOpenSubtitles(
                    apiKey, languages.map { LanguageCodes.toTwoLetter(it) },
                    imdbId = imdb, season = target?.episode?.season, episode = target?.episode?.number,
                    movieHash = hash, query = if (imdb == null && hash == null) item.title else null,
                )
            }.getOrDefault(emptyList())
            out += os
        }
        return out
            .filter { languages.isEmpty() || languages.any { l -> LanguageCodes.matches(it.language, l) } }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<OnlineSubtitle> { it.match }.thenByDescending { it.downloads })
    }

    override fun onCleared() {
        saveNow()
        binge.flush(profiles.currentProfileId.value)
        extras.release()
        attached?.removeListener(listener)
        attached = null
        super.onCleared()
    }

    companion object {
        const val MSG_NO_CUES = "no_cues"
        const val MSG_DECODED_EFFECTS = "decoded_effects"
        const val MSG_DECODED_SPEED = "decoded_speed"
        const val MSG_DV5 = "dv5"
        const val MSG_LOAD_FAILED = "load_failed"
        const val MSG_FALLBACK = "fallback"
    }
}
