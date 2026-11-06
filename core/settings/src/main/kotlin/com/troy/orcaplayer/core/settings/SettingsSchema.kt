package com.troy.orcaplayer.core.settings

import androidx.annotation.StringRes

/** Sections of the Settings screen. Each one has its own "reset to defaults". */
enum class SettingsSection(@StringRes val titleRes: Int) {
    APPEARANCE(R.string.settings_section_appearance),
    PLAYER(R.string.settings_section_player),
    GESTURES(R.string.settings_section_gestures),
    SUBTITLES(R.string.settings_section_subtitles),
    LIBRARY(R.string.settings_section_library),
    ACCESSIBILITY(R.string.settings_section_accessibility),
    LANGUAGE(R.string.settings_section_language),
    PRIVACY(R.string.settings_section_privacy),
}

/** Profile settings belong to one profile. Global settings apply to the whole app (for example the language). */
enum class SettingScope { PROFILE, GLOBAL }

data class Choice(val value: String, @StringRes val labelRes: Int)

/**
 * One user-facing option. Values are stored as strings so the stored format stays simple and
 * versioned; [SettingsMigrations] upgrades old values.
 */
sealed class SettingDef<T>(
    val key: String,
    val section: SettingsSection,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int?,
    @StringRes val keywordsRes: Int?,
    val default: T,
    val scope: SettingScope,
) {
    abstract fun decode(raw: String?): T
    abstract fun encode(value: T): String
}

class BoolSetting(
    key: String,
    section: SettingsSection,
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int? = null,
    @StringRes keywordsRes: Int? = null,
    default: Boolean,
    scope: SettingScope = SettingScope.PROFILE,
) : SettingDef<Boolean>(key, section, titleRes, summaryRes, keywordsRes, default, scope) {
    override fun decode(raw: String?): Boolean = raw?.toBooleanStrictOrNull() ?: default
    override fun encode(value: Boolean): String = value.toString()
}

class ChoiceSetting(
    key: String,
    section: SettingsSection,
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int? = null,
    @StringRes keywordsRes: Int? = null,
    default: String,
    val choices: List<Choice>,
    scope: SettingScope = SettingScope.PROFILE,
) : SettingDef<String>(key, section, titleRes, summaryRes, keywordsRes, default, scope) {
    override fun decode(raw: String?): String = raw?.takeIf { v -> choices.any { it.value == v } } ?: default
    override fun encode(value: String): String = value
}

/** ARGB colour, or null for "none". */
class ColorSetting(
    key: String,
    section: SettingsSection,
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int? = null,
    @StringRes keywordsRes: Int? = null,
    default: Long?,
    scope: SettingScope = SettingScope.PROFILE,
) : SettingDef<Long?>(key, section, titleRes, summaryRes, keywordsRes, default, scope) {
    override fun decode(raw: String?): Long? = when {
        raw == null -> default
        raw.isEmpty() -> null
        else -> raw.removePrefix("#").toLongOrNull(16) ?: default
    }
    override fun encode(value: Long?): String = value?.let { "#%08X".format(it) } ?: ""
}

object Settings {
    const val CUSTOM_ACCENT = "custom"

    val Accent = ChoiceSetting(
        key = "appearance.accent",
        section = SettingsSection.APPEARANCE,
        titleRes = R.string.setting_accent,
        summaryRes = R.string.setting_accent_summary,
        keywordsRes = R.string.setting_accent_keywords,
        default = "teal",
        choices = listOf(
            Choice("teal", R.string.accent_teal),
            Choice("red", R.string.accent_red),
            Choice("yellow", R.string.accent_yellow),
            Choice("blue", R.string.accent_blue),
            Choice("cyan", R.string.accent_cyan),
            Choice("silver", R.string.accent_silver),
            Choice("green", R.string.accent_green),
            Choice("violet", R.string.accent_violet),
            Choice("pink", R.string.accent_pink),
            Choice("orange", R.string.accent_orange),
            Choice(CUSTOM_ACCENT, R.string.accent_custom),
        ),
    )

