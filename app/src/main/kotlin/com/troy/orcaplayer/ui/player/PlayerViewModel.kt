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
import com.troy.orcaplayer.core.data.ResumePositionEntity
import com.troy.orcaplayer.core.data.ResumeRepository
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.PlayerLayout
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.AudioVideoSettings
import com.troy.orcaplayer.core.settings.SettingDef
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
) : ViewModel() {
    /** Saves must finish even when the screen closes, so they don't use viewModelScope. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: StateFlow<SettingsValues> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsValues.Defaults)

    val layout: StateFlow<PlayerLayout> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.playerLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerLayout.Default)

    val player: StateFlow<ExoPlayer> = engine.playerFlow
    val stats: StateFlow<PlaybackStats> = engine.stats

    /** Equalizer, effects and volume boost. */
    val sound = SoundController(viewModelScope, context, engine, settingsRepo, profiles.currentProfileId, settings)
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

    /** Media IDs that already showed the Dolby Vision warning, so it shows once per video. */
    private val dvWarned = mutableSetOf<String>()

    private var attached: ExoPlayer? = null
    private var handledIndex = -1
    private var explicitStart: Pair<Int, Long>? = null
    private var lastSaveAt = 0L
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
                if (!p.hasNextMediaItem() && p.repeatMode == Player.REPEAT_MODE_OFF) _events.tryEmit(PlayerEvent.Finish)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
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
        engine.setDecoderMode(opts.decoderMode ?: DecoderMode.fromKey(v[PlayerSettings.Decoder]))
        val first = request.items[request.startIndex]
        explicitStart = first.startPositionMs?.let { request.startIndex to it }
        val startPos = first.startPositionMs ?: resumeStart(first, v, request.profileId)
        _external.value = null
        _subtitleDelayMs.value = 0
        _subtitleSpeed.value = 1.0
        _ui.update { it.copy(error = null) }
        handledIndex = request.startIndex
        val p = engine.player
        engine.setSpeed(v[PlayerSettings.DefaultSpeed].toFloatOrNull() ?: 1f)
        p.pauseAtEndOfMediaItems = !v[PlayerSettings.AutoNext]
        applySubtitleLanguage(p, v)
        sound.onMediaStarted(first)
        engine.load(request.items, request.startIndex, startPos)
        refresh()
        // Subtitles sent by the calling app, then files next to the video.
        val subs = opts.subtitles
        if (subs.isNotEmpty()) {
            val pick = subs.firstOrNull { it.first == opts.enabledSubtitle } ?: subs.first()
            loadSubtitle(Uri.parse(pick.first), pick.second)
        } else {
            autoLoadSidecar(first, v)
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
        viewModelScope.launch {
            val v = settings.value
            sound.onMediaStarted(item)
            val explicit = explicitStart?.takeIf { it.first == index }?.second
            val pos = explicit ?: resumeStart(item, v, profiles.currentProfileId.value)
            if (pos > 0 && p === attached && p.currentMediaItemIndex == index) p.seekTo(pos)
            autoLoadSidecar(item, v)
        }
    }

    // Resume saving

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
        attached?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
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
        engine.setSpeed(speed.coerceIn(0.25f, 4f))
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
    }

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
    }

    /** Auto quality for adaptive streams. */
    fun autoVideoQuality() {
        val p = attached ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
    }

    fun subtitlesOff() {
        val p = attached ?: return
        _external.value = null
        _subtitlesOff.value = true
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    fun setSubtitleDelay(ms: Long) {
        _subtitleDelayMs.value = ms.coerceIn(-600_000, 600_000)
    }

    fun setSubtitleSpeed(speed: Double) {
        _subtitleSpeed.value = speed
    }

    // External subtitles

    fun loadSubtitle(uri: Uri, name: String? = null, encoding: String? = null) {
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

    override fun onCleared() {
        saveNow()
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
    }
}
