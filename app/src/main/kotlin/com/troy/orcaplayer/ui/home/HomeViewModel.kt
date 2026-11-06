package com.troy.orcaplayer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.HomeRow
import com.troy.orcaplayer.sources.api.PageRequest
import com.troy.orcaplayer.ui.common.RowState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/** Home rows come only from installed sources: no made-up rows (17.1). */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val hub: ContentHub,
    private val addons: AddonRepository,
) : ViewModel() {
    val rows: StateFlow<List<HomeRow>> = combine(addons.list, hub.profile) { l, p -> hub.homeRows(l, p) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hasAddons: StateFlow<Boolean> = combine(addons.list, hub.profile) { l, p -> l.addons.any { it.activeFor(p) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _states = MutableStateFlow<Map<String, RowState>>(emptyMap())
    val states: StateFlow<Map<String, RowState>> = _states.asStateFlow()

    private val next = HashMap<String, PageRequest?>()
    private val loading = HashSet<String>()
    // A few rows at a time, so a long Home doesn't open dozens of connections at once.
    private val gate = Semaphore(4)

    init {
        viewModelScope.launch {
            rows.collect { list ->
                val keys = list.map { it.key }.toSet()
                _states.update { m -> m.filterKeys { it in keys } }
                list.forEach { if (it.key !in _states.value) load(it, reset = true) }
            }
        }
    }

    fun loadMore(row: HomeRow) = load(row, reset = false)

    fun refresh() {
        viewModelScope.launch {
            runCatching { addons.refreshManifests(force = true) }
            rows.value.forEach { load(it, reset = true) }
        }
    }

    private fun load(row: HomeRow, reset: Boolean) {
        val key = row.key
        if (key in loading) return
        val page = if (reset) PageRequest() else next[key] ?: return
        loading += key
        _states.update { it + (key to (it[key] ?: RowState()).copy(loading = true, error = null)) }
        viewModelScope.launch {
            val filters = row.genre?.let { mapOf("genre" to it) }.orEmpty()
            val result = gate.withPermit { runCatching { hub.page(row, filters, page) } }
            loading -= key
            result.onSuccess { p ->
                next[key] = p.next
                _states.update { m ->
                    val old = if (reset) emptyList() else m[key]?.items.orEmpty()
                    val merged = (old + p.items).distinctBy { it.type.name + it.id }
                    m + (key to RowState(merged, loading = false, error = null, hasMore = p.next != null && merged.size > old.size))
                }
            }.onFailure { e ->
                _states.update { m -> m + (key to (m[key] ?: RowState()).copy(loading = false, error = e.message ?: "?", hasMore = false)) }
            }
        }
    }
}
