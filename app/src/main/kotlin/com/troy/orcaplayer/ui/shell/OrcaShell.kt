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
import com.troy.orcaplayer.ui.profiles.ProfilesScreen
import com.troy.orcaplayer.ui.profiles.KidsExitGate
import com.troy.orcaplayer.ui.profiles.ProfilesViewModel
import com.troy.orcaplayer.ui.profiles.WhoIsWatchingScreen
import com.troy.orcaplayer.ui.home.TitlePreviewHost
import com.troy.orcaplayer.ui.home.MyListScreen
import com.troy.orcaplayer.ui.home.HomeRowsScreen
import com.troy.orcaplayer.ui.common.CardSpec
import com.troy.orcaplayer.ui.common.LocalCardSpec
import com.troy.orcaplayer.core.settings.BrowsingSettings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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

/** Kids restrictions for the shell (18): set once from the current profile. */
data class KidsGuard(val active: Boolean = false, val request: (after: () -> Unit) -> Unit = { it() })

val LocalKidsGuard = staticCompositionLocalOf { KidsGuard() }

@Composable
fun OrcaShell(navLayout: NavLayout, isTv: Boolean) {
    val appVm: AppViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    val profiles by appVm.allProfiles.collectAsStateWithLifecycle()
    val chosen by appVm.profileChosen.collectAsStateWithLifecycle()
    val values by appVm.settings.collectAsStateWithLifecycle()
    val list = profiles
    // Who's watching, once per launch, when there is a choice to make (18).
    if (!chosen && list != null && values != null) {
        if (list.size > 1 && values!![BrowsingSettings.AskAtStart]) {
            var managing by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(Troy.colors.bg)) {
                if (managing) {
                    ProfilesScreen(onBack = { managing = false })
                } else {
                    WhoIsWatchingScreen(onChosen = { appVm.profileChosen.value = true }, onManage = { managing = true })
                }
            }
            return
        }
        LaunchedEffect(Unit) { appVm.profileChosen.value = true }
    }
    if (list == null || values == null) {
        Box(Modifier.fillMaxSize().background(Troy.colors.bg))
        return
    }
    MainShell(navLayout, isTv, appVm)
}

@Composable
private fun MainShell(navLayout: NavLayout, isTv: Boolean, appVm: AppViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val profile by appVm.currentProfile.collectAsStateWithLifecycle()
    val kids = profile?.isKids == true
    // Tabs can be hidden or reordered by the layout model; Home stays reachable as the start.
    // Kids profiles never see Addons or Tools (18).
    val tabs = navLayout.visibleTabs
        .filter { !kids || (it != MainTab.ADDONS && it != MainTab.TOOLS) }
        .ifEmpty { listOf(MainTab.HOME) }.map { it.spec() }
    val onTabRoute = tabs.any { it.route == route }
    val style = navStyle(isTv)
    val onSelect: (TabSpec) -> Unit = { spec -> nav.navigateToTab(spec.route) }
    val c = Troy.colors

    val values by appVm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profilesVm: ProfilesViewModel = hiltViewModel()
    var gateThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    val guard = remember(kids) { KidsGuard(kids) { after -> if (kids) gateThen = after else after() } }

    // Screen pinning keeps a child in the app (18); it is released when the profile changes.
    val activity = context as? android.app.Activity
    val pin = kids && profile?.kids?.screenPinning == true
    LaunchedEffect(pin) {
        runCatching { if (pin) activity?.startLockTask() else if (isLockTaskOn(context)) activity?.stopLockTask() }
    }
    // A tab the new profile can't see: go back to Home.
    LaunchedEffect(kids, route) {
        if (kids && (route == MainTab.ADDONS.spec().route || route == MainTab.TOOLS.spec().route)) nav.navigateToTab(tabs.first().route)
    }

    val spec = values?.let { CardSpec.from(it, Troy.motion.reduceMotion) } ?: CardSpec()
    CompositionLocalProvider(LocalKidsGuard provides guard, LocalCardSpec provides spec) {
        TitlePreviewHost(openTitle = { t, play ->
            com.troy.orcaplayer.ui.common.TitleMemory.put(t)
            nav.navigate(Routes.title(t.type.name, t.id, play))
        }) {
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
                    enabled = !kids && (values?.get(com.troy.orcaplayer.core.settings.SourceSettings.ClipboardDetect) ?: false),
                    onPlay = { link -> appVm.playLink(link)?.let { com.troy.orcaplayer.ui.player.PlayerLaunch.play(context, it) } },
                    onInstall = { link -> appVm.pendingInstall.value = link },
                )
                // A link shared to the app: play it or download it (16.1). Not in Kids profiles.
                val shared by appVm.pendingShare.collectAsStateWithLifecycle()
                shared?.let { link ->
                    if (kids) {
                        LaunchedEffect(link) {
                            appVm.pendingShare.value = null
                            android.widget.Toast.makeText(context, com.troy.orcaplayer.R.string.kids_blocked, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        com.troy.orcaplayer.ui.downloads.SharedLinkDialog(link, appVm, onDone = { appVm.pendingShare.value = null })
                    }
                }
                gateThen?.let { then ->
                    KidsExitGate(profilesVm, onDismiss = { gateThen = null }, onUnlocked = { gateThen = null; then() })
                }
            }
        }
    }
}

