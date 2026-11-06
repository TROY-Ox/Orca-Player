package com.troy.orcaplayer.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.MyListEntity
import com.troy.orcaplayer.core.data.MyListRepository
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.data.ResumePositionEntity
import com.troy.orcaplayer.core.data.ResumeRepository
import com.troy.orcaplayer.core.model.ContinueWatching
import com.troy.orcaplayer.core.model.MediaKey
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.MyListKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.core.model.UriSchemes
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.downloads.Downloads
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One Continue Watching entry with its progress. */
data class ContinueItem(val entry: ResumePositionEntity, val progress: Float) {
    val key: String get() = entry.mediaKey
    val name: String get() = entry.title
}

/** Where a Continue Watching or My List item opens: the player, or the title page that starts streams. */
sealed interface OpenTarget {
    data class Play(val request: PlayRequest) : OpenTarget
    data class Details(val type: String, val id: String, val play: String?) : OpenTarget
}

enum class MyListSort { CUSTOM, ADDED, NAME, YEAR }

/** My List, Continue Watching and watched marks, shared by Home, the preview card and the details page (17.4, 17.5). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val resume: ResumeRepository,
    private val myListRepo: MyListRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val profileId get() = profiles.currentProfileId.value

    val settings: StateFlow<SettingsValues> = profiles.currentProfileId.flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsValues.Defaults)

    val myList: StateFlow<List<MyListEntity>> = profiles.currentProfileId.flatMapLatest { myListRepo.items(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val myKeys: StateFlow<Set<String>> = myList.map { l -> l.map { it.itemKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val continueWatching: StateFlow<List<ContinueItem>> = combine(
        profiles.currentProfileId.flatMapLatest { resume.visible(it) },
        settings,
    ) { list, v ->
        val threshold = (v[PlayerSettings.WatchedThreshold].toIntOrNull() ?: 90) / 100.0
        // One entry per series: its most recent episode.
        list.filter { ContinueWatching.shouldShow(it.positionMs, it.durationMs, threshold) }
            .distinctBy { it.titleId?.let { t -> "t:$t" } ?: it.mediaKey }
            .map { ContinueItem(it, ContinueWatching.progress(it.positionMs, it.durationMs)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Card progress bars for movies: "TYPE/id" to how far along. */
    val titleProgress: StateFlow<Map<String, Float>> = profiles.currentProfileId.flatMapLatest { resume.all(it) }.map { list ->
        list.filter { it.titleType != null && it.titleId != null && it.episodeId == null && it.durationMs > 0 }
            .associate { "${it.titleType}/${it.titleId}" to ContinueWatching.progress(it.positionMs, it.durationMs) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun inList(title: Title): Boolean = MyListKeys.forTitle(title.type, title.id) in myKeys.value

    fun toggle(title: Title) {
        val key = MyListKeys.forTitle(title.type, title.id)
        viewModelScope.launch {
            if (key in myKeys.value) {
                myListRepo.remove(profileId, key)
            } else {
                myListRepo.add(
                    MyListEntity(
                        profileId = profileId, itemKey = key, name = title.name, titleType = title.type.name, titleId = title.id,
                        sourceId = title.source.sourceId, sourceName = title.source.sourceName, poster = title.poster,
                        background = title.background, year = title.year, addedAt = System.currentTimeMillis(),
                        knownEpisodes = if (title.type == TitleType.SERIES && title.episodes.isNotEmpty()) title.episodes.size else -1,
                        lastEpisodeId = title.episodes.lastOrNull()?.id,
                    ),
                )
            }
        }
    }

    fun remove(key: String) {
        viewModelScope.launch { myListRepo.remove(profileId, key) }
    }

    fun reorder(keys: List<String>) {
        viewModelScope.launch { myListRepo.reorder(profileId, keys) }
    }

    fun sorted(list: List<MyListEntity>, sort: MyListSort): List<MyListEntity> = when (sort) {
        MyListSort.CUSTOM -> list
        MyListSort.ADDED -> list.sortedByDescending { it.addedAt }
        MyListSort.NAME -> list.sortedBy { it.name.lowercase() }
        MyListSort.YEAR -> list.sortedByDescending { it.year ?: 0 }
    }

    /** Marks a movie watched, so it leaves Continue Watching and shows as finished. */
    fun markWatched(title: Title) {
        viewModelScope.launch {
            resume.markWatched(profileId, MediaKeys.forSource(ContentHub.RESUME_SOURCE, title.id, null).id, title.name)
        }
    }

    fun removeFromRow(item: ContinueItem) {
        viewModelScope.launch { resume.hide(profileId, item.key) }
    }

    fun titleOf(item: ContinueItem): Title? {
        val e = item.entry
        val type = e.titleType?.let { runCatching { TitleType.valueOf(it) }.getOrNull() } ?: return null
        val id = e.titleId ?: return null
        return Title(id, SourceRef(SourceKind.STREMIO_ADDON, e.sourceKind ?: "source"), type, e.title, poster = e.poster)
    }

    fun titleOf(item: MyListEntity): Title? {
        val type = item.titleType?.let { runCatching { TitleType.valueOf(it) }.getOrNull() } ?: return null
        val id = item.titleId ?: return null
        return Title(
            id, SourceRef(SourceKind.STREMIO_ADDON, item.sourceId ?: "source", item.sourceName ?: item.sourceId ?: "source"), type, item.name,
            poster = item.poster, background = item.background, year = item.year,
        )
    }

    /** Continue Watching: a finished download plays offline; files and links play; source titles reopen their streams. */
    fun open(item: ContinueItem): OpenTarget? {
        val e = item.entry
        val downloaded = Downloads.store(context).items.firstOrNull { it.meta.mediaKeyId == e.mediaKey && it.status == DownloadStatus.COMPLETED }
        downloaded?.let { d -> Downloads.playItem(d)?.let { return OpenTarget.Play(PlayRequest(listOf(it), 0, profileId)) } }
        if (e.titleId != null && e.titleType != null && e.sourceKind != SourceKind.LOCAL.name && e.sourceKind != SourceKind.LINK.name) {
            return OpenTarget.Details(e.titleType!!, e.titleId!!, e.episodeId ?: PLAY_MOVIE)
        }
        val uri = e.path ?: return null
        if (!UriSchemes.isAllowed(uri)) return null
        val key = keyFrom(e.mediaKey, uri)
        val local = e.sourceKind == SourceKind.LOCAL.name || uri.startsWith("content:") || uri.startsWith("file:")
        val play = PlayItem(
            uri = uri, title = e.title, key = key,
            source = if (local) SourceRef(SourceKind.LOCAL, "local") else SourceRef(SourceKind.LINK, "link"),
            subtitle = e.subtitle, canSave = local,
        )
        return OpenTarget.Play(PlayRequest(listOf(play), 0, profileId))
    }

    fun open(item: MyListEntity): OpenTarget? {
        if (item.titleType != null && item.titleId != null) return OpenTarget.Details(item.titleType!!, item.titleId!!, null)
        val uri = item.uri?.takeIf { UriSchemes.isAllowed(it) } ?: return null
        val key = keyFrom(item.itemKey.removePrefix("media:"), uri)
        return OpenTarget.Play(PlayRequest(listOf(PlayItem(uri, item.name, key, SourceRef(SourceKind.LINK, "link"))), 0, profileId))
    }

    /** Rebuilds a media key from its stored ID, so resume finds the same position. */
    private fun keyFrom(id: String, uri: String): MediaKey {
        if (id.startsWith("local:")) {
            val parts = id.split(':')
            val size = parts.getOrNull(1)?.toLongOrNull()
            val hash = parts.getOrNull(2)
            if (size != null && hash != null) return MediaKey.LocalFile(size, hash, uri)
        }
        if (id.startsWith("link:")) return MediaKey.Link(id.removePrefix("link:"))
        return MediaKeys.forLink(uri)
    }

    companion object {
        /** The "play" route value for a movie (no episode). */
        const val PLAY_MOVIE = "movie"
    }
}
