package com.troy.orcaplayer.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.KidsRules
import com.troy.orcaplayer.core.model.PinLock
import com.troy.orcaplayer.core.model.Profile
import com.troy.orcaplayer.core.settings.Settings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.HomeRow
import com.troy.orcaplayer.sources.local.LocalMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Avatars to pick from: plain signifiers, drawn with the theme's icons. */
val AVATARS = listOf("orca", "star", "face", "pets", "rocket", "game", "music", "heart", "leaf", "ball")

/** The accents a profile can choose (the theme's ten; custom colours stay in Settings). */
val PROFILE_ACCENTS = Settings.Accent.choices.filter { it.value != Settings.CUSTOM_ACCENT }

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val repo: ProfileRepository,
    private val settingsRepo: SettingsRepository,
    private val hub: ContentHub,
    private val addons: AddonRepository,
    private val local: LocalMediaRepository,
) : ViewModel() {
    val profiles: StateFlow<List<Profile>> = repo.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val current: StateFlow<String> = repo.currentProfileId
    val currentProfile: StateFlow<Profile?> = combine(repo.profiles, repo.currentProfileId) { l, id -> l.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Accent of each profile, for its avatar. */
    suspend fun accentOf(id: String): String = settingsRepo.values(id).first()[Settings.Accent]

    fun pinWaitLeft(id: String): Long = repo.pinWaitLeft(id)

    suspend fun checkPin(id: String, pin: String): Boolean = repo.checkPin(id, pin)

    suspend fun checkParentPin(pin: String): Boolean = repo.checkParentPin(pin)

    suspend fun parentHasPin(): Boolean = repo.parentHasPin()

    /** Shortest wait among the parent profiles, for the "try again in" message. */
    fun parentWaitLeft(): Long = profiles.value.filter { !it.isKids && it.hasPin }.minOfOrNull { repo.pinWaitLeft(it.id) } ?: 0

    fun switchTo(id: String, done: () -> Unit = {}) {
        viewModelScope.launch {
            repo.switchTo(id)
            done()
        }
    }

    fun save(
        existing: Profile?, name: String, avatar: String, accent: String, isKids: Boolean, kids: KidsRules,
        newPin: String?, removePin: Boolean, done: (Profile) -> Unit,
    ) {
        viewModelScope.launch {
            val p = if (existing == null) {
                repo.create(name, avatar, isKids, kids)
            } else {
                existing.copy(name = name.trim().ifEmpty { existing.name }, avatar = avatar, isKids = isKids && existing.id != Profile.DEFAULT_ID, kids = kids)
                    .also { repo.update(it) }
            }
            when {
                newPin != null && PinLock.isValid(newPin) -> repo.setPin(p.id, newPin)
                removePin -> repo.setPin(p.id, null)
            }
            settingsRepo.set(p.id, Settings.Accent, accent)
            done(p)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun move(id: String, by: Int) {
        val ids = profiles.value.map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        val j = i + by
        if (i < 0 || j !in ids.indices) return
        ids.add(j, ids.removeAt(i))
        viewModelScope.launch { repo.reorder(ids) }
    }

    /** Every catalog of every add-on that is safe for children, for choosing what Kids may see. */
    fun kidsCatalogs(): List<HomeRow> = hub.discoverCatalogs(addons.list.value, null).filter { row -> addons.addon(row.addonId)?.adult != true }

    suspend fun folders(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching { local.scan().map { it.folderId to it.folderName }.distinct().sortedBy { it.second.lowercase() } }.getOrDefault(emptyList())
    }
}
