package com.troy.orcaplayer.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.LinksRepository
import com.troy.orcaplayer.data.SearchResults
import com.troy.orcaplayer.sources.api.PageRequest
import com.troy.orcaplayer.sources.local.LocalKeys
import com.troy.orcaplayer.sources.local.LocalMedia
import com.troy.orcaplayer.sources.local.LocalMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One search box for local files, the library and every source that can search (14.4). */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val hub: ContentHub,
    private val addons: AddonRepository,
    private val links: LinksRepository,
    private val local: LocalMediaRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    val query = MutableStateFlow("")

    val results: StateFlow<SearchResults> = query
        .map { it.trim() }
        .debounce(350)
        .distinctUntilChanged()
        .flatMapLatest { q -> if (q.length < 2) flow { emit(SearchResults()) } else hub.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    private var localCache: List<LocalMedia>? = null

    val localResults: StateFlow<List<LocalMedia>> = query
        .map { it.trim() }
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.length < 2) {
                    emit(emptyList())
                } else {
                    val all = localCache ?: runCatching { local.scan() }.getOrDefault(emptyList()).also { localCache = it }
                    emit(all.filter { it.name.contains(q, ignoreCase = true) || it.folderName.contains(q, ignoreCase = true) }.take(30))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<String>> = links.data.map { it.searches }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _trending = MutableStateFlow<List<Title>>(emptyList())
    val trending: StateFlow<List<Title>> = _trending.asStateFlow()

    val typeFilter = MutableStateFlow<TitleType?>(null)
    val sourceFilter = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            addons.list.map { it.trending }.distinctUntilChanged().collect { key ->
                _trending.value = emptyList()
                val (addonId, catalogKey) = key?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return@collect
                val row = hub.discoverCatalogs().firstOrNull { it.addonId == addonId && it.decl.key == catalogKey } ?: return@collect
                _trending.value = runCatching { hub.page(row, emptyMap(), PageRequest()).items.take(20) }.getOrDefault(emptyList())
            }
        }
    }

    fun submit() {
        val q = query.value.trim()
        if (q.length >= 2) links.addSearch(q)
    }

    fun clearRecent() = links.clearSearches()

    suspend fun playLocal(m: LocalMedia): PlayRequest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val uri = Uri.parse(m.uri)
        PlayRequest(
            listOf(
                PlayItem(
                    uri = m.uri,
                    title = m.name.substringBeforeLast('.'),
                    key = LocalKeys.forUri(context.contentResolver, uri, m.uri) ?: MediaKeys.forLink(m.uri),
                    source = SourceRef(SourceKind.LOCAL, "local"),
                    subtitle = m.folderName,
                    canSave = true,
                ),
            ),
            0,
            profiles.currentProfileId.value,
        )
    }
}
