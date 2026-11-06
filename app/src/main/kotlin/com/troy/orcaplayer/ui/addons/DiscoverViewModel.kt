package com.troy.orcaplayer.ui.addons

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.HomeRow
import com.troy.orcaplayer.sources.api.PageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverState(
    val row: HomeRow? = null,
    val filters: Map<String, String> = emptyMap(),
    val items: List<Title> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    /** A required extra (such as a genre) must be chosen before the catalog can load (14.1). */
    val needsChoice: Boolean = false,
)

/** Any catalog as a full grid, with its genre and other options (14.3). */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val hub: ContentHub,
    addons: AddonRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val catalogs: StateFlow<List<HomeRow>> = combine(addons.list, hub.profile) { l, p -> hub.discoverCatalogs(l, p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, hub.discoverCatalogs())

    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    private var next: PageRequest? = null
    private var job: Job? = null

    init {
        val addonId = saved.get<String>("addon")?.takeIf { it.isNotEmpty() }
        val key = saved.get<String>("catalog")?.takeIf { it.isNotEmpty() }
        val first = catalogs.value.firstOrNull { it.addonId == addonId && it.decl.key == key } ?: catalogs.value.firstOrNull()
        if (first != null) select(first)
    }

    fun select(row: HomeRow) {
        val filters = buildMap {
            (row.genre ?: row.decl.requiredExtras.firstOrNull { it.name == "genre" }?.options?.firstOrNull())?.let { put("genre", it) }
            row.decl.requiredExtras.filter { it.name != "genre" && it.name != "search" }.forEach { x -> x.options.firstOrNull()?.let { put(x.name, it) } }
        }
        _state.value = DiscoverState(row = row, filters = filters)
        reload()
    }

    fun setFilter(name: String, value: String?) {
        _state.update { s -> s.copy(filters = if (value == null) s.filters - name else s.filters + (name to value)) }
        reload()
    }

    private fun reload() {
        next = PageRequest()
        _state.update { it.copy(items = emptyList(), hasMore = true, error = null) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        val row = s.row ?: return
        val page = next ?: return
        if (s.loading) return
        val missing = row.decl.requiredExtras.any { it.name != "search" && s.filters[it.name].isNullOrEmpty() }
        if (missing) {
            _state.update { it.copy(needsChoice = true, hasMore = false) }
            return
        }
        _state.update { it.copy(loading = true, needsChoice = false) }
        job?.cancel()
        job = viewModelScope.launch {
            val result = runCatching { hub.page(row, s.filters, page) }
            if (_state.value.row?.key != row.key || _state.value.filters != s.filters) return@launch
            result.onSuccess { p ->
                next = p.next
                _state.update { st ->
                    val merged = (st.items + p.items).distinctBy { it.type.name + it.id }
                    st.copy(items = merged, loading = false, hasMore = p.next != null && merged.size > st.items.size)
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "?", hasMore = false) }
            }
        }
    }
}
