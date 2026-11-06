package com.troy.orcaplayer.core.settings

import androidx.annotation.StringRes

/** Player, gesture, subtitle and library options (Phase 2). Every one of them is listed in Settings. */
object PlayerSettings {
    private fun choice(
        key: String, section: SettingsSection, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?,
        default: String, vararg choices: Pair<String, Int>,
    ) = ChoiceSetting(key, section, title, summary, keywords, default, choices.map { Choice(it.first, it.second) })

    private fun bool(key: String, section: SettingsSection, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?, default: Boolean) =
        BoolSetting(key, section, title, summary, keywords, default)

    private val P = SettingsSection.PLAYER
    private val G = SettingsSection.GESTURES
    private val S = SettingsSection.SUBTITLES
    private val L = SettingsSection.LIBRARY

    // Player

    val Decoder = choice(
        "player.decoder", P, R.string.setting_decoder, R.string.setting_decoder_summary, R.string.setting_decoder_keywords, "hw_plus",
        "hw_plus" to R.string.decoder_hw_plus, "hw" to R.string.decoder_hw, "sw" to R.string.decoder_sw,
    )
    val DefaultSpeed = choice(
        "player.default_speed", P, R.string.setting_default_speed, null, R.string.setting_speed_keywords, "1.0",
        "0.5" to R.string.speed_0_5, "0.75" to R.string.speed_0_75, "1.0" to R.string.speed_1, "1.25" to R.string.speed_1_25,
        "1.5" to R.string.speed_1_5, "2.0" to R.string.speed_2,
    )
    val Resume = choice(
        "player.resume", P, R.string.setting_resume, R.string.setting_resume_summary, R.string.setting_resume_keywords, "auto",
        "auto" to R.string.resume_auto, "ask" to R.string.resume_ask, "off" to R.string.resume_off,
    )
    val WatchedThreshold = choice(
        "player.watched_threshold", P, R.string.setting_watched, R.string.setting_watched_summary, R.string.setting_resume_keywords, "90",
        "80" to R.string.percent_80, "90" to R.string.percent_90, "95" to R.string.percent_95,
    )
    val AutoNext = bool("player.auto_next", P, R.string.setting_auto_next, R.string.setting_auto_next_summary, R.string.setting_queue_keywords, true)
    val BackgroundPlay = bool("player.background", P, R.string.setting_background, R.string.setting_background_summary, R.string.setting_background_keywords, false)
    val Pip = choice(
        "player.pip", P, R.string.setting_pip, R.string.setting_pip_summary, R.string.setting_pip_keywords, "playing",
        "always" to R.string.pip_always, "playing" to R.string.pip_playing, "never" to R.string.pip_never,
    )
    val PlayerOrientation = choice(
        "player.orientation", P, R.string.setting_player_orientation, R.string.setting_orientation_summary, R.string.setting_orientation_keywords, "auto",
        "auto" to R.string.orientation_video, "landscape" to R.string.orientation_landscape,
        "portrait" to R.string.orientation_portrait, "system" to R.string.orientation_system,
    )
    val MenuOrientation = choice(
        "player.menu_orientation", P, R.string.setting_menu_orientation, R.string.setting_orientation_summary, R.string.setting_orientation_keywords, "system",
        "system" to R.string.orientation_system, "portrait" to R.string.orientation_portrait, "landscape" to R.string.orientation_landscape,
    )
    val LockBlocksKeys = bool("player.lock_keys", P, R.string.setting_lock_keys, R.string.setting_lock_keys_summary, R.string.setting_lock_keywords, false)
    val ShowRemaining = bool("player.show_remaining", P, R.string.setting_show_remaining, R.string.setting_show_remaining_summary, null, false)

    // Gestures and sliders

