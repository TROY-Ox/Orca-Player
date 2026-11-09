package com.troy.orcaplayer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.model.MainTab
import com.troy.orcaplayer.ui.shell.MainTopBar
import com.troy.orcaplayer.ui.shell.spec

/** Phase 1 placeholder for each main tab. Each one says honestly which phase fills it. */
@Composable
fun TabPlaceholder(tab: MainTab, onOpenSettings: () -> Unit, onOpenProfiles: () -> Unit) {
    val spec = tab.spec()
    val (title, message) = when (tab) {
        MainTab.HOME -> R.string.home_empty_title to R.string.home_empty_message
        MainTab.LIVE -> R.string.live_empty_tab_title to R.string.live_empty_tab_message
        MainTab.LOCAL -> R.string.local_empty_title to R.string.local_empty_message
        MainTab.ADDONS -> R.string.addons_empty_title to R.string.addons_empty_message
        MainTab.SEARCH -> R.string.search_empty_title to R.string.search_empty_message
        MainTab.TOOLS -> R.string.tools_empty_title to R.string.tools_empty_message
    }
    Column(Modifier.fillMaxSize()) {
        MainTopBar(stringResource(spec.label), onOpenSettings, onOpenProfiles)
        EmptyState(
            icon = spec.icon,
            title = stringResource(title),
            message = stringResource(message),
            modifier = Modifier.weight(1f),
            actionLabel = stringResource(R.string.menu_settings),
            onAction = onOpenSettings,
        )
    }
}
