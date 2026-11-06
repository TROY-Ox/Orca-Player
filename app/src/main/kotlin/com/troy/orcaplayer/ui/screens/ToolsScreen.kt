package com.troy.orcaplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.ui.shell.MainTopBar

/** The Tools tab. Phase 3 adds the decoder check; the other tools arrive in Phase 4. */
@Composable
fun ToolsScreen(onOpenSettings: () -> Unit, onOpenProfiles: () -> Unit, onOpenDecoderCheck: () -> Unit) {
    val c = Troy.colors
    Column(Modifier.fillMaxSize()) {
        MainTopBar(stringResource(R.string.tab_tools), onOpenSettings, onOpenProfiles)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).troyFocusRing()
                    .clickable(role = Role.Button, onClick = onOpenDecoderCheck).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = c.accent, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dc_title), style = Troy.type.title, color = c.text)
                    Text(stringResource(R.string.dc_summary), style = Troy.type.bodySmall, color = c.text2)
                }
            }
            Text(stringResource(R.string.tools_more_later), style = Troy.type.bodySmall, color = c.text3)
        }
    }
}