    val SkipSeconds = choice(
        "gestures.skip_seconds", G, R.string.setting_skip, R.string.setting_skip_summary, R.string.setting_skip_keywords, "10",
        "5" to R.string.seconds_5, "10" to R.string.seconds_10, "15" to R.string.seconds_15, "20" to R.string.seconds_20, "30" to R.string.seconds_30,
    )
    val LongPressSpeed = choice(
        "gestures.long_press_speed", G, R.string.setting_long_press, R.string.setting_long_press_summary, R.string.setting_speed_keywords, "2.0",
        "1.5" to R.string.speed_1_5, "2.0" to R.string.speed_2, "3.0" to R.string.speed_3,
    )
    val DoubleTap = bool("gestures.double_tap", G, R.string.setting_gesture_double_tap, null, R.string.setting_gesture_keywords, true)
    val SwipeSeek = bool("gestures.swipe_seek", G, R.string.setting_gesture_swipe_seek, null, R.string.setting_gesture_keywords, true)
    val SwipeSliders = bool("gestures.swipe_sliders", G, R.string.setting_gesture_swipe_sliders, null, R.string.setting_gesture_keywords, true)
    val PinchZoom = bool("gestures.pinch", G, R.string.setting_gesture_pinch, null, R.string.setting_gesture_keywords, true)
    val LongPress = bool("gestures.long_press", G, R.string.setting_gesture_long_press, null, R.string.setting_gesture_keywords, true)
    val SeekSensitivity = choice(
        "gestures.seek_sensitivity", G, R.string.setting_seek_sensitivity, null, R.string.setting_gesture_keywords, "medium",
        "low" to R.string.level_low, "medium" to R.string.level_medium, "high" to R.string.level_high,
    )
    val SliderDisplay = choice(
        "sliders.display", G, R.string.setting_slider_display, null, R.string.setting_slider_keywords, "touch",
        "always" to R.string.slider_always, "touch" to R.string.slider_touch, "hidden" to R.string.slider_hidden,
    )
    val SliderSwap = bool("sliders.swap", G, R.string.setting_slider_swap, R.string.setting_slider_swap_summary, R.string.setting_slider_keywords, false)
    val SliderMode = choice(
        "sliders.mode", G, R.string.setting_slider_mode, R.string.setting_slider_mode_summary, R.string.setting_slider_keywords, "accelerated",
        "accelerated" to R.string.slider_accelerated, "linear" to R.string.slider_linear,
    )
    val SliderAcceleration = choice(
        "sliders.acceleration", G, R.string.setting_slider_acceleration, null, R.string.setting_slider_keywords, "medium",
        "low" to R.string.level_low, "medium" to R.string.level_medium, "high" to R.string.level_high,
    )
    val SliderStep = choice(
        "sliders.step", G, R.string.setting_slider_step, R.string.setting_slider_step_summary, R.string.setting_slider_keywords, "1",
        "1" to R.string.percent_1, "2" to R.string.percent_2, "5" to R.string.percent_5,
    )
    val SliderFastMax = choice(
        "sliders.fast_max", G, R.string.setting_slider_fast_max, null, R.string.setting_slider_keywords, "medium",
        "low" to R.string.level_low, "medium" to R.string.level_medium, "high" to R.string.level_high,
    )
    val SliderHaptics = bool("sliders.haptics", G, R.string.setting_slider_haptics, R.string.setting_slider_haptics_summary, R.string.setting_slider_keywords, true)

    // Subtitles

