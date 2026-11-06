package com.troy.orcaplayer.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.AppViewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.troy.orcaplayer.ui.addons.AddonDetailScreen
import com.troy.orcaplayer.ui.addons.AddonsScreen
import com.troy.orcaplayer.ui.addons.DiscoverScreen
import com.troy.orcaplayer.ui.common.TitleMemory
import com.troy.orcaplayer.ui.home.HomeScreen
import com.troy.orcaplayer.ui.links.UrlPlayerScreen
import com.troy.orcaplayer.ui.links.ClipboardOffer
import com.troy.orcaplayer.ui.search.SearchScreen
import com.troy.orcaplayer.ui.title.TitleScreen
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.ui.tools.JobsScreen
import com.troy.orcaplayer.ui.tools.ToolsCustomizeScreen
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.troy.orcaplayer.core.design.motion.SpringMath
import com.troy.orcaplayer.core.design.motion.TroySprings
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.MainTab
import com.troy.orcaplayer.core.model.NavLayout
import com.troy.orcaplayer.ui.local.LocalScreen
import com.troy.orcaplayer.ui.screens.LicencesScreen
import com.troy.orcaplayer.ui.screens.ProfilesScreen
import com.troy.orcaplayer.ui.screens.DecoderCheckScreen
import com.troy.orcaplayer.ui.screens.TabPlaceholder
import com.troy.orcaplayer.ui.screens.ToolsScreen
import com.troy.orcaplayer.ui.settings.SettingsScreen

enum class NavStyle { BOTTOM, RAIL, TV_RAIL }

@Composable
fun navStyle(isTv: Boolean): NavStyle {
    val cfg = LocalConfiguration.current
    return when {
        isTv -> NavStyle.TV_RAIL
        cfg.screenWidthDp >= 600 || cfg.screenWidthDp > cfg.screenHeightDp -> NavStyle.RAIL
        else -> NavStyle.BOTTOM
    }
}

