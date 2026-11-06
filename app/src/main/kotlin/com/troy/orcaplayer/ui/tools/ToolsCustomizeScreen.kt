package com.troy.orcaplayer.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.ToolId
import com.troy.orcaplayer.core.model.ToolsLayout

/** Order and visibility of the tools, separately for the Tools tab and the player's Tools panel. */
@Composable
fun ToolsCustomizeScreen(layout: ToolsLayout, onChange: (ToolsLayout) -> Unit, onBack: () -> Unit) {
    ToolScaffold(stringResource(R.string.tools_customize), onBack) {
        ToolCard {
            SectionLabel(stringResource(R.string.tools_in_tab))
            ToolList(layout.tools, ToolId.entries.filter { it.inTab }) { onChange(layout.copy(tools = it)) }
        }
        ToolCard {
            SectionLabel(stringResource(R.string.tools_in_panel))
            ToolList(layout.panel, ToolId.entries.filter { it.inPanel }) { onChange(layout.copy(panel = it)) }
        }
        TroyButton(stringResource(R.string.tools_reset), { onChange(ToolsLayout.Default) }, kind = ButtonKind.GHOST)
    }
}

/** Shown tools in their order, then the hidden ones. */
@Composable
private fun ToolList(shown: List<ToolId>, all: List<ToolId>, onChange: (List<ToolId>) -> Unit) {
    val c = Troy.colors
    val visible = shown.filter { it in all }
    val hidden = all.filter { it !in visible }
    (visible + hidden).forEach { tool ->
        val index = visible.indexOf(tool)
        val on = index >= 0
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(tool.icon(), contentDescription = null, tint = if (on) c.accent else c.text3, modifier = Modifier.size(22.dp))
            Text(stringResource(tool.nameRes()), style = Troy.type.body, color = if (on) c.text else c.text3, modifier = Modifier.weight(1f))
            if (on) {
                TroyIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.tools_move_up), onClick = {
                    if (index > 0) onChange(visible.toMutableList().apply { add(index - 1, removeAt(index)) })
                })
                TroyIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.tools_move_down), onClick = {
                    if (index < visible.lastIndex) onChange(visible.toMutableList().apply { add(index + 1, removeAt(index)) })
                })
            }
            TroyToggle(on, { checked -> onChange(if (checked) visible + tool else visible - tool) })
        }
    }
}
