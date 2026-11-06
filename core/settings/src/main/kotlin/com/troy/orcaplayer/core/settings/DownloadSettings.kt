package com.troy.orcaplayer.core.settings

import androidx.annotation.StringRes

/** The downloader (16). These apply to the whole app, since downloads run for every profile. */
object DownloadSettings {
    private val D = SettingsSection.DOWNLOADS

    private fun choice(key: String, @StringRes title: Int, @StringRes summary: Int?, default: String, vararg choices: Pair<String, Int>) =
        ChoiceSetting(key, D, title, summary, R.string.setting_download_keywords, default, choices.map { Choice(it.first, it.second) }, SettingScope.GLOBAL)

    private fun bool(key: String, @StringRes title: Int, @StringRes summary: Int?, default: Boolean) =
        BoolSetting(key, D, title, summary, R.string.setting_download_keywords, default, SettingScope.GLOBAL)

    const val PLACE_PUBLIC = "public"
    const val PLACE_PRIVATE = "private"
    const val PLACE_FOLDER = "folder"

    val Place = choice(
        "downloads.place", R.string.setting_dl_place, R.string.setting_dl_place_summary, PLACE_PUBLIC,
        PLACE_PUBLIC to R.string.dl_place_public, PLACE_PRIVATE to R.string.dl_place_private, PLACE_FOLDER to R.string.dl_place_folder,
    )
    /** The folder picked with the system folder picker, as a tree URI. */
    val Folder = TextSetting("downloads.folder", D, R.string.setting_dl_folder, R.string.setting_dl_folder_summary, R.string.setting_download_keywords, scope = SettingScope.GLOBAL)
    val Parallel = choice(
        "downloads.parallel", R.string.setting_dl_parallel, R.string.setting_dl_parallel_summary, "2",
        "1" to R.string.count_1, "2" to R.string.count_2, "3" to R.string.count_3, "4" to R.string.count_4, "5" to R.string.count_5,
    )
    val MultiConnection = bool("downloads.multi", R.string.setting_dl_multi, R.string.setting_dl_multi_summary, false)
    val SpeedLimit = choice(
        "downloads.speed", R.string.setting_dl_speed, R.string.setting_dl_speed_summary, "0",
        "0" to R.string.dl_speed_off, "524288" to R.string.dl_speed_512k, "1048576" to R.string.dl_speed_1m, "2097152" to R.string.dl_speed_2m,
        "5242880" to R.string.dl_speed_5m, "10485760" to R.string.dl_speed_10m, "20971520" to R.string.dl_speed_20m,
    )
    val WifiOnly = bool("downloads.wifi_only", R.string.setting_dl_wifi, R.string.setting_dl_wifi_summary, true)
    val ChargingOnly = bool("downloads.charging", R.string.setting_dl_charging, R.string.setting_dl_charging_summary, false)
    val StartTime = choice(
        "downloads.start", R.string.setting_dl_start, R.string.setting_dl_start_summary, "off",
        "off" to R.string.dl_start_off, "1320" to R.string.time_2200, "1380" to R.string.time_2300, "0" to R.string.time_0000,
        "60" to R.string.time_0100, "120" to R.string.time_0200, "180" to R.string.time_0300, "240" to R.string.time_0400,
    )
    val Retries = choice(
        "downloads.retries", R.string.setting_dl_retries, R.string.setting_dl_retries_summary, "5",
        "3" to R.string.count_3, "5" to R.string.count_5, "10" to R.string.count_10,
    )
    val Reserve = choice(
        "downloads.reserve", R.string.setting_dl_reserve, R.string.setting_dl_reserve_summary, "500",
        "200" to R.string.mb_200, "500" to R.string.mb_500, "1000" to R.string.mb_1000, "2000" to R.string.mb_2000,
    )
    val MovieTemplate = TextSetting(
        "downloads.movie_name", D, R.string.setting_dl_movie_name, R.string.setting_dl_name_summary, R.string.setting_download_keywords,
        default = "{title} ({year})", scope = SettingScope.GLOBAL,
    )
    val EpisodeTemplate = TextSetting(
        "downloads.episode_name", D, R.string.setting_dl_episode_name, R.string.setting_dl_name_summary, R.string.setting_download_keywords,
        default = "{series} S{season}E{episode} {episode_title}", scope = SettingScope.GLOBAL,
    )
    val SaveSubtitles = bool("downloads.subtitles", R.string.setting_dl_subtitles, R.string.setting_dl_subtitles_summary, true)
    val Notify = bool("downloads.notify", R.string.setting_dl_notify, null, true)
    val SmartDelete = choice(
        "downloads.smart_delete", R.string.setting_dl_smart_delete, R.string.setting_dl_smart_delete_summary, "ask",
        "off" to R.string.dl_smart_keep_files, "ask" to R.string.dl_smart_ask, "auto" to R.string.dl_smart_auto,
    )
    val SmartKeep = choice(
        "downloads.smart_keep", R.string.setting_dl_smart_keep, R.string.setting_dl_smart_keep_summary, "2",
        "1" to R.string.count_1, "2" to R.string.count_2, "3" to R.string.count_3, "5" to R.string.count_5,
    )
    val KidsAllowed = bool("downloads.kids", R.string.setting_dl_kids, R.string.setting_dl_kids_summary, false)

    val all: List<SettingDef<*>> = listOf(
        Place, Folder, Parallel, MultiConnection, SpeedLimit, WifiOnly, ChargingOnly, StartTime, Retries, Reserve,
        MovieTemplate, EpisodeTemplate, SaveSubtitles, Notify, SmartDelete, SmartKeep, KidsAllowed,
    )
}
