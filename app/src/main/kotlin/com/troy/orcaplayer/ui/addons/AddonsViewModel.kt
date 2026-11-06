package com.troy.orcaplayer.ui.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.data.AddonList
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.Health
import com.troy.orcaplayer.data.HealthState
import com.troy.orcaplayer.data.InstalledAddon
import com.troy.orcaplayer.sources.stremio.AddonManifest
import com.troy.orcaplayer.sources.stremio.CatalogDecl
import com.troy.orcaplayer.sources.stremio.ManifestException
import com.troy.orcaplayer.sources.stremio.StremioParse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AddonFilter { ALL, ADDONS, EXTENSIONS, ENABLED, DISABLED, ERRORS, UPDATES }

sealed interface PreviewState {
    data object Idle : PreviewState
    data class Loading(val input: String) : PreviewState
    data class Ready(val url: String, val manifest: AddonManifest, val installed: InstalledAddon?) : PreviewState
    data class Failed(val input: String, val reason: ManifestException.Reason?, val detail: String?) : PreviewState
}

data class DirectoryState(
    val addonName: String = "",
    val loading: Boolean = false,
    val items: List<StremioParse.AddonListing> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class AddonsViewModel @Inject constructor(
    private val repo: AddonRepository,
    private val hub: ContentHub,
) : ViewModel() {
    val list: StateFlow<AddonList> = repo.list
    val health: StateFlow<Map<String, Health>> = repo.health

    private val _preview = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    private val _undo = MutableStateFlow<List<AddonRepository.Removed>>(emptyList())
    /** The last removal, so it can be undone (15.2). */
    val undo: StateFlow<List<AddonRepository.Removed>> = _undo.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _directory = MutableStateFlow<DirectoryState?>(null)
    val directory: StateFlow<DirectoryState?> = _directory.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun matches(a: InstalledAddon, filter: AddonFilter, query: String): Boolean {
        val h = health.value[a.id]
        val ok = when (filter) {
            AddonFilter.ALL -> true
            AddonFilter.ADDONS -> !com.troy.orcaplayer.data.ExtensionBridge.handles(a.manifestUrl)
            AddonFilter.EXTENSIONS -> com.troy.orcaplayer.data.ExtensionBridge.handles(a.manifestUrl)
            AddonFilter.ENABLED -> a.enabled
            AddonFilter.DISABLED -> !a.enabled
            AddonFilter.ERRORS -> a.lastError != null || h?.state == HealthState.FAILING
            AddonFilter.UPDATES -> a.updatedFrom != null
        }
        val q = query.trim()
        return ok && (q.isEmpty() || a.manifest.name.contains(q, true) || a.manifest.description?.contains(q, true) == true || a.manifestUrl.contains(q, true))
    }

    fun openPreview(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        _preview.value = PreviewState.Loading(text)
        viewModelScope.launch {
            _preview.value = try {
                val (url, m) = repo.preview(text)
                PreviewState.Ready(url, m, repo.addon(m.id))
            } catch (e: ManifestException) {
                PreviewState.Failed(text, e.reason, e.detail)
            } catch (e: Exception) {
                PreviewState.Failed(text, null, e.message)
            }
        }
    }

    fun closePreview() {
        _preview.value = PreviewState.Idle
    }

    fun install(ready: PreviewState.Ready) {
        repo.install(ready.url, ready.manifest)
        _preview.value = PreviewState.Idle
    }

    fun setEnabled(ids: Set<String>, enabled: Boolean) = repo.setEnabled(ids, enabled)

    fun remove(ids: Set<String>) {
        _undo.value = repo.remove(ids)
    }

    fun undoRemove() {
        repo.restore(_undo.value)
        _undo.value = emptyList()
    }

    fun dismissUndo() {
        _undo.value = emptyList()
    }

    fun move(from: Int, to: Int) = repo.move(from, to)

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            runCatching { repo.refreshManifests(force = true) }
            _refreshing.value = false
        }
    }

    fun exportJson(): String = repo.export()

    fun import(text: String) {
        viewModelScope.launch {
            val n = runCatching { repo.import(text) }.getOrDefault(0)
            _message.value = n.toString()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Opens an addon directory's list (addon_catalog, 15.5). */
    fun openDirectory(addon: InstalledAddon, catalog: CatalogDecl) {
        _directory.value = DirectoryState(addonName = catalog.name, loading = true)
        viewModelScope.launch {
            val result = runCatching { repo.client(addon).addonCatalog(catalog) }
            _directory.value = DirectoryState(
                addonName = catalog.name,
                loading = false,
                items = result.getOrDefault(emptyList()),
                error = result.exceptionOrNull()?.message,
            )
        }
    }

    fun closeDirectory() {
        _directory.value = null
    }

    fun installListing(item: StremioParse.AddonListing) {
        val m = item.manifest
        if (m != null) {
            _preview.value = PreviewState.Ready(item.transportUrl, m, repo.addon(m.id))
        } else {
            openPreview(item.transportUrl)
        }
    }
}