private fun isLockTaskOn(context: android.content.Context): Boolean {
    val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
    return am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
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
    val guard = LocalKidsGuard.current
    val ctx = LocalContext.current
    // In a Kids profile, Settings and Profiles sit behind the exit gate (18).
    val openSettings: () -> Unit = { guard.request { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } } }
    val openProfiles: () -> Unit = { guard.request { nav.navigate(Routes.PROFILES) { launchSingleTop = true } } }
    val back: () -> Unit = { nav.popBackStack() }
    val appVm: AppViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    val openUrlPlayer: () -> Unit = {
        if (guard.active) {
            android.widget.Toast.makeText(ctx, com.troy.orcaplayer.R.string.kids_blocked, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            nav.navigate(Routes.urlPlayer()) { launchSingleTop = true }
        }
    }
    val openDetails: (String, String, String?) -> Unit = { type, id, play -> nav.navigate(Routes.title(type, id, play)) }
    val openMyList: () -> Unit = { nav.navigate(Routes.MY_LIST) { launchSingleTop = true } }
    val openTitle: (Title) -> Unit = { t ->
        TitleMemory.put(t)
        nav.navigate(Routes.title(t.type.name, t.id))
    }
    val openDiscover: (String?, String?) -> Unit = { a, k -> nav.navigate(Routes.discover(a, k)) }
    val openDownloads: (String?) -> Unit = { tab -> nav.navigate(Routes.downloads(tab)) { launchSingleTop = true } }
    val showDownloads by appVm.pendingDownloads.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(showDownloads) {
        if (showDownloads) {
            appVm.pendingDownloads.value = false
            openDownloads(null)
        }
    }
    val pendingTitle by appVm.pendingTitle.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingTitle) {
        val t = pendingTitle ?: return@LaunchedEffect
        appVm.pendingTitle.value = null
        val type = t.substringBefore('/')
        val id = t.substringAfter('/', "")
        if (id.isNotEmpty()) nav.navigate(Routes.title(type, id))
    }
    val pendingInstall by appVm.pendingInstall.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingInstall, guard.active) {
        if (pendingInstall != null && guard.active) {
            appVm.pendingInstall.value = null
        } else if (pendingInstall != null && nav.currentDestination?.route != MainTab.ADDONS.spec().route) {
            nav.navigateToTab(MainTab.ADDONS.spec().route)
        }
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
                        onOpenDownloads = { openDownloads("COMPLETED") },
                        onOpenDetails = openDetails,
                        onOpenMyList = openMyList,
                    )
                    MainTab.ADDONS -> AddonsScreen(
                        openSettings, openProfiles,
                        onOpenAddon = { nav.navigate(Routes.addon(it)) },
                        onOpenDiscover = { openDiscover(null, null) },
                        installRequest = pendingInstall,
                        onInstallRequestHandled = { appVm.pendingInstall.value = null },
                    )
                    MainTab.SEARCH -> SearchScreen(openSettings, openProfiles, openTitle)
                    MainTab.LOCAL -> LocalScreen(openSettings, openProfiles, openUrlPlayer, onOpenDownloads = { openDownloads("COMPLETED") })
                    MainTab.TOOLS -> {
                        val layout by appVm.toolsLayout.collectAsStateWithLifecycle()
                        ToolsScreen(
                            layout, openSettings, openProfiles,
                            onOpenDecoderCheck = { nav.navigate(Routes.DECODER_CHECK) { launchSingleTop = true } },
                            onOpenJobs = { nav.navigate(Routes.JOBS) { launchSingleTop = true } },
                            onCustomize = { nav.navigate(Routes.TOOLS_CUSTOMIZE) { launchSingleTop = true } },
                            onOpenUrlPlayer = openUrlPlayer,
                            onOpenDownloads = { openDownloads(null) },
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
                onOpenHomeRows = { nav.navigate(Routes.HOME_ROWS) { launchSingleTop = true } },
                onOpenProfiles = { nav.navigate(Routes.PROFILES) { launchSingleTop = true } },
            )
        }
        composable(Routes.MY_LIST) { MyListScreen(onBack = back, onOpenDetails = openDetails) }
        composable(Routes.HOME_ROWS) { HomeRowsScreen(onBack = back) }
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
            arguments = listOf(
                navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType },
                navArgument("play") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { TitleScreen(onBack = back) }
        composable(Routes.LICENCES) { LicencesScreen(onBack = back) }
        composable(Routes.DOWNLOADS, arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "" })) { e ->
            com.troy.orcaplayer.ui.downloads.DownloadsScreen(
                initialTab = e.arguments?.getString("tab")?.takeIf { it.isNotEmpty() },
                onBack = back,
                onPlay = { com.troy.orcaplayer.ui.player.PlayerLaunch.play(ctx, it) },
            )
        }
    }
}
