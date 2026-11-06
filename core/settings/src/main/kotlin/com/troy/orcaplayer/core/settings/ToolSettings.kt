package com.troy.orcaplayer.core.settings

import androidx.annotation.StringRes

/** Tools and per-video options (Phase 4). Every one of them is listed in Settings. */
object ToolSettings {
    private fun choice(
        key: String, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?,
        default: String, vararg choices: Pair<String, Int>,
    ) = ChoiceSetting(key, SettingsSection.TOOLS, title, summary, keywords, default, choices.map { Choice(it.first, it.second) })

    private fun bool(key: String, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?, default: Boolean) =
        BoolSetting(key, SettingsSection.TOOLS, title, summary, keywords, default)

    val PanelSide = choice(
        "tools.panel_side", R.string.setting_tools_side, R.string.setting_tools_side_summary, R.string.setting_tools_keywords, "end",
        "end" to R.string.side_end, "start" to R.string.side_start,
    )
    val RememberPerVideo = bool("tools.per_video", R.string.setting_per_video, R.string.setting_per_video_summary, R.string.setting_per_video_keywords, true)
    val ScreenshotFormat = choice(
        "tools.screenshot_format", R.string.setting_screenshot_format, R.string.setting_screenshot_format_summary, R.string.setting_screenshot_keywords, "png",
        "png" to R.string.format_png, "jpg" to R.string.format_jpg,
    )
    val ScreenshotSubtitles = bool("tools.screenshot_subtitles", R.string.setting_screenshot_subtitles, R.string.setting_screenshot_subtitles_summary, R.string.setting_screenshot_keywords, true)
    val ClipMode = choice(
        "tools.clip_mode", R.string.setting_clip_mode, R.string.setting_clip_mode_summary, R.string.setting_clip_keywords, "smart",
        "fast" to R.string.clip_mode_fast_name, "smart" to R.string.clip_mode_smart_name, "precise" to R.string.clip_mode_precise_name,
    )
    val SleepFade = bool("tools.sleep_fade", R.string.setting_sleep_fade, R.string.setting_sleep_fade_summary, R.string.setting_sleep_keywords, true)
    val NightDim = choice(
        "tools.night_dim", R.string.setting_night_dim, R.string.setting_night_dim_summary, R.string.setting_night_keywords, "35",
        "20" to R.string.percent_20, "35" to R.string.percent_35, "50" to R.string.percent_50,
    )
    val NightWarm = bool("tools.night_warm", R.string.setting_night_warm, R.string.setting_night_warm_summary, R.string.setting_night_keywords, true)
    val NightCompressor = bool("tools.night_compressor", R.string.setting_night_compressor, R.string.setting_night_compressor_summary, R.string.setting_night_keywords, true)
    val NightSchedule = bool("tools.night_schedule", R.string.setting_night_schedule, R.string.setting_night_schedule_summary, R.string.setting_night_keywords, false)
    val NightStart = choice(
        "tools.night_start", R.string.setting_night_start, null, R.string.setting_night_keywords, "22",
        "20" to R.string.hour_20, "21" to R.string.hour_21, "22" to R.string.hour_22, "23" to R.string.hour_23, "0" to R.string.hour_0,
    )
    val NightEnd = choice(
        "tools.night_end", R.string.setting_night_end, null, R.string.setting_night_keywords, "6",
        "5" to R.string.hour_5, "6" to R.string.hour_6, "7" to R.string.hour_7, "8" to R.string.hour_8,
    )
    /** The one-time "you must have the right to save this" reminder (3.7). Turning it off shows it again. */
    val SaveReminderSeen = BoolSetting(
        "privacy.save_reminder", SettingsSection.PRIVACY, R.string.setting_save_reminder, R.string.setting_save_reminder_summary,
        R.string.setting_saving_keywords, false,
    )

    val all: List<SettingDef<*>> = listOf(
        PanelSide, RememberPerVideo, ScreenshotFormat, ScreenshotSubtitles, ClipMode, SleepFade,
        NightDim, NightWarm, NightCompressor, NightSchedule, NightStart, NightEnd, SaveReminderSeen,
    )

    /** True when [hour] (0..23) is inside the night window, which may wrap past midnight. */
    fun inNightWindow(hour: Int, start: Int, end: Int): Boolean =
        if (start == end) false else if (start < end) hour in start until end else hour >= start || hour < end
}
