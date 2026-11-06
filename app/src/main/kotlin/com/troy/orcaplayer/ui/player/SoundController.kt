package com.troy.orcaplayer.ui.player

import android.content.Context
import com.troy.orcaplayer.core.model.AudioFx
import com.troy.orcaplayer.core.model.AudioStore
import com.troy.orcaplayer.core.model.EqPreset
import com.troy.orcaplayer.core.model.MediaKey
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.settings.AudioVideoSettings as AV
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.player.audio.ChainConfig
import com.troy.orcaplayer.player.audio.LimiterStrength
import com.troy.orcaplayer.player.audio.Presets
import com.troy.orcaplayer.player.engine.PlaybackEngine
import com.troy.orcaplayer.player.engine.Spatial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.UUID

/** Where the current sound came from, shown on the equalizer. */
enum class FxOrigin { GLOBAL, VIDEO, FOLDER }

/**
 * The equalizer and volume boost for the player: keeps the stored values, applies the ones saved
 * for a video or its folder, and hands the resulting chain settings to the engine.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SoundController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val engine: PlaybackEngine,
    private val repo: SettingsRepository,
    private val profileId: StateFlow<String>,
    private val settings: StateFlow<SettingsValues>,
) {
    private val _store = MutableStateFlow(AudioStore())
    val store: StateFlow<AudioStore> = _store.asStateFlow()

    private val _fx = MutableStateFlow(AudioFx())
    /** The values playing now: the global ones, or those saved for this video or folder. */
    val fx: StateFlow<AudioFx> = _fx.asStateFlow()

    private val _origin = MutableStateFlow(FxOrigin.GLOBAL)
    val origin: StateFlow<FxOrigin> = _origin.asStateFlow()

    private var mediaKey: String? = null
    private var folderKey: String? = null
    private var saveJob: Job? = null
    private var loaded = false

    private val _nightCompressor = MutableStateFlow(false)

    /** Night mode evens out loud sounds without changing the saved sound. */
    fun setNightCompressor(on: Boolean) {
        _nightCompressor.value = on
    }

    private fun nightFx(fx: AudioFx, night: Boolean): AudioFx = when {
        !night -> fx
        fx.enabled -> fx.copy(compressor = maxOf(fx.compressor, NIGHT_COMPRESSOR))
        else -> AudioFx(compressor = NIGHT_COMPRESSOR, boostPercent = fx.boostPercent)
    }

    init {
        scope.launch {
            profileId.flatMapLatest { repo.audioStore(it) }.collect { s ->
                _store.value = s
                if (!loaded) {
                    loaded = true
                    _fx.value = s.current
                }
            }
        }
        scope.launch {
            combine(_fx, settings, _nightCompressor) { fx, v, night -> Triple(nightFx(fx, night), v, night) }.collect { (fx, v, _) ->
                val spatial = v[AV.SpatialAudio]
                engine.setOutputPrefs(passthrough = v[AV.Passthrough], spatial = spatial)
                engine.setAudioConfig(
                    ChainConfig(
                        fx = fx,
                        boost = if (v[AV.ExtraVolume]) fx.boostPercent.coerceIn(100, maxBoost(v)) / 100f else 1f,
                        normalize = v[AV.Normalize],
                        quietRecording = v[AV.QuietRecording],
                        limiter = LimiterStrength.fromKey(v[AV.Limiter]),
                        virtualizerAllowed = !(spatial && Spatial.systemActive(context)),
                    ),
                )
            }
        }
    }

    fun maxBoost(v: SettingsValues = settings.value): Int = v[AV.MaxBoost].toIntOrNull() ?: 200

    val virtualizerBlocked: Boolean get() = settings.value[AV.SpatialAudio] && Spatial.systemActive(context)

    /** Called when a video starts: uses the sound saved for it or its folder, if any. */
    suspend fun onMediaStarted(item: PlayItem) {
        val store = repo.audioStore(profileId.value).first()
        _store.value = store
        loaded = true
        mediaKey = item.key.id
        folderKey = folderOf(item)
        val auto = settings.value[AV.AutoApply]
        val video = if (auto) store.perMedia[item.key.id] else null
        val folder = if (auto) folderKey?.let { store.perFolder[it] } else null
        when {
            video != null -> { _fx.value = video; _origin.value = FxOrigin.VIDEO }
            folder != null -> { _fx.value = folder; _origin.value = FxOrigin.FOLDER }
            else -> { _fx.value = store.current; _origin.value = FxOrigin.GLOBAL }
        }
    }

    /** Changes the values. Saved as the global sound unless a video or folder sound is playing. */
    fun update(change: (AudioFx) -> AudioFx) {
        val next = change(_fx.value)
        _fx.value = next
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val id = profileId.value
            when (_origin.value) {
                FxOrigin.GLOBAL -> repo.updateAudioStore(id) { it.copy(current = next) }
                FxOrigin.VIDEO -> mediaKey?.let { k -> repo.updateAudioStore(id) { it.withMedia(k, next) } }
                FxOrigin.FOLDER -> folderKey?.let { k -> repo.updateAudioStore(id) { it.withFolder(k, next) } }
            }
        }
    }

    fun setBand(index: Int, db: Float) = update { fx ->
        fx.copy(bands = fx.bands.mapIndexed { i, v -> if (i == index) db else v }, preset = Presets.CUSTOM)
    }

    fun applyPreset(p: EqPreset) = update { it.withPreset(p).copy(enabled = true, boostPercent = it.boostPercent) }

    fun setBoost(percent: Int) = update { it.copy(boostPercent = percent.coerceIn(100, maxBoost())) }

    fun savePreset(name: String) {
        val fx = _fx.value
        val preset = EqPreset(
            id = "user_" + UUID.randomUUID().toString().take(8),
            name = name.trim().take(40),
            bands = fx.bands, preampDb = fx.preampDb, bassDb = fx.bassDb,
            virtualizer = fx.virtualizer, loudness = fx.loudness, compressor = fx.compressor,
        )
        scope.launch { repo.updateAudioStore(profileId.value) { it.copy(custom = it.custom + preset) } }
        update { it.copy(preset = preset.id) }
    }

    fun deletePreset(id: String) {
        scope.launch { repo.updateAudioStore(profileId.value) { s -> s.copy(custom = s.custom.filterNot { it.id == id }) } }
        if (_fx.value.preset == id) update { it.copy(preset = Presets.CUSTOM) }
    }

    fun saveForVideo() {
        val k = mediaKey ?: return
        val fx = _fx.value
        _origin.value = FxOrigin.VIDEO
        scope.launch { repo.updateAudioStore(profileId.value) { it.withMedia(k, fx) } }
    }

    fun saveForFolder() {
        val k = folderKey ?: return
        val fx = _fx.value
        _origin.value = FxOrigin.FOLDER
        scope.launch { repo.updateAudioStore(profileId.value) { it.withFolder(k, fx) } }
    }

    /** Stops using the video or folder sound and goes back to the global one. */
    fun useGlobal() {
        val k = mediaKey
        val origin = _origin.value
        scope.launch {
            repo.updateAudioStore(profileId.value) { s ->
                when (origin) {
                    FxOrigin.VIDEO -> s.copy(perMedia = s.perMedia - (k ?: ""))
                    FxOrigin.FOLDER -> s.copy(perFolder = s.perFolder - (folderKey ?: ""))
                    FxOrigin.GLOBAL -> s
                }
            }
        }
        _origin.value = FxOrigin.GLOBAL
        _fx.value = _store.value.current
    }

    fun reset() = update { AudioFx(boostPercent = 100) }

    val canSaveFolder: Boolean get() = folderKey != null

    /** True once, the first time the boost goes above 150% (if the warning is on). */
    fun shouldWarnBoost(percent: Int): Boolean {
        if (percent <= 150 || !settings.value[AV.BoostWarning] || _store.value.boostWarned) return false
        _store.value = _store.value.copy(boostWarned = true)
        scope.launch { repo.updateAudioStore(profileId.value) { it.copy(boostWarned = true) } }
        return true
    }

    companion object {
        const val NIGHT_COMPRESSOR = 0.7f

        fun titleFolder(sourceId: String, titleId: String) = "title:$sourceId:$titleId"

        fun folderOf(item: PlayItem): String? {
            // Addon titles: the "folder" is the title, so a series' episodes share its settings (14.6).
            (item.key as? MediaKey.SourceContent)?.let { return titleFolder(it.sourceId, it.titleId) }
            val path = (item.key as? MediaKey.LocalFile)?.path
            if (!path.isNullOrEmpty() && path.contains('/')) return "dir:" + path.substringBeforeLast('/')
            val uri = item.uri.substringBefore('?')
            if (uri.startsWith("content://")) return null
            return uri.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { "dir:$it" }
        }
    }
}
