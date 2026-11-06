package com.troy.orcaplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.NavLayout
import com.troy.orcaplayer.core.model.ToolsLayout
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.SourceSettings
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.system.SystemSignals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    profiles: ProfileRepository,
    private val addons: AddonRepository,
    private val links: com.troy.orcaplayer.data.LinksRepository,
) : ViewModel() {
    val profileId: StateFlow<String> = profiles.currentProfileId

    val settings: StateFlow<SettingsValues?> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val navLayout: StateFlow<NavLayout> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.navLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavLayout.Default)

    val toolsLayout: StateFlow<ToolsLayout> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.toolsLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ToolsLayout.Default)

    fun setToolsLayout(layout: ToolsLayout) {
        viewModelScope.launch { settingsRepo.setToolsLayout(profileId.value, layout) }
    }

    /** A manifest link that arrived from outside (stremio:// or a share), waiting for the install preview. */
    val pendingInstall = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            settings.collect { v -> v?.let { addons.defaultTimeoutSec = it[SourceSettings.AddonTimeout].toIntOrNull() ?: 10 } }
        }
        // Re-check manifests on start, at most once a day (15.4).
        viewModelScope.launch { runCatching { addons.refreshManifests(force = false) } }
    }

    /** A link from the clipboard offer, as a PlayRequest; it goes into the link history. */
    fun playLink(url: String): com.troy.orcaplayer.core.model.PlayRequest? {
        if (!com.troy.orcaplayer.core.model.UriSchemes.isAllowed(url)) return null
        val limit = settings.value?.get(SourceSettings.HistoryLength)?.toIntOrNull() ?: 50
        links.addHistory(com.troy.orcaplayer.data.HistoryEntry(url), limit)
        val uri = android.net.Uri.parse(url)
        return com.troy.orcaplayer.core.model.PlayRequest(
            listOf(
                com.troy.orcaplayer.core.model.PlayItem(
                    uri = url,
                    title = uri.lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: uri.host ?: url,
                    key = com.troy.orcaplayer.core.model.MediaKeys.forLink(url),
                    source = com.troy.orcaplayer.core.model.SourceRef(com.troy.orcaplayer.core.model.SourceKind.LINK, "link"),
                    subtitle = uri.host,
                ),
            ),
            0,
            profileId.value,
        )
    }

    private val _signals = MutableStateFlow(SystemSignals())
    val signals: StateFlow<SystemSignals> = _signals.asStateFlow()

    fun updateSignals(s: SystemSignals) {
        _signals.value = s
    }
}
