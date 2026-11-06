package com.troy.orcaplayer.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.LibraryItemEntity
import com.troy.orcaplayer.core.data.LibraryShowEntity
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.EpisodeInfo
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.settings.ExtrasSettings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.library.Library
import com.troy.orcaplayer.library.MatchOptions
import com.troy.orcaplayer.library.ScanState
import com.troy.orcaplayer.library.TmdbResult
import com.troy.orcaplayer.sources.local.LocalKeys
import com.troy.orcaplayer.sources.network.ShareSessions
import com.troy.orcaplayer.sources.network.ShareUris
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** A series as the library shows it: the show and its episodes in order. */
data class LibraryShow(val show: LibraryShowEntity, val episodes: List<LibraryItemEntity>) {
    val seasons: List<Int> get() = episodes.mapNotNull { it.season }.distinct().sorted()
    val lastAdded: Long get() = episodes.maxOfOrNull { it.addedAt } ?: 0
}

/** A movie collection from TMDB ("The Matrix Collection"). */
data class LibraryCollection(val id: Int, val name: String, val movies: List<LibraryItemEntity>)

data class LibraryContent(
    val movies: List<LibraryItemEntity> = emptyList(),
    val shows: List<LibraryShow> = emptyList(),
    val collections: List<LibraryCollection> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val library: Library,
    private val settingsRepo: SettingsRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    val settings: StateFlow<SettingsValues> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsValues.Defaults)

    val scan: StateFlow<ScanState?> = library.scan

    val watched: StateFlow<Map<String, Long>> = profiles.currentProfileId
        .flatMapLatest { library.watched(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val content: StateFlow<LibraryContent> = combine(library.items, library.shows) { items, shows ->
        val movies = items.filter { it.kind == Library.KIND_MOVIE }
        val byShow = items.filter { it.kind == Library.KIND_EPISODE }.groupBy { it.showKey }
        val showList = shows.mapNotNull { s ->
            val eps = byShow[s.showKey].orEmpty().sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.fileName }))
            if (eps.isEmpty()) null else LibraryShow(s, eps)
        }
        val collections = movies.filter { it.collectionId != null }.groupBy { it.collectionId!! }
            .filter { it.value.size > 1 }
            .map { (id, list) -> LibraryCollection(id, list.first().collectionName.orEmpty(), list.sortedBy { it.year ?: 0 }) }
            .sortedBy { it.name.lowercase() }
        LibraryContent(movies, showList, collections, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryContent())

    init {
        viewModelScope.launch {
            val v = settingsRepo.values(profiles.currentProfileId.value).first()
            if (v[ExtrasSettings.LibraryAutoScan] && !autoScanned) {
                autoScanned = true
                library.scan(options(v))
            }
        }
    }

    fun options(v: SettingsValues = settings.value): MatchOptions {
        val lang = when (val l = v[ExtrasSettings.LibraryLanguage]) {
            "app" -> context.resources.configuration.locales[0]?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()
            else -> l
        }
        return MatchOptions(online = v[ExtrasSettings.LibraryOnline], key = v[ExtrasSettings.TmdbKey].trim(), language = lang)
    }

    fun rescan() { viewModelScope.launch { library.scan(options()) } }

    fun rematch() { viewModelScope.launch { runCatching { library.match(options(), force = true) } } }

    fun clearError() = library.clearError()

    fun setSort(key: String) {
        viewModelScope.launch { settingsRepo.set(profiles.currentProfileId.value, ExtrasSettings.LibrarySort, key) }
    }

    fun setWatched(ids: List<String>, watched: Boolean) {
        viewModelScope.launch { library.setWatched(profiles.currentProfileId.value, ids, watched) }
    }

    suspend fun search(query: String, year: Int?, series: Boolean): List<TmdbResult> = library.search(options(), query, year, series)

    fun fixMovie(itemId: String, tmdbId: Int) { viewModelScope.launch { runCatching { library.fixMovie(options(), itemId, tmdbId) } } }
    fun fixShow(showKey: String, tmdbId: Int) { viewModelScope.launch { runCatching { library.fixShow(options(), showKey, tmdbId) } } }
    fun editMovie(itemId: String, title: String, year: Int?) { viewModelScope.launch { library.editMovie(itemId, title, year) } }
    fun editShow(showKey: String, name: String, year: Int?) { viewModelScope.launch { library.editShow(showKey, name, year) } }
    fun makeMovie(itemId: String) { viewModelScope.launch { library.setKind(itemId, null, null, null) } }
    fun makeEpisode(itemId: String, show: String, season: Int?, episode: Int?) {
        viewModelScope.launch { library.setKind(itemId, show, season, episode) }
    }

    /** A movie plays alone; an episode plays with the rest of its show queued after it. */
    suspend fun request(item: LibraryItemEntity, queue: List<LibraryItemEntity> = listOf(item)): PlayRequest = withContext(Dispatchers.IO) {
        val index = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        val from = (index - 50).coerceAtLeast(0)
        val window = queue.subList(from, (index + 50).coerceAtMost(queue.size))
        PlayRequest(window.map { playItem(it) }, index - from, profiles.currentProfileId.value)
    }

    private fun playItem(e: LibraryItemEntity): PlayItem {
        val isLocal = e.origin == Library.ORIGIN_LOCAL
        val key = if (isLocal) {
            LocalKeys.forUri(context.contentResolver, Uri.parse(e.uri), e.uri) ?: MediaKeys.forLink(e.uri)
        } else {
            MediaKeys.forSource("share:" + e.origin, ShareUris.parse(e.uri)?.second ?: e.uri)
        }
        val source = if (isLocal) SourceRef(SourceKind.LOCAL, "local")
        else SourceRef(SourceKind.NETWORK_SHARE, e.origin, ShareSessions.get(context).store[e.origin]?.name ?: e.origin)
        val isEpisode = e.kind == Library.KIND_EPISODE
        return PlayItem(
            uri = e.uri,
            title = if (isEpisode) e.title else e.title + (e.year?.let { " ($it)" } ?: ""),
            key = key,
            source = source,
            subtitle = if (isEpisode) episodeLabel(e) else null,
            episode = if (isEpisode) EpisodeInfo(titleId = e.showKey.orEmpty(), episodeId = e.id, season = e.season, number = e.episode, name = e.episodeTitle) else null,
            canSave = true,
            poster = e.poster,
            titleType = if (isEpisode) "series" else "movie",
            externalIds = buildMap {
                e.tmdbId?.let { put("tmdb", it.toString()) }
                if (!isEpisode) e.imdbId?.let { put("imdb", it) }
            },
        )
    }

    companion object {
        @Volatile private var autoScanned = false

        fun episodeLabel(e: LibraryItemEntity): String =
            listOfNotNull("S${e.season ?: 1} E${e.episode ?: "?"}", e.episodeTitle).joinToString(" · ")
    }
}
