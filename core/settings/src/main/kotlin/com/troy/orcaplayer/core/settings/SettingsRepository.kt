package com.troy.orcaplayer.core.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.troy.orcaplayer.core.model.AudioStore
import com.troy.orcaplayer.core.model.GestureMap
import com.troy.orcaplayer.core.model.HomeLayout
import com.troy.orcaplayer.core.model.NavLayout
import com.troy.orcaplayer.core.model.PlayerLayout
import com.troy.orcaplayer.core.model.MediaPrefsStore
import com.troy.orcaplayer.core.model.ToolsLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Snapshot of stored values for one profile. Reads fall back to defaults. */
class SettingsValues internal constructor(private val raw: Map<String, String>) {
    operator fun <T> get(def: SettingDef<T>): T = def.decode(raw[def.key])
    fun isDefault(def: SettingDef<*>): Boolean = raw[def.key] == null

    companion object {
        val Defaults = SettingsValues(emptyMap())
    }
}

object SettingsMigrations {
    /** Bump when a stored key or value format changes, and add a step below. */
    const val CURRENT_VERSION = 1

    val versionKey = intPreferencesKey("__schema_version")

    /** step(from) upgrades preferences stored at version `from` to `from + 1`. */
    private val steps: Map<Int, (MutablePreferences) -> Unit> = mapOf(
        // 0 -> 1: first versioned schema, nothing to move.
        0 to { _ -> },
    )

    internal val migration = object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            (currentData[versionKey] ?: 0) < CURRENT_VERSION

        override suspend fun migrate(currentData: Preferences): Preferences {
            val prefs = currentData.toMutablePreferences()
            var v = prefs[versionKey] ?: 0
            while (v < CURRENT_VERSION) {
                steps[v]?.invoke(prefs)
                v++
            }
            prefs[versionKey] = CURRENT_VERSION
            return prefs.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "orca_settings",
    produceMigrations = { listOf(SettingsMigrations.migration) },
)

/**
 * Settings and layout models, stored in DataStore. Profile-scoped keys are prefixed with the
 * profile ID; global keys with "global".
 */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun storageKey(profileId: String, def: SettingDef<*>) =
        stringPreferencesKey(prefix(profileId, def.scope) + def.key)

    private fun prefix(profileId: String, scope: SettingScope) =
        if (scope == SettingScope.GLOBAL) "global/" else "p/$profileId/"

    fun values(profileId: String): Flow<SettingsValues> = store.data.map { prefs ->
        val raw = buildMap {
            for (def in Settings.all) prefs[storageKey(profileId, def)]?.let { put(def.key, it) }
        }
        SettingsValues(raw)
    }

    suspend fun <T> set(profileId: String, def: SettingDef<T>, value: T) {
        store.edit { it[storageKey(profileId, def)] = def.encode(value) }
    }

    suspend fun resetSection(profileId: String, section: SettingsSection) {
        store.edit { prefs -> Settings.inSection(section).forEach { prefs.remove(storageKey(profileId, it)) } }
        // The audio reset also returns the equalizer to flat; saved presets are kept.
        if (section == SettingsSection.AUDIO) updateAudioStore(profileId) { it.copy(current = com.troy.orcaplayer.core.model.AudioFx()) }
    }

    suspend fun resetAll(profileId: String) {
        store.edit { prefs -> Settings.all.forEach { prefs.remove(storageKey(profileId, it)) } }
    }

    // Layout models. The UI is built from these from day one, so the Phase 11 editors need no rewrite.

    fun navLayout(profileId: String): Flow<NavLayout> = layout(profileId, "layout.nav", NavLayout.serializer(), NavLayout.Default)
    fun homeLayout(profileId: String): Flow<HomeLayout> = layout(profileId, "layout.home", HomeLayout.serializer(), HomeLayout.Default)
    fun playerLayout(profileId: String): Flow<PlayerLayout> = layout(profileId, "layout.player", PlayerLayout.serializer(), PlayerLayout.Default)
    fun gestureMap(profileId: String): Flow<GestureMap> = layout(profileId, "layout.gestures", GestureMap.serializer(), GestureMap.Default)
    fun toolsLayout(profileId: String): Flow<ToolsLayout> = layout(profileId, "layout.tools", ToolsLayout.serializer(), ToolsLayout.Default)

