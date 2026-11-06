package com.troy.orcaplayer.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.core.design.component.pressScale
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes

@Composable
private fun NavItem(
    spec: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Troy.colors
    val t = Troy.type
    val source = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (selected) c.accent else c.text2, Troy.motion.spec(), label = "navTint")
    val bg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.14f) else Color.Transparent, Troy.motion.spec(), label = "navBg")
    val label = stringResource(spec.label)
    val itemModifier = modifier
        .pressScale(source)
        .troyFocusRing(12.dp)
        .selectable(selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
    if (horizontal) {
        Row(
            itemModifier.background(bg, TroyShapes.Control).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(if (selected) spec.selectedIcon else spec.icon, contentDescription = null, tint = tint)
            Text(label, style = t.bodySmall, color = if (selected) c.text else c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Column(
            itemModifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.background(bg, TroyShapes.Pill).padding(horizontal = 18.dp, vertical = 4.dp)) {
                Icon(if (selected) spec.selectedIcon else spec.icon, contentDescription = if (showLabel) null else label, tint = tint)
            }
            if (showLabel) {
                Spacer(Modifier.height(4.dp))
                Text(label, style = t.label.copy(letterSpacing = t.body.letterSpacing), color = if (selected) c.text else c.text2, maxLines = 1)
            }
        }
    }
}

/** Phones in portrait. */
@Composable
fun OrcaBottomBar(tabs: List<TabSpec>, selectedRoute: String?, onSelect: (TabSpec) -> Unit) {
    val g = Troy.glass
    Row(
        Modifier
            .fillMaxWidth()
            .background(g.panel)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(vertical = 6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { spec ->
            NavItem(spec, spec.route == selectedRoute, { onSelect(spec) }, showLabel = true, horizontal = false, modifier = Modifier.weight(1f))
        }
    }
}

/** Landscape and tablets: a navigation rail. On TV: a wider side rail with labels and initial focus. */
@Composable
fun OrcaRail(tabs: List<TabSpec>, selectedRoute: String?, onSelect: (TabSpec) -> Unit, tv: Boolean) {
    val g = Troy.glass
    val first = remember { FocusRequester() }
    if (tv) {
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .then(if (tv) Modifier.width(220.dp) else Modifier.width(88.dp))
            .background(g.panel)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(horizontal = if (tv) 12.dp else 4.dp, vertical = 16.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tabs.forEachIndexed { i, spec ->
            val selected = spec.route == selectedRoute
            NavItem(
                spec,
                selected,
                { onSelect(spec) },
                showLabel = true,
                horizontal = tv,
                modifier = (if (tv) Modifier.fillMaxWidth() else Modifier.size(width = 80.dp, height = 64.dp))
                    .then(if ((selectedRoute == null && i == 0) || selected) Modifier.focusRequester(first) else Modifier),
            )
        }
    }
}
