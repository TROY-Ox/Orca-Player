package com.troy.orcaplayer.core.settings

import androidx.annotation.StringRes

/** Library and extras (Phase 9). Every one of them is listed in Settings. */
object ExtrasSettings {
    private fun choice(
        key: String, section: SettingsSection, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?,
        default: String, vararg choices: Pair<String, Int>,
    ) = ChoiceSetting(key, section, title, summary, keywords, default, choices.map { Choice(it.first, it.second) })

    private fun bool(key: String, section: SettingsSection, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?, default: Boolean) =
        BoolSetting(key, section, title, summary, keywords, default)

    private val P = SettingsSection.PLAYER
    private val L = SettingsSection.LIBRARY
    private val S = SettingsSection.SOURCES

    // Mini player and floating window (20.5)
    val MiniPlayer = bool("player.mini_player", P, R.string.setting_mini_player, R.string.setting_mini_player_summary, R.string.setting_mini_keywords, true)
    val FloatingWindow = bool("player.floating", P, R.string.setting_floating, R.string.setting_floating_summary, R.string.setting_mini_keywords, false)
    val FloatingSize = choice(
        "player.floating_size", P, R.string.setting_floating_size, null, R.string.setting_mini_keywords, "medium",
        "small" to R.string.size_small, "medium" to R.string.size_medium, "large" to R.string.size_large,
    )
    val FloatingCorner = choice(
        "player.floating_corner", P, R.string.setting_floating_corner, null, R.string.setting_mini_keywords, "bottom_end",
        "top_start" to R.string.corner_top_start, "top_end" to R.string.corner_top_end,
        "bottom_start" to R.string.corner_bottom_start, "bottom_end" to R.string.corner_bottom_end,
    )

    // libVLC engine (20.8)
    val VlcFallback = bool("player.vlc_fallback", P, R.string.setting_vlc_fallback, R.string.setting_vlc_fallback_summary, R.string.setting_vlc_keywords, true)

    // Media library (20.1)
    val LibraryEnabled = bool("library.enabled", L, R.string.setting_library, R.string.setting_library_summary, R.string.setting_library_keywords, true)
    val LibraryAutoScan = bool("library.auto_scan", L, R.string.setting_library_auto_scan, R.string.setting_library_auto_scan_summary, R.string.setting_library_keywords, true)
    val LibraryOnline = bool("library.online", L, R.string.setting_library_online, R.string.setting_library_online_summary, R.string.setting_tmdb_keywords, true)
    val TmdbKey = TextSetting(
        "library.tmdb_key", L, R.string.setting_tmdb_key, R.string.setting_tmdb_key_summary, R.string.setting_tmdb_keywords,
        secret = true, scope = SettingScope.GLOBAL,
    )
    val LibraryLanguage = choice(
        "library.language", L, R.string.setting_library_language, R.string.setting_library_language_summary, R.string.setting_tmdb_keywords, "app",
        "app" to R.string.library_language_app, "en" to R.string.language_english, "ar" to R.string.language_arabic,
    )
    val LibrarySort = choice(
        "library.sort", L, R.string.setting_library_sort, null, R.string.setting_library_keywords, "added",
        "added" to R.string.sort_added, "title" to R.string.sort_title, "year" to R.string.sort_year,
        "rating" to R.string.sort_rating, "watched" to R.string.sort_last_watched,
    )

    // Chromecast (20.7)
    val CastEnabled = bool("sources.cast", S, R.string.setting_cast, R.string.setting_cast_summary, R.string.setting_cast_keywords, true)

    // Trakt (20.9)
    val TraktClientId = TextSetting(
        "sources.trakt_client_id", S, R.string.setting_trakt_client_id, R.string.setting_trakt_client_summary, R.string.setting_trakt_keywords,
        secret = true, scope = SettingScope.GLOBAL,
    )
    val TraktClientSecret = TextSetting(
        "sources.trakt_client_secret", S, R.string.setting_trakt_secret, R.string.setting_trakt_client_summary, R.string.setting_trakt_keywords,
        secret = true, scope = SettingScope.GLOBAL,
    )
    val TraktScrobble = bool("sources.trakt_scrobble", S, R.string.setting_trakt_scrobble, R.string.setting_trakt_scrobble_summary, R.string.setting_trakt_keywords, true)

    val all: List<SettingDef<*>> = listOf(
        MiniPlayer, FloatingWindow, FloatingSize, FloatingCorner, VlcFallback,
        LibraryEnabled, LibraryAutoScan, LibraryOnline, TmdbKey, LibraryLanguage, LibrarySort,
        CastEnabled, TraktClientId, TraktClientSecret, TraktScrobble,
    )
}
