package com.troy.orcaplayer.core.settings

import androidx.annotation.StringRes

/** Home, cards, rows and the hero (17), and profile options (18). */
object BrowsingSettings {
    private val H = SettingsSection.HOME
    private val PR = SettingsSection.PROFILES

    private fun choice(
        key: String, section: SettingsSection, @StringRes title: Int, @StringRes summary: Int?, default: String,
        vararg choices: Pair<String, Int>, scope: SettingScope = SettingScope.PROFILE,
    ) = ChoiceSetting(key, section, title, summary, if (section == PR) R.string.setting_profiles_keywords else R.string.setting_home_keywords, default, choices.map { Choice(it.first, it.second) }, scope)

    private fun bool(key: String, section: SettingsSection, @StringRes title: Int, @StringRes summary: Int?, default: Boolean, scope: SettingScope = SettingScope.PROFILE) =
        BoolSetting(key, section, title, summary, if (section == PR) R.string.setting_profiles_keywords else R.string.setting_home_keywords, default, scope)

    val Hero = choice(
        "browse.hero", H, R.string.setting_hero, R.string.setting_hero_summary, "carousel",
        "carousel" to R.string.hero_carousel, "single" to R.string.hero_single, "off" to R.string.hero_off,
    )
    val HeroAutoplay = bool("browse.hero_autoplay", H, R.string.setting_hero_autoplay, R.string.setting_hero_autoplay_summary, true)
    val HeroInterval = choice(
        "browse.hero_interval", H, R.string.setting_hero_interval, null, "8",
        "5" to R.string.seconds_5, "8" to R.string.seconds_8, "12" to R.string.seconds_12,
    )
    val ContinueRow = bool("browse.row_continue", H, R.string.setting_row_continue, null, true)
    val MyListRow = bool("browse.row_mylist", H, R.string.setting_row_mylist, null, true)
    val DownloadsRow = bool("browse.row_downloads", H, R.string.setting_row_downloads, null, true)
    val CardSize = choice(
        "browse.card_size", H, R.string.setting_card_size, null, "medium",
        "small" to R.string.size_small, "medium" to R.string.size_medium, "large" to R.string.size_large,
    )
    val CardCorners = choice(
        "browse.card_corners", H, R.string.setting_card_corners, null, "default",
        "small" to R.string.corners_small, "default" to R.string.corners_default, "large" to R.string.corners_large,
    )
    val CardSpacing = choice(
        "browse.card_spacing", H, R.string.setting_card_spacing, null, "normal",
        "compact" to R.string.spacing_compact, "normal" to R.string.spacing_normal, "roomy" to R.string.spacing_roomy,
    )
    val CardShape = choice(
        "browse.card_shape", H, R.string.setting_card_shape, R.string.setting_card_shape_summary, "2:3",
        "2:3" to R.string.shape_2_3, "3:4" to R.string.shape_3_4,
    )
    val Parallax = bool("browse.parallax", H, R.string.setting_parallax, R.string.setting_parallax_summary, true)
    val AnimationSpeed = choice(
        "browse.animation_speed", H, R.string.setting_animation_speed, R.string.setting_animation_speed_summary, "normal",
        "slow" to R.string.speed_slow, "normal" to R.string.speed_normal, "fast" to R.string.speed_fast,
    )
    val NewEpisodeAlerts = choice(
        "browse.new_episodes", H, R.string.setting_new_episodes, R.string.setting_new_episodes_summary, "off",
        "off" to R.string.alerts_off, "6" to R.string.every_6h, "12" to R.string.every_12h, "24" to R.string.every_24h,
        scope = SettingScope.GLOBAL,
    )

    val AskAtStart = bool("profiles.ask_at_start", PR, R.string.setting_ask_profile, R.string.setting_ask_profile_summary, true, SettingScope.GLOBAL)
    val KidsLockPlayer = bool("profiles.kids_lock", PR, R.string.setting_kids_lock, R.string.setting_kids_lock_summary, true, SettingScope.GLOBAL)

    /** Spring response multiplier for "Animation speed". */
    fun speedFactor(value: String): Float = when (value) {
        "slow" -> 1.4f
        "fast" -> 0.7f
        else -> 1f
    }

    val all: List<SettingDef<*>> = listOf(
        Hero, HeroAutoplay, HeroInterval, ContinueRow, MyListRow, DownloadsRow,
        CardSize, CardCorners, CardSpacing, CardShape, Parallax, AnimationSpeed, NewEpisodeAlerts,
        AskAtStart, KidsLockPlayer,
    )
}
