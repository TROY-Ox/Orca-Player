package com.troy.orcaplayer

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.troy.orcaplayer.core.design.theme.OrcaTheme
import com.troy.orcaplayer.core.design.theme.ThemeMode
import com.troy.orcaplayer.core.design.theme.ThemeState
import com.troy.orcaplayer.core.design.theme.TroyAccent
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.core.settings.Settings
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.system.SystemSignals
import com.troy.orcaplayer.ui.shell.OrcaShell
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        vm.updateSignals(SystemSignals.read(this))
        takeInstallLink(intent)
        setContent { OrcaRoot(vm) }
        // Menu orientation setting, phones only (tablets, foldables and TVs follow the screen).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.settings.collect { v ->
                    if (v == null || resources.configuration.smallestScreenWidthDp >= 600 || vm.signals.value.isTv) return@collect
                    val wanted = when (v[PlayerSettings.MenuOrientation]) {
                        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    if (requestedOrientation != wanted) requestedOrientation = wanted
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takeInstallLink(intent)
    }

    private fun takeInstallLink(intent: android.content.Intent?) {
        val link = intent?.getStringExtra(EXTRA_INSTALL_ADDON) ?: intent?.dataString?.takeIf { it.startsWith("stremio:", ignoreCase = true) }
        if (link != null) vm.pendingInstall.value = link
        intent?.getStringExtra(EXTRA_SHARED_LINK)?.let { vm.pendingShare.value = it }
        if (intent?.getBooleanExtra(com.troy.orcaplayer.downloads.DownloadNotifications.EXTRA_OPEN_DOWNLOADS, false) == true) vm.pendingDownloads.value = true
    }

    companion object {
        const val EXTRA_INSTALL_ADDON = "orca.install_addon"
        /** A link shared from another app, offered as Play or Download. */
        const val EXTRA_SHARED_LINK = "orca.shared_link"
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed Remove animations or contrast while we were away.
        vm.updateSignals(SystemSignals.read(this))
    }
}

@Composable
private fun OrcaRoot(vm: AppViewModel) {
    val values by vm.settings.collectAsStateWithLifecycle()
    val signals by vm.signals.collectAsStateWithLifecycle()
    val nav by vm.navLayout.collectAsStateWithLifecycle()
    val isArabic = LocalConfiguration.current.locales[0]?.language == "ar"
    val state = themeState(values ?: SettingsValues.Defaults, signals, isArabic)
    OrcaTheme(state) {
        OrcaShell(navLayout = nav, isTv = signals.isTv)
    }
}

internal fun themeState(v: SettingsValues, s: SystemSignals, isArabic: Boolean): ThemeState {
    val accentKey = v[Settings.Accent]
    val custom = if (accentKey == Settings.CUSTOM_ACCENT) v[Settings.CustomAccent]?.let { Color(it.toInt()) } else null
    val digits = v[Settings.Digits]
    return ThemeState(
        accent = TroyAccent.fromKey(accentKey) ?: TroyAccent.Default,
        customAccent = custom,
        mode = if (v[Settings.ThemeMode] == "amoled") ThemeMode.AMOLED else ThemeMode.DARK,
        frostedGlass = v[Settings.FrostedGlass],
        solidPanels = v[Settings.SolidPanels],
        highContrast = v[Settings.HighContrast] || s.highContrast,
        reduceMotion = v[Settings.ReduceMotion] || s.removeAnimations,
        lite = s.isTv || s.lowRam,
        arabicDigits = digits == "arabic" || (digits == "auto" && isArabic),
        mirrorTimeline = v[Settings.MirrorTimeline],
    )
}
