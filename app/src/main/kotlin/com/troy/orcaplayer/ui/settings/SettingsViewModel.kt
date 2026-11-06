package com.troy.orcaplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.settings.SettingDef
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsSection
import com.troy.orcaplayer.core.settings.SettingsValues
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    val values: StateFlow<SettingsValues> = profiles.currentProfileId
        .flatMapLatest { repo.values(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsValues.Defaults)

    fun <T> set(def: SettingDef<T>, value: T) {
        viewModelScope.launch { repo.set(profiles.currentProfileId.value, def, value) }
    }

    fun reset(section: SettingsSection) {
        viewModelScope.launch { repo.resetSection(profiles.currentProfileId.value, section) }
    }
}
