package com.troy.orcaplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.NavLayout
import com.troy.orcaplayer.core.model.ToolsLayout
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
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

    private val _signals = MutableStateFlow(SystemSignals())
    val signals: StateFlow<SystemSignals> = _signals.asStateFlow()

    fun updateSignals(s: SystemSignals) {
        _signals.value = s
    }
}
