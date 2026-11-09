package com.troy.orcaplayer.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.UriSchemes
import com.troy.orcaplayer.data.LiveChannel
import com.troy.orcaplayer.data.LivePlaylist
import com.troy.orcaplayer.data.LiveTvData
import com.troy.orcaplayer.data.LiveTvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the channel list shows: one group of one playlist (or all), filtered by the search box. */
data class LiveFilter(val playlistId: String? = null, val group: String? = null, val query: String = "")

data class LiveUi(
    val playlists: List<LivePlaylist> = emptyList(),
    val groups: List<String> = emptyList(),
    val channels: List<LiveChannel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val total: Int = 0,
)

const val GROUP_FAVORITES = "\u0000fav"
const val GROUP_RECENT = "\u0000recent"

@OptIn(FlowPreview::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repo: LiveTvRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    val filter = MutableStateFlow(LiveFilter())
    val data: StateFlow<LiveTvData> = repo.data

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val ui: StateFlow<LiveUi> = combine(repo.data, repo.channels, filter.debounce(120)) { d, all, f ->
        val lists = d.playlists
        val inScope = (if (f.playlistId == null) lists.flatMap { all[it.id].orEmpty() } else all[f.playlistId].orEmpty())
            .distinctBy { it.url }
        val groups = inScope.mapNotNull { it.group }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val fav = d.favorites.toSet()
        val byGroup = when (f.group) {
            null -> inScope
            GROUP_FAVORITES -> d.favorites.mapNotNull { u -> inScope.firstOrNull { it.url == u } }
            GROUP_RECENT -> d.recent.mapNotNull { u -> inScope.firstOrNull { it.url == u } }
            else -> inScope.filter { it.group == f.group }
        }
        val q = f.query.trim().lowercase(Locale.ROOT)
        val shown = if (q.isEmpty()) byGroup else byGroup.filter { it.name.lowercase(Locale.ROOT).contains(q) || it.group?.lowercase(Locale.ROOT)?.contains(q) == true }
        LiveUi(lists, groups, shown, fav, inScope.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUi())

    init {
        viewModelScope.launch { runCatching { repo.loadAll() } }
    }

    fun add(name: String, url: String, userAgent: String? = null) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            runCatching { repo.add(name, url, userAgent) }
                .onSuccess { p -> filter.value = LiveFilter(playlistId = p.id); _message.value = null }
                .onFailure { _message.value = it.message ?: it.javaClass.simpleName }
            _busy.value = false
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _busy.value = true
            val failed = repo.data.value.playlists.mapNotNull { p -> runCatching { repo.refresh(p.id) }.exceptionOrNull()?.let { p.name } }
            _message.value = failed.takeIf { it.isNotEmpty() }?.joinToString(", ")
            _busy.value = false
        }
    }

    fun remove(id: String) {
        repo.remove(id)
        if (filter.value.playlistId == id) filter.value = LiveFilter()
    }

    fun rename(id: String, name: String) = repo.rename(id, name)

    fun toggleFavorite(c: LiveChannel) = repo.toggleFavorite(c.url)

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * Plays [channel] with the channels around it in the current list as the queue, so Next and
     * Previous change channel.
     */
    fun play(channel: LiveChannel): PlayRequest? {
        val list = ui.value.channels.filter { UriSchemes.isAllowed(it.url) }
        if (!UriSchemes.isAllowed(channel.url)) return null
        repo.markWatched(channel.url)
        val i = list.indexOfFirst { it.url == channel.url }.coerceAtLeast(0)
        val from = (i - 100).coerceAtLeast(0)
        val window = list.subList(from, (i + 101).coerceAtMost(list.size)).ifEmpty { listOf(channel) }
        val items = window.map { c ->
            PlayItem(
                uri = c.url,
                title = c.name,
                key = MediaKeys.forLink(c.url),
                source = SourceRef(SourceKind.LINK, "live", "Live TV"),
                headers = c.entry.headers,
                subtitle = c.group,
                poster = c.entry.logo,
                titleType = "TV",
                // Live streams have no end and no resume point.
                startPositionMs = 0,
                downloadable = false,
            )
        }
        val start = window.indexOfFirst { it.url == channel.url }.coerceAtLeast(0)
        return PlayRequest(items, start, profiles.currentProfileId.value)
    }
}
