package com.troy.orcaplayer.ui.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.data.ResumeRepository
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.settings.SettingDef
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.sources.local.LocalKeys
import com.troy.orcaplayer.sources.local.LocalMedia
import com.troy.orcaplayer.sources.local.LocalMediaRepository
import com.troy.orcaplayer.sources.local.Thumbnails
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class MediaAccess { FULL, PARTIAL, NONE }

/** Saved progress for a list item: fraction watched (0..1), or null when never played. */
data class Progress(val fraction: Float)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repo: LocalMediaRepository,
    val thumbnails: Thumbnails,
    resume: ResumeRepository,
    private val settingsRepo: SettingsRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    private val _access = MutableStateFlow(readAccess())
    val access: StateFlow<MediaAccess> = _access

    /** Null while the first scan runs. */
    private val folderTick = MutableStateFlow(0)

    val media: StateFlow<List<LocalMedia>?> = combine(_access, folderTick) { a, _ -> a }
        .flatMapLatest { a -> if (a == MediaAccess.NONE && repo.trees().isEmpty()) flowOf(emptyList()) else repo.observe() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress: StateFlow<Map<String, Progress>> = profiles.currentProfileId
        .flatMapLatest { resume.all(it) }
        .map { list ->
            list.filter { it.path != null && it.durationMs > 0 }
                .associate { it.path!! to Progress((it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val settings: StateFlow<SettingsValues> = profiles.currentProfileId
        .flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsValues.Defaults)

    fun refreshAccess() {
        _access.value = readAccess()
    }

    fun <T> set(def: SettingDef<T>, value: T) {
        viewModelScope.launch { settingsRepo.set(profiles.currentProfileId.value, def, value) }
    }

    fun addFolder(tree: Uri) {
        repo.addTree(tree)
        folderTick.value++
    }

    /** Builds a queue from the visible list (up to 100 videos around the one tapped). */
    suspend fun request(list: List<LocalMedia>, index: Int): PlayRequest = withContext(Dispatchers.IO) {
        val from = (index - 50).coerceAtLeast(0)
        val to = (index + 50).coerceAtMost(list.size)
        val window = list.subList(from, to)
        val items = window.map { m ->
            val uri = Uri.parse(m.uri)
            PlayItem(
                uri = m.uri,
                title = m.name.substringBeforeLast('.'),
                key = LocalKeys.forUri(context.contentResolver, uri, m.uri) ?: MediaKeys.forLink(m.uri),
                source = SourceRef(SourceKind.LOCAL, "local"),
                subtitle = m.folderName,
                canSave = true,
            )
        }
        PlayRequest(items, index - from, profiles.currentProfileId.value)
    }

    private fun readAccess(): MediaAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.FULL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
            else -> MediaAccess.NONE
        }
    }

    companion object {
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
