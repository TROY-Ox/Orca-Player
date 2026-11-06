package com.troy.orcaplayer.core.settings

import androidx.annotation.StringRes

/** Audio and video quality options (Phase 3). Every one of them is listed in Settings. */
object AudioVideoSettings {
    private fun choice(
        key: String, section: SettingsSection, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?,
        default: String, vararg choices: Pair<String, Int>,
    ) = ChoiceSetting(key, section, title, summary, keywords, default, choices.map { Choice(it.first, it.second) })

    private fun bool(key: String, section: SettingsSection, @StringRes title: Int, @StringRes summary: Int?, @StringRes keywords: Int?, default: Boolean) =
        BoolSetting(key, section, title, summary, keywords, default)

    private val A = SettingsSection.AUDIO
    private val V = SettingsSection.VIDEO

    // Audio

    val EqMode = choice(
        "audio.eq_mode", A, R.string.setting_eq_mode, R.string.setting_eq_mode_summary, R.string.setting_eq_keywords, "simple",
        "simple" to R.string.eq_mode_simple, "advanced" to R.string.eq_mode_advanced,
    )
    val ExtraVolume = bool("audio.extra_volume", A, R.string.setting_extra_volume, R.string.setting_extra_volume_summary, R.string.setting_boost_keywords, false)
    val MaxBoost = choice(
        "audio.max_boost", A, R.string.setting_max_boost, R.string.setting_max_boost_summary, R.string.setting_boost_keywords, "200",
        "150" to R.string.percent_150, "200" to R.string.percent_200, "300" to R.string.percent_300,
    )
    val Limiter = choice(
        "audio.limiter", A, R.string.setting_limiter, R.string.setting_limiter_summary, R.string.setting_boost_keywords, "medium",
        "soft" to R.string.limiter_soft, "medium" to R.string.limiter_medium, "strong" to R.string.limiter_strong,
    )
    val BoostWarning = bool("audio.boost_warning", A, R.string.setting_boost_warning, R.string.setting_boost_warning_summary, R.string.setting_boost_keywords, true)
    val Normalize = bool("audio.normalize", A, R.string.setting_normalize, R.string.setting_normalize_summary, R.string.setting_normalize_keywords, false)
    val QuietRecording = bool("audio.quiet_recording", A, R.string.setting_quiet_recording, R.string.setting_quiet_recording_summary, R.string.setting_normalize_keywords, false)
    val AutoApply = bool("audio.auto_apply", A, R.string.setting_eq_auto_apply, R.string.setting_eq_auto_apply_summary, R.string.setting_eq_keywords, true)
    val LevelMeter = bool("audio.level_meter", A, R.string.setting_level_meter, R.string.setting_level_meter_summary, R.string.setting_eq_keywords, true)
    val Passthrough = bool("audio.passthrough", A, R.string.setting_passthrough, R.string.setting_passthrough_summary, R.string.setting_passthrough_keywords, false)
    val SpatialAudio = bool("audio.spatial", A, R.string.setting_spatial, R.string.setting_spatial_summary, R.string.setting_spatial_keywords, true)

    // Video

    val FrameRateMatch = choice(
        "video.frame_rate", V, R.string.setting_frame_rate, R.string.setting_frame_rate_summary, R.string.setting_frame_rate_keywords, "always",
        "always" to R.string.frame_rate_always, "seamless" to R.string.frame_rate_seamless, "off" to R.string.frame_rate_off,
    )
    val DolbyVisionWarning = bool("video.dv_warning", V, R.string.setting_dv_warning, R.string.setting_dv_warning_summary, R.string.setting_hdr_keywords, true)
    val ToneMapping = choice(
        "video.tone_mapping", V, R.string.setting_tone_mapping, R.string.setting_tone_mapping_summary, R.string.setting_hdr_keywords, "system",
        "system" to R.string.tone_system, "reinhard" to R.string.tone_reinhard, "filmic" to R.string.tone_filmic,
    )
    val ToneBrightness = choice(
        "video.tone_brightness", V, R.string.setting_tone_brightness, R.string.setting_tone_brightness_summary, R.string.setting_hdr_keywords, "0",
        "-20" to R.string.brightness_minus_20, "-10" to R.string.brightness_minus_10, "0" to R.string.brightness_0,
        "10" to R.string.brightness_plus_10, "20" to R.string.brightness_plus_20,
    )
    val InfoOverlay = bool("video.info_overlay", V, R.string.setting_info_overlay, R.string.setting_info_overlay_summary, R.string.setting_info_keywords, false)

    val all: List<SettingDef<*>> = listOf(
        EqMode, ExtraVolume, MaxBoost, Limiter, BoostWarning, Normalize, QuietRecording, AutoApply, LevelMeter, Passthrough, SpatialAudio,
        FrameRateMatch, DolbyVisionWarning, ToneMapping, ToneBrightness, InfoOverlay,
    )
}
