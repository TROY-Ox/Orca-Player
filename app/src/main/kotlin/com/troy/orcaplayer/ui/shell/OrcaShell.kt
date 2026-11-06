package com.troy.orcaplayer.ui.shell

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
import com.troy.orcaplayer.ui.screens.TabPlaceholder
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
                if (tab == MainTab.LOCAL) LocalScreen(openSettings, openProfiles) else TabPlaceholder(tab, openSettings, openProfiles)
            }
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = back, onOpenLicences = { nav.navigate(Routes.LICENCES) }) }
        composable(Routes.PROFILES) { ProfilesScreen(onBack = back) }
        composable(Routes.LICENCES) { LicencesScreen(onBack = back) }
    }
}
