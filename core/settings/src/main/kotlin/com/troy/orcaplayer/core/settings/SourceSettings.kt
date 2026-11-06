package com.troy.orcaplayer.core.settings

import androidx.annotation.StringRes

/** URL player, addons, the stream picker, fallback and online subtitles (Phase 5). */
object SourceSettings {
    private fun choice(
        key: String, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?,
        default: String, vararg choices: Pair<String, Int>, scope: SettingScope = SettingScope.PROFILE,
    ) = ChoiceSetting(key, SettingsSection.SOURCES, title, summary, keywords, default, choices.map { Choice(it.first, it.second) }, scope)

    private fun bool(key: String, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?, default: Boolean) =
        BoolSetting(key, SettingsSection.SOURCES, title, summary, keywords, default)

    // URL player (13)
    val ClipboardDetect = bool("sources.clipboard", R.string.setting_clipboard, R.string.setting_clipboard_summary, R.string.setting_link_keywords, true)
    val UserAgent = TextSetting("sources.user_agent", SettingsSection.SOURCES, R.string.setting_user_agent, R.string.setting_user_agent_summary, R.string.setting_link_keywords)
    val DefaultHeaders = TextSetting(
        "sources.headers", SettingsSection.SOURCES, R.string.setting_default_headers, R.string.setting_default_headers_summary,
        R.string.setting_link_keywords, multiline = true,
    )
    val BufferSeconds = choice(
        "sources.buffer", R.string.setting_buffer, R.string.setting_buffer_summary, R.string.setting_link_keywords, "50",
        "15" to R.string.seconds_15, "30" to R.string.seconds_30, "50" to R.string.seconds_50, "120" to R.string.seconds_120,
        scope = SettingScope.GLOBAL,
    )
    val CacheSize = choice(
        "sources.cache", R.string.setting_stream_cache, R.string.setting_stream_cache_summary, R.string.setting_link_keywords, "200",
        "0" to R.string.cache_off, "100" to R.string.mb_100, "200" to R.string.mb_200, "500" to R.string.mb_500, "1000" to R.string.mb_1000,
        scope = SettingScope.GLOBAL,
    )
    val HistoryLength = choice(
        "sources.history", R.string.setting_history_length, R.string.setting_history_length_summary, R.string.setting_link_keywords, "50",
        "0" to R.string.history_off, "25" to R.string.count_25, "50" to R.string.count_50, "100" to R.string.count_100, "200" to R.string.count_200,
    )

    // Addons (14, 15)
    val AddonTimeout = choice(
        "sources.timeout", R.string.setting_addon_timeout, R.string.setting_addon_timeout_summary, R.string.setting_addon_keywords, "10",
        "5" to R.string.seconds_5, "10" to R.string.seconds_10, "20" to R.string.seconds_20, "30" to R.string.seconds_30,
        scope = SettingScope.GLOBAL,
    )
    val ShowUnsupported = bool("sources.show_unsupported", R.string.setting_show_unsupported, R.string.setting_show_unsupported_summary, R.string.setting_addon_keywords, false)

    // Stream picker (14.6)
    val StreamSort = choice(
        "sources.stream_sort", R.string.setting_stream_sort, R.string.setting_stream_sort_summary, R.string.setting_stream_keywords, "quality",
        "quality" to R.string.sort_quality, "size_largest" to R.string.sort_size_largest, "size_smallest" to R.string.sort_size_smallest,
        "language" to R.string.sort_language, "source" to R.string.sort_source,
    )
    val PreferredQuality = choice(
        "sources.quality", R.string.setting_preferred_quality, R.string.setting_preferred_quality_summary, R.string.setting_stream_keywords, "best",
        "best" to R.string.quality_best, "2160" to R.string.quality_2160, "1080" to R.string.quality_1080, "720" to R.string.quality_720, "480" to R.string.quality_480,
    )
    val AutoPick = choice(
        "sources.auto_pick", R.string.setting_auto_pick, R.string.setting_auto_pick_summary, R.string.setting_stream_keywords, "ask",
        "ask" to R.string.auto_pick_ask, "auto" to R.string.auto_pick_auto,
    )

    // Fallback (14.7)
    val Fallback = bool("sources.fallback", R.string.setting_fallback, R.string.setting_fallback_summary, R.string.setting_fallback_keywords, true)
    val FallbackRetries = choice(
        "sources.fallback_retries", R.string.setting_fallback_retries, null, R.string.setting_fallback_keywords, "3",
        "1" to R.string.count_1, "2" to R.string.count_2, "3" to R.string.count_3, "5" to R.string.count_5,
    )
    val StallSeconds = choice(
        "sources.stall", R.string.setting_stall, R.string.setting_stall_summary, R.string.setting_fallback_keywords, "15",
        "8" to R.string.seconds_8, "15" to R.string.seconds_15, "30" to R.string.seconds_30,
    )

    // Online subtitles (9.6)
    val SubtitleAutoDownload = BoolSetting(
        "subtitles.online_auto", SettingsSection.SUBTITLES, R.string.setting_online_subs_auto, R.string.setting_online_subs_auto_summary,
        R.string.setting_online_subs_keywords, true,
    )
    val OpenSubtitlesKey = TextSetting(
        "subtitles.opensubtitles_key", SettingsSection.SUBTITLES, R.string.setting_opensubtitles_key, R.string.setting_opensubtitles_key_summary,
        R.string.setting_online_subs_keywords, secret = true, scope = SettingScope.GLOBAL,
    )

    val all: List<SettingDef<*>> = listOf(
        ClipboardDetect, UserAgent, DefaultHeaders, BufferSeconds, CacheSize, HistoryLength,
        AddonTimeout, ShowUnsupported, StreamSort, PreferredQuality, AutoPick,
        Fallback, FallbackRetries, StallSeconds, SubtitleAutoDownload, OpenSubtitlesKey,
    )

    /** "Name: value" lines into a header map. Lines without a colon are skipped. */
    fun parseHeaders(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }?.let { it to line.substring(i + 1).trim() }
        }
        .toMap()
}