    val CustomAccent = ColorSetting(
        key = "appearance.custom_accent",
        section = SettingsSection.APPEARANCE,
        titleRes = R.string.setting_custom_accent,
        summaryRes = R.string.setting_custom_accent_summary,
        keywordsRes = R.string.setting_accent_keywords,
        default = 0xFF14B8A6,
    )

    val ThemeMode = ChoiceSetting(
        key = "appearance.mode",
        section = SettingsSection.APPEARANCE,
        titleRes = R.string.setting_mode,
        summaryRes = R.string.setting_mode_summary,
        keywordsRes = R.string.setting_mode_keywords,
        default = "dark",
        choices = listOf(Choice("dark", R.string.mode_dark), Choice("amoled", R.string.mode_amoled)),
    )

    val FrostedGlass = BoolSetting(
        key = "appearance.frosted_glass",
        section = SettingsSection.APPEARANCE,
        titleRes = R.string.setting_frosted_glass,
        summaryRes = R.string.setting_frosted_glass_summary,
        keywordsRes = R.string.setting_glass_keywords,
        default = false,
    )

    val ReduceMotion = BoolSetting(
        key = "accessibility.reduce_motion",
        section = SettingsSection.ACCESSIBILITY,
        titleRes = R.string.setting_reduce_motion,
        summaryRes = R.string.setting_reduce_motion_summary,
        keywordsRes = R.string.setting_motion_keywords,
        default = false,
    )

    val HighContrast = BoolSetting(
        key = "accessibility.high_contrast",
        section = SettingsSection.ACCESSIBILITY,
        titleRes = R.string.setting_high_contrast,
        summaryRes = R.string.setting_high_contrast_summary,
        keywordsRes = R.string.setting_contrast_keywords,
        default = false,
    )

    val SolidPanels = BoolSetting(
        key = "accessibility.solid_panels",
        section = SettingsSection.ACCESSIBILITY,
        titleRes = R.string.setting_solid_panels,
        summaryRes = R.string.setting_solid_panels_summary,
        keywordsRes = R.string.setting_glass_keywords,
        default = false,
    )

    val Language = ChoiceSetting(
        key = "language.app_language",
        section = SettingsSection.LANGUAGE,
        titleRes = R.string.setting_language,
        summaryRes = R.string.setting_language_summary,
        keywordsRes = R.string.setting_language_keywords,
        default = "system",
        choices = listOf(
            Choice("system", R.string.language_system),
            Choice("en", R.string.language_english),
            Choice("ar", R.string.language_arabic),
        ),
        scope = SettingScope.GLOBAL,
    )

    val Digits = ChoiceSetting(
        key = "language.digits",
        section = SettingsSection.LANGUAGE,
        titleRes = R.string.setting_digits,
        summaryRes = R.string.setting_digits_summary,
        keywordsRes = R.string.setting_digits_keywords,
        default = "auto",
        choices = listOf(
            Choice("auto", R.string.digits_auto),
            Choice("western", R.string.digits_western),
            Choice("arabic", R.string.digits_arabic),
        ),
    )

    val MirrorTimeline = BoolSetting(
        key = "language.mirror_timeline",
        section = SettingsSection.LANGUAGE,
        titleRes = R.string.setting_mirror_timeline,
        summaryRes = R.string.setting_mirror_timeline_summary,
        keywordsRes = R.string.setting_timeline_keywords,
        default = false,
    )

    val SavingEnabled = BoolSetting(
        key = "privacy.saving_enabled",
        section = SettingsSection.PRIVACY,
        titleRes = R.string.setting_saving,
        summaryRes = R.string.setting_saving_summary,
        keywordsRes = R.string.setting_saving_keywords,
        default = true,
    )

    val all: List<SettingDef<*>> = listOf(
        Accent, CustomAccent, ThemeMode, FrostedGlass,
        *PlayerSettings.all.toTypedArray(),
        ReduceMotion, HighContrast, SolidPanels,
        Language, Digits, MirrorTimeline,
        SavingEnabled,
    )

    fun inSection(section: SettingsSection) = all.filter { it.section == section }

    fun byKey(key: String) = all.firstOrNull { it.key == key }
}
