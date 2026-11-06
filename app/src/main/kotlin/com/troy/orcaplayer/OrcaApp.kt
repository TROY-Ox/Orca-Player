package com.troy.orcaplayer

import android.app.Application
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.settings.DownloadSettings
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.SourceSettings
import com.troy.orcaplayer.data.DownloadPlanner
import com.troy.orcaplayer.downloads.Downloads
import com.troy.orcaplayer.downloads.logic.DownloadConfig
import com.troy.orcaplayer.downloads.logic.DownloadPlace
import com.troy.orcaplayer.downloads.logic.SmartDelete
import com.troy.orcaplayer.player.engine.PlaybackEngine
import com.troy.orcaplayer.tools.JobsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class OrcaApp : Application() {
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var planner: DownloadPlanner
    @Inject lateinit var addons: com.troy.orcaplayer.data.AddonRepository

    private fun processName(): String = if (android.os.Build.VERSION.SDK_INT >= 28) {
        getProcessName()
    } else {
        runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault(packageName)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        // The extended build's ":extensions" process only runs extension code (19.3).
        if (processName().endsWith(":extensions")) return
        com.troy.orcaplayer.flavor.Flavor.init(this, addons)
        JobsRepository.init(this)
        Downloads.bridge = planner
        registerSchemes()
        appScope.launch { runCatching { com.troy.orcaplayer.trakt.Trakt.get(this@OrcaApp).load() } }
        appScope.launch {
            profiles.ensureDefaultProfile(getString(R.string.profiles_default_name))
        }
        // New-episode alerts follow their (all-profiles) setting (17.8).
        appScope.launch {
            profiles.currentProfileId.flatMapLatest { settings.values(it) }
                .map { it[com.troy.orcaplayer.core.settings.BrowsingSettings.NewEpisodeAlerts].toIntOrNull() }
                .distinctUntilChanged()
                .collect { hours -> runCatching { com.troy.orcaplayer.data.NewEpisodeAlerts.schedule(this@OrcaApp, hours) } }
        }
        // Downloader options follow Settings (16.8); downloads that were running continue.
        appScope.launch {
            var started = false
            profiles.currentProfileId.flatMapLatest { settings.values(it) }.collect { v ->
                val agent = v[SourceSettings.UserAgent].ifBlank { PlaybackEngine.DEFAULT_USER_AGENT }
                Downloads.userAgent = agent
                planner.extraHeaders = SourceSettings.parseHeaders(v[SourceSettings.DefaultHeaders])
                planner.subtitleLanguage = when (val l = v[PlayerSettings.SubtitleLanguage]) {
                    "off" -> null
                    "auto" -> Locale.getDefault().language.takeIf { it == "ar" || it == "en" } ?: "en"
                    else -> l
                }
                Downloads.configure(this@OrcaApp, downloadConfig(v))
                if (!started) {
                    started = true
                    Downloads.init(this@OrcaApp)
                }
            }
        }
    }

    private fun downloadConfig(v: SettingsValues): DownloadConfig {
        val folder = v[DownloadSettings.Folder].ifBlank { null }
        val place = when (v[DownloadSettings.Place]) {
            DownloadSettings.PLACE_PRIVATE -> DownloadPlace.PRIVATE
            DownloadSettings.PLACE_FOLDER -> if (folder != null) DownloadPlace.FOLDER else DownloadPlace.PUBLIC
            else -> DownloadPlace.PUBLIC
        }
        return DownloadConfig(
            parallel = v[DownloadSettings.Parallel].toIntOrNull() ?: 2,
            multiConnection = v[DownloadSettings.MultiConnection],
            speedLimit = v[DownloadSettings.SpeedLimit].toLongOrNull() ?: 0,
            wifiOnly = v[DownloadSettings.WifiOnly],
            chargingOnly = v[DownloadSettings.ChargingOnly],
            startMinute = v[DownloadSettings.StartTime].toIntOrNull(),
            maxRetries = v[DownloadSettings.Retries].toIntOrNull() ?: 5,
            reserveBytes = (v[DownloadSettings.Reserve].toLongOrNull() ?: 500) * 1024 * 1024,
            notifyFinished = v[DownloadSettings.Notify],
            place = place,
            folderUri = folder,
            movieTemplate = v[DownloadSettings.MovieTemplate],
            episodeTemplate = v[DownloadSettings.EpisodeTemplate],
            saveSubtitles = v[DownloadSettings.SaveSubtitles],
            smartDelete = when (v[DownloadSettings.SmartDelete]) {
                "off" -> SmartDelete.OFF
                "auto" -> SmartDelete.AUTO
                else -> SmartDelete.ASK
            },
            smartKeep = v[DownloadSettings.SmartKeep].toIntOrNull() ?: 2,
        )
    }

    /** The private folder plays through its own decrypting source, never the stream cache (20.4). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun registerSchemes() {
        com.troy.orcaplayer.player.engine.CustomSchemes.register(
            com.troy.orcaplayer.vault.Vault.SCHEME,
            com.troy.orcaplayer.vault.VaultDataSource.Factory(com.troy.orcaplayer.vault.Vault.get(this)),
        )
        // Network shares read through their own seekable sources, logged in once per connection (20.2).
        val shares = com.troy.orcaplayer.sources.network.ShareDataSource.Factory(com.troy.orcaplayer.sources.network.ShareSessions.get(this))
        com.troy.orcaplayer.sources.network.ShareDataSource.schemes.forEach { com.troy.orcaplayer.player.engine.CustomSchemes.register(it, shares) }
    }
}
