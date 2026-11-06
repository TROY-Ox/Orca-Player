package com.troy.orcaplayer.ui.player

import com.troy.orcaplayer.core.model.FilterPreset
import com.troy.orcaplayer.core.model.FilterStore
import com.troy.orcaplayer.core.model.MediaPrefs
import com.troy.orcaplayer.core.model.VideoFilter
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.player.engine.PlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Picture filters (20.6) and the audio delay (10.6) for the player screen. Filters can be kept
 * for one video (per-video settings) or for the profile; presets are saved per profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPicture(
    private val scope: CoroutineScope,
    private val engine: PlaybackEngine,
    private val repo: SettingsRepository,
    profileId: StateFlow<String>,
    private val extras: PlayerExtras,
) {
    private val pid = profileId
    val store: StateFlow<FilterStore> = profileId.flatMapLatest { repo.filterStore(it) }
        .stateIn(scope, SharingStarted.Eagerly, FilterStore())

    private val _filter = MutableStateFlow(VideoFilter())
    val filter: StateFlow<VideoFilter> = _filter.asStateFlow()

    /** True while the filter in use is saved for this video only. */
    private val _perVideo = MutableStateFlow(false)
    val perVideo: StateFlow<Boolean> = _perVideo.asStateFlow()

    val active: StateFlow<Boolean> = engine.filtersActive
    val audioDevice = engine.audioDelay.device
    val audioDelayMs = engine.audioDelay.delayMs

    init {
        scope.launch {
            store.collect { s -> if (!_perVideo.value) apply(s.current, s.skipHdr) }
        }
    }

    private fun apply(f: VideoFilter, skipHdr: Boolean = store.value.skipHdr) {
        _filter.value = f
        engine.setFilter(f, skipHdr)
    }

    /** Called with each video's saved values: a per-video filter wins over the profile's. */
    fun onMediaStarted(prefs: MediaPrefs) {
        val own = prefs.filter
        _perVideo.value = own != null
        apply(own ?: store.value.current)
    }

    fun set(f: VideoFilter) {
        val v = f.clamped()
        apply(v)
        if (_perVideo.value) {
            extras.remember { it.copy(filter = v) }
        } else {
            scope.launch { repo.updateFilterStore(pid.value) { it.copy(current = v) } }
        }
    }

    fun reset() = set(VideoFilter())

    /** Keeps the filter for this video only, or returns to the profile's filter. */
    fun setPerVideo(on: Boolean) {
        if (on == _perVideo.value) return
        _perVideo.value = on
        if (on) {
            extras.remember { it.copy(filter = _filter.value) }
        } else {
            extras.remember { it.copy(filter = null) }
            apply(store.value.current)
        }
    }

    fun setSkipHdr(on: Boolean) = scope.launch {
        repo.updateFilterStore(pid.value) { it.copy(skipHdr = on) }
        engine.setFilter(_filter.value, on)
    }

    fun savePreset(name: String) = scope.launch {
        val n = name.trim().take(40).ifBlank { return@launch }
        repo.updateFilterStore(pid.value) { it.withPreset(FilterPreset(n, _filter.value)) }
    }

    fun deletePreset(name: String) = scope.launch { repo.updateFilterStore(pid.value) { it.withoutPreset(name) } }

    fun applyPreset(p: FilterPreset) = set(p.filter)

    fun setAudioDelay(ms: Int) = engine.audioDelay.setDelay(ms)
}