    suspend fun setNavLayout(profileId: String, value: NavLayout) = setLayout(profileId, "layout.nav", NavLayout.serializer(), value)
    suspend fun setHomeLayout(profileId: String, value: HomeLayout) = setLayout(profileId, "layout.home", HomeLayout.serializer(), value)
    suspend fun setPlayerLayout(profileId: String, value: PlayerLayout) = setLayout(profileId, "layout.player", PlayerLayout.serializer(), value)
    suspend fun setGestureMap(profileId: String, value: GestureMap) = setLayout(profileId, "layout.gestures", GestureMap.serializer(), value)
    suspend fun setToolsLayout(profileId: String, value: ToolsLayout) = setLayout(profileId, "layout.tools", ToolsLayout.serializer(), value)

    // Equalizer values, presets and per-video or per-folder choices.

    fun audioStore(profileId: String): Flow<AudioStore> = layout(profileId, "audio.store", AudioStore.serializer(), AudioStore())

    suspend fun updateAudioStore(profileId: String, change: (AudioStore) -> AudioStore) {
        val key = stringPreferencesKey("p/$profileId/audio.store")
        store.edit { prefs ->
            val now = prefs[key]?.let { runCatching { json.decodeFromString(AudioStore.serializer(), it) }.getOrNull() } ?: AudioStore()
            prefs[key] = json.encodeToString(AudioStore.serializer(), change(now))
        }
    }

    fun mediaPrefs(profileId: String): Flow<MediaPrefsStore> = layout(profileId, "media.prefs", MediaPrefsStore.serializer(), MediaPrefsStore())

    suspend fun updateMediaPrefs(profileId: String, change: (MediaPrefsStore) -> MediaPrefsStore) {
        val key = stringPreferencesKey("p/$profileId/media.prefs")
        store.edit { prefs ->
            val now = prefs[key]?.let { runCatching { json.decodeFromString(MediaPrefsStore.serializer(), it) }.getOrNull() } ?: MediaPrefsStore()
            prefs[key] = json.encodeToString(MediaPrefsStore.serializer(), change(now))
        }
    }

    fun filterStore(profileId: String): Flow<com.troy.orcaplayer.core.model.FilterStore> =
        layout(profileId, "video.filters", com.troy.orcaplayer.core.model.FilterStore.serializer(), com.troy.orcaplayer.core.model.FilterStore())

    suspend fun updateFilterStore(profileId: String, change: (com.troy.orcaplayer.core.model.FilterStore) -> com.troy.orcaplayer.core.model.FilterStore) =
        updateBlob(profileId, "video.filters", com.troy.orcaplayer.core.model.FilterStore.serializer(), com.troy.orcaplayer.core.model.FilterStore(), change)

    /** Any JSON value stored for a profile, such as library or sync state. */
    fun <T> blob(profileId: String, key: String, serializer: KSerializer<T>, default: T): Flow<T> = layout(profileId, key, serializer, default)

    suspend fun <T> updateBlob(profileId: String, key: String, serializer: KSerializer<T>, default: T, change: (T) -> T) {
        val k = stringPreferencesKey("p/$profileId/$key")
        store.edit { prefs ->
            val now = prefs[k]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default
            prefs[k] = json.encodeToString(serializer, change(now))
        }
    }

    private fun <T> layout(profileId: String, key: String, serializer: KSerializer<T>, default: T): Flow<T> =
        store.data.map { prefs ->
            prefs[stringPreferencesKey("p/$profileId/$key")]
                ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
                ?: default
        }

    private suspend fun <T> setLayout(profileId: String, key: String, serializer: KSerializer<T>, value: T) {
        store.edit { it[stringPreferencesKey("p/$profileId/$key")] = json.encodeToString(serializer, value) }
    }
}