@Composable
fun OrcaShell(navLayout: NavLayout, isTv: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Tabs can be hidden or reordered by the layout model; Home stays reachable as the start.
    val tabs = navLayout.visibleTabs.ifEmpty { listOf(MainTab.HOME) }.map { it.spec() }
    val onTabRoute = tabs.any { it.route == route }
    val style = navStyle(isTv)
    val onSelect: (TabSpec) -> Unit = { spec -> nav.navigateToTab(spec.route) }
    val c = Troy.colors

    val appVm: AppViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    val values by appVm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(c.bg)) {
        when (style) {
            NavStyle.BOTTOM -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { OrcaNavHost(nav, tabs.first().route) }
                if (onTabRoute) OrcaBottomBar(tabs, route, onSelect)
            }
            NavStyle.RAIL, NavStyle.TV_RAIL -> Row(Modifier.fillMaxSize()) {
                if (onTabRoute) OrcaRail(tabs, route, onSelect, tv = style == NavStyle.TV_RAIL)
                Box(Modifier.weight(1f)) { OrcaNavHost(nav, tabs.first().route) }
            }
        }
        ClipboardOffer(
            enabled = values?.get(com.troy.orcaplayer.core.settings.SourceSettings.ClipboardDetect) ?: false,
            onPlay = { link -> appVm.playLink(link)?.let { com.troy.orcaplayer.ui.player.PlayerLaunch.play(context, it) } },
            onInstall = { link -> appVm.pendingInstall.value = link },
        )
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun OrcaNavHost(nav: NavHostController, start: String) {
    val motion = Troy.motion
    // Tab switches: the old screen fades out quickly while the new one materializes (fade, rise and
    // blur in one layer), so the two never sit half-blurred on top of each other.
    val enter: EnterTransition = if (motion.reduceMotion) EnterTransition.None else fadeIn(
        spring(dampingRatio = 1f, stiffness = SpringMath.stiffness(TroySprings.Default.response)),
    )
    val exit: ExitTransition = if (motion.reduceMotion) ExitTransition.None else fadeOut(
        spring(dampingRatio = 1f, stiffness = SpringMath.stiffness(TroySprings.LITE_RESPONSE_CAP)),
    )
    val openSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }
    val openProfiles = { nav.navigate(Routes.PROFILES) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }
    val appVm: AppViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    val openUrlPlayer = { nav.navigate(Routes.urlPlayer()) { launchSingleTop = true } }
    val openTitle: (Title) -> Unit = { t ->
        TitleMemory.put(t)
        nav.navigate(Routes.title(t.type.name, t.id))
    }
    val openDiscover: (String?, String?) -> Unit = { a, k -> nav.navigate(Routes.discover(a, k)) }
    val pendingInstall by appVm.pendingInstall.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingInstall) {
        if (pendingInstall != null && nav.currentDestination?.route != MainTab.ADDONS.spec().route) nav.navigateToTab(MainTab.ADDONS.spec().route)
    }

    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { enter },
        popExitTransition = { exit },
    ) {
        MainTab.entries.forEach { tab ->
            composable(tab.spec().route) {
                when (tab) {
                    MainTab.HOME -> HomeScreen(
                        openSettings, openProfiles, openTitle,
                        onOpenCatalog = { a, k -> openDiscover(a, k) },
                        onOpenUrlPlayer = openUrlPlayer,
                        onOpenAddons = { nav.navigateToTab(MainTab.ADDONS.spec().route) },
                    )
                    MainTab.ADDONS -> AddonsScreen(
                        openSettings, openProfiles,
                        onOpenAddon = { nav.navigate(Routes.addon(it)) },
                        onOpenDiscover = { openDiscover(null, null) },
                        installRequest = pendingInstall,
                        onInstallRequestHandled = { appVm.pendingInstall.value = null },
                    )
                    MainTab.SEARCH -> SearchScreen(openSettings, openProfiles, openTitle)
                    MainTab.LOCAL -> LocalScreen(openSettings, openProfiles, openUrlPlayer)
                    MainTab.TOOLS -> {
                        val layout by appVm.toolsLayout.collectAsStateWithLifecycle()
                        ToolsScreen(
                            layout, openSettings, openProfiles,
                            onOpenDecoderCheck = { nav.navigate(Routes.DECODER_CHECK) { launchSingleTop = true } },
                            onOpenJobs = { nav.navigate(Routes.JOBS) { launchSingleTop = true } },
                            onCustomize = { nav.navigate(Routes.TOOLS_CUSTOMIZE) { launchSingleTop = true } },
                            onOpenUrlPlayer = openUrlPlayer,
                        )
                    }
                    else -> TabPlaceholder(tab, openSettings, openProfiles)
                }
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = back,
                onOpenLicences = { nav.navigate(Routes.LICENCES) },
                onOpenDecoderCheck = { nav.navigate(Routes.DECODER_CHECK) { launchSingleTop = true } },
            )
        }
        composable(Routes.DECODER_CHECK) { DecoderCheckScreen(onBack = back) }
        composable(Routes.JOBS) { JobsScreen(onBack = back) }
        composable(Routes.TOOLS_CUSTOMIZE) {
            val layout by appVm.toolsLayout.collectAsStateWithLifecycle()
            ToolsCustomizeScreen(layout, appVm::setToolsLayout, onBack = back)
        }
        composable(Routes.PROFILES) { ProfilesScreen(onBack = back) }
        composable(Routes.URL_PLAYER, arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })) { e ->
            UrlPlayerScreen(
                onBack = back,
                onInstallAddon = { link -> appVm.pendingInstall.value = link },
                initialUrl = e.arguments?.getString("url")?.takeIf { it.isNotEmpty() },
            )
        }
        composable(Routes.ADDON, arguments = listOf(navArgument("id") { type = NavType.StringType })) { AddonDetailScreen(onBack = back) }
        composable(
            Routes.DISCOVER,
            arguments = listOf(
                navArgument("addon") { type = NavType.StringType; defaultValue = "" },
                navArgument("catalog") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { DiscoverScreen(onBack = back, onOpenTitle = openTitle) }
        composable(
            Routes.TITLE,
            arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
        ) { TitleScreen(onBack = back) }
        composable(Routes.LICENCES) { LicencesScreen(onBack = back) }
    }
}