    val SubtitleEncoding = choice(
        "subtitles.encoding", S, R.string.setting_sub_encoding, R.string.setting_sub_encoding_summary, R.string.setting_sub_encoding_keywords, "auto",
        "auto" to R.string.encoding_auto, "UTF-8" to R.string.encoding_utf8, "windows-1256" to R.string.encoding_1256,
        "ISO-8859-6" to R.string.encoding_8859_6, "UTF-16" to R.string.encoding_utf16, "windows-1252" to R.string.encoding_1252,
        "ISO-8859-1" to R.string.encoding_8859_1,
    )
    val FixPunctuation = bool("subtitles.fix_punctuation", S, R.string.setting_sub_fix_punct, R.string.setting_sub_fix_punct_summary, R.string.setting_sub_arabic_keywords, false)
    val SubtitleSize = choice(
        "subtitles.size", S, R.string.setting_sub_size, R.string.setting_sub_size_summary, R.string.setting_sub_style_keywords, "system",
        "system" to R.string.sub_size_system, "small" to R.string.size_small, "medium" to R.string.size_medium,
        "large" to R.string.size_large, "xlarge" to R.string.size_xlarge,
    )
    val SubtitleColor = choice(
        "subtitles.color", S, R.string.setting_sub_color, null, R.string.setting_sub_style_keywords, "white",
        "white" to R.string.color_white, "yellow" to R.string.accent_yellow, "accent" to R.string.color_accent,
    )
    val SubtitleOutline = bool("subtitles.outline", S, R.string.setting_sub_outline, null, R.string.setting_sub_style_keywords, true)
    val SubtitleShadow = bool("subtitles.shadow", S, R.string.setting_sub_shadow, null, R.string.setting_sub_style_keywords, true)
    val SubtitleBackground = choice(
        "subtitles.background", S, R.string.setting_sub_background, null, R.string.setting_sub_style_keywords, "none",
        "none" to R.string.sub_bg_none, "translucent" to R.string.sub_bg_translucent, "solid" to R.string.sub_bg_solid,
    )
    val SubtitleBold = bool("subtitles.bold", S, R.string.setting_sub_bold, null, R.string.setting_sub_style_keywords, false)
    val SubtitleMargin = choice(
        "subtitles.margin", S, R.string.setting_sub_margin, R.string.setting_sub_margin_summary, R.string.setting_sub_style_keywords, "medium",
        "low" to R.string.level_low, "medium" to R.string.level_medium, "high" to R.string.level_high,
    )
    val SubtitleAutoLoad = bool("subtitles.auto_load", S, R.string.setting_sub_auto_load, R.string.setting_sub_auto_load_summary, null, true)
    val SubtitleLanguage = choice(
        "subtitles.language", S, R.string.setting_sub_language, R.string.setting_sub_language_summary, R.string.setting_sub_arabic_keywords, "auto",
        "auto" to R.string.sub_lang_auto, "ar" to R.string.language_arabic, "en" to R.string.language_english, "off" to R.string.sub_lang_off,
    )

    // Local library

    val LibraryTab = choice(
        "library.tab", L, R.string.setting_library_tab, null, R.string.setting_library_keywords, "folders",
        "folders" to R.string.library_folders, "all" to R.string.library_all,
    )
    val LibraryView = choice(
        "library.view", L, R.string.setting_library_view, null, R.string.setting_library_keywords, "list",
        "list" to R.string.view_list, "grid" to R.string.view_grid,
    )
    val GridSize = choice(
        "library.grid_size", L, R.string.setting_grid_size, null, R.string.setting_library_keywords, "medium",
        "small" to R.string.size_small, "medium" to R.string.size_medium, "large" to R.string.size_large,
    )
    val LibrarySort = choice(
        "library.sort", L, R.string.setting_library_sort, null, R.string.setting_library_keywords, "name",
        "name" to R.string.sort_name, "date" to R.string.sort_date, "size" to R.string.sort_size, "length" to R.string.sort_length,
    )
    val LibraryFilter = choice(
        "library.filter", L, R.string.setting_library_filter, null, R.string.setting_library_keywords, "video",
        "video" to R.string.filter_video, "audio" to R.string.filter_audio, "all" to R.string.filter_all,
    )

    val all: List<SettingDef<*>> = listOf(
        Decoder, DefaultSpeed, Resume, WatchedThreshold, AutoNext, BackgroundPlay, Pip, PlayerOrientation, MenuOrientation,
        LockBlocksKeys, ShowRemaining,
        SkipSeconds, LongPressSpeed, DoubleTap, SwipeSeek, SwipeSliders, PinchZoom, LongPress, SeekSensitivity,
        SliderDisplay, SliderSwap, SliderMode, SliderAcceleration, SliderStep, SliderFastMax, SliderHaptics,
        SubtitleEncoding, FixPunctuation, SubtitleSize, SubtitleColor, SubtitleOutline, SubtitleShadow, SubtitleBackground,
        SubtitleBold, SubtitleMargin, SubtitleAutoLoad, SubtitleLanguage,
        LibraryTab, LibraryView, GridSize, LibrarySort, LibraryFilter,
    )
}
