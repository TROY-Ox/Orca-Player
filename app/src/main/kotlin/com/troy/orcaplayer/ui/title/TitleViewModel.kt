package com.troy.orcaplayer.ui.title

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.data.ResumeRepository
import com.troy.orcaplayer.core.model.Episode
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.StreamKind
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.SourceSettings
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.StreamEntry
import com.troy.orcaplayer.data.StreamsState
import com.troy.orcaplayer.sources.api.StreamSort
import com.troy.orcaplayer.ui.common.TitleMemory
import com.troy.orcaplayer.ui.player.SoundController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StreamPicker(
    val episode: Episode?,
    val state: StreamsState = StreamsState(pending = 1),
    val sort: StreamSort = StreamSort.QUALITY,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
)

sealed interface TitleEvent {
    data class Play(val request: PlayRequest) : TitleEvent
    data class Open(val url: String, val youtube: Boolean) : TitleEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TitleViewModel @Inject constructor(
    private val hub: ContentHub,
    private val settingsRepo: SettingsRepository,
    private val profiles: ProfileRepository,
    resume: ResumeRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val type: TitleType = runCatching { TitleType.valueOf(saved.get<String>("type").orEmpty()) }.getOrDefault(TitleType.MOVIE)
    val id: String = saved.get<String>("id").orEmpty()

    private val _title = MutableStateFlow(TitleMemory.get(type.name, id))
    val title: StateFlow<Title?> = _title.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _season = MutableStateFlow<Int?>(null)
    val season: StateFlow<Int?> = _season.asStateFlow()

    private val _picker = MutableStateFlow<StreamPicker?>(null)
    val picker: StateFlow<StreamPicker?> = _picker.asStateFlow()

    private val _events = MutableStateFlow<TitleEvent?>(null)
    val events: StateFlow<TitleEvent?> = _events.asStateFlow()

    val settings: StateFlow<SettingsValues> = profiles.currentProfileId.flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsValues.Defaults)

    /** Progress of each episode (or the movie), 0..1, from saved resume positions. */
    val progress: StateFlow<Map<String, Float>> = profiles.currentProfileId.flatMapLatest { resume.all(it) }
        .map { list ->
            list.filter { it.durationMs > 0 }.associate { it.mediaKey to (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var streamJob: Job? = null

    init {
        load()
    }

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            val merged = runCatching { hub.details(type, id, _title.value) }.getOrNull()
            if (merged != null) _title.value = merged
            _loading.value = false
            val seasons = seasons()
            if (_season.value == null) _season.value = seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull()
        }
    }

    fun seasons(): List<Int> = _title.value?.episodes?.map { it.season ?: 0 }?.distinct()?.sorted().orEmpty()

    fun selectSeason(s: Int) {
        _season.value = s
    }

    fun progressKey(episode: Episode?): String = MediaKeys.forSource(ContentHub.RESUME_SOURCE, id, episode?.id).id

    /** Opens the stream picker for the movie or an episode, gathering streams from every source (14.6). */
    fun openStreams(episode: Episode?) {
        val v = settings.value
        val sort = runCatching { StreamSort.valueOf(v[SourceSettings.StreamSort].uppercase()) }.getOrDefault(StreamSort.QUALITY)
        val titleFolder = SoundController.titleFolder(ContentHub.RESUME_SOURCE, id)
        _picker.value = StreamPicker(episode, sort = sort)
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val prefs = settingsRepo.mediaPrefs(profiles.currentProfileId.value).first().perFolder[titleFolder]
            _picker.update { it?.copy(audioLanguage = prefs?.audioLanguage, subtitleLanguage = prefs?.subtitleLanguage) }
            val videoId = episode?.id ?: id
            hub.streams(type, videoId).collect { st ->
                _picker.update { it?.copy(state = st) }
                if (st.pending == 0 && v[SourceSettings.AutoPick] == "auto") {
                    ranked().firstOrNull { it.playable }?.let { play(it) }
                }
            }
        }
    }

    fun closeStreams() {
        streamJob?.cancel()
        _picker.value = null
    }

    fun setSort(sort: StreamSort) = _picker.update { it?.copy(sort = sort) }

    /** Remembers the title's audio and subtitle language; applied as track preferences when the stream loads. */
    fun setLanguages(audio: String?, subtitle: String?) {
        _picker.update { it?.copy(audioLanguage = audio, subtitleLanguage = subtitle) }
        val folder = SoundController.titleFolder(ContentHub.RESUME_SOURCE, id)
        viewModelScope.launch {
            settingsRepo.updateMediaPrefs(profiles.currentProfileId.value) { store ->
                store.updateFolder(folder) { it.copy(audioLanguage = audio, subtitleLanguage = subtitle) }
            }
        }
    }

    fun ranked(): List<StreamEntry> {
        val p = _picker.value ?: return emptyList()
        val v = settings.value
        val showUnsupported = v[SourceSettings.ShowUnsupported]
        val quality = v[SourceSettings.PreferredQuality].toIntOrNull()
        val lang = p.audioLanguage
        val entries = p.state.entries.filter { it.playable || showUnsupported || it.option.kind != StreamKind.TORRENT && it.option.kind != StreamKind.UNSUPPORTED }
        val (good, bad) = hub.rank(entries, p.sort, quality, lang).partition {
            it.playable || it.option.kind == StreamKind.EXTERNAL || it.option.kind == StreamKind.YOUTUBE
        }
        return good + bad
    }

    fun play(entry: StreamEntry) {
        val t = _title.value ?: return
        val p = _picker.value ?: return
        when (entry.option.kind) {
            StreamKind.URL -> {
                if (!entry.playable) return
                val v = settings.value
                val request = hub.request(
                    t, p.episode, entry, ranked(), profiles.currentProfileId.value,
                    SourceSettings.parseHeaders(v[SourceSettings.DefaultHeaders]),
                    maxAlternates = (v[SourceSettings.FallbackRetries].toIntOrNull() ?: 3) + 2,
                )
                streamJob?.cancel()
                _picker.value = null
                _events.value = TitleEvent.Play(request)
            }
            StreamKind.EXTERNAL -> _events.value = TitleEvent.Open(entry.option.url, youtube = false)
            StreamKind.YOUTUBE -> _events.value = TitleEvent.Open(entry.option.url, youtube = true)
            else -> Unit
        }
    }

    fun consumeEvent() {
        _events.value = null
    }

    /** The next episode after [e], for "next episode" from the details screen. */
    fun nextEpisode(e: Episode): Episode? {
        val list = _title.value?.episodes.orEmpty()
        val i = list.indexOfFirst { it.id == e.id }
        return list.getOrNull(i + 1)
    }
}
