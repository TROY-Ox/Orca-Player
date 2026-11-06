package com.troy.orcaplayer.ui.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.model.MainTab

object Routes {
    const val HOME = "home"
    const val LOCAL = "local"
    const val ADDONS = "addons"
    const val SEARCH = "search"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"
    const val PROFILES = "profiles"
    const val LICENCES = "licences"
    const val DECODER_CHECK = "decoder_check"
    const val JOBS = "jobs"
    const val TOOLS_CUSTOMIZE = "tools_customize"
    const val URL_PLAYER = "url_player?url={url}"
    const val ADDON = "addon/{id}"
    const val DISCOVER = "discover?addon={addon}&catalog={catalog}"
    const val TITLE = "title/{type}/{id}?play={play}"
    const val MY_LIST = "my_list"
    const val HOME_ROWS = "home_rows"
    const val DOWNLOADS = "downloads?tab={tab}"

    fun urlPlayer(url: String? = null) = "url_player?url=" + android.net.Uri.encode(url.orEmpty())
    fun addon(id: String) = "addon/" + android.net.Uri.encode(id)
    fun discover(addonId: String? = null, catalogKey: String? = null) =
        "discover?addon=" + android.net.Uri.encode(addonId.orEmpty()) + "&catalog=" + android.net.Uri.encode(catalogKey.orEmpty())
    fun downloads(tab: String? = null) = "downloads?tab=" + android.net.Uri.encode(tab.orEmpty())
    /** [play] opens the stream picker at once: an episode ID, or "movie". */
    fun title(type: String, id: String, play: String? = null) =
        "title/" + android.net.Uri.encode(type) + "/" + android.net.Uri.encode(id) + "?play=" + android.net.Uri.encode(play.orEmpty())
}

data class TabSpec(val tab: MainTab, val route: String, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

fun MainTab.spec(): TabSpec = when (this) {
    MainTab.HOME -> TabSpec(this, Routes.HOME, R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home)
    MainTab.LOCAL -> TabSpec(this, Routes.LOCAL, R.string.tab_local, Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary)
    MainTab.ADDONS -> TabSpec(this, Routes.ADDONS, R.string.tab_addons, Icons.Outlined.Extension, Icons.Filled.Extension)
    MainTab.SEARCH -> TabSpec(this, Routes.SEARCH, R.string.tab_search, Icons.Outlined.Search, Icons.Filled.Search)
    MainTab.TOOLS -> TabSpec(this, Routes.TOOLS, R.string.tab_tools, Icons.Outlined.Build, Icons.Filled.Build)
}
