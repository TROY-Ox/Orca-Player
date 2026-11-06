package com.troy.orcaplayer.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes

enum class ButtonKind { PRIMARY, SECONDARY, GHOST }

/** One solid primary button per screen. Labels are small and uppercase in English. */
@Composable
fun TroyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Troy.colors
    val t = Troy.type
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val shape = TroyShapes.Control
    val bg = when (kind) {
        ButtonKind.PRIMARY -> when {
            pressed -> c.accentPressed
            hovered -> c.accentHover
            else -> c.accent
        }
        ButtonKind.SECONDARY -> c.controlFill
        ButtonKind.GHOST -> androidx.compose.ui.graphics.Color.Transparent
    }
    val fg = if (kind == ButtonKind.PRIMARY) c.onAccent else c.text
    val glow = if (kind == ButtonKind.PRIMARY && enabled) {
        Modifier.shadow(14.dp, shape, clip = false, ambientColor = c.accent.copy(alpha = 0.4f), spotColor = c.accent.copy(alpha = 0.4f))
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .pressScale(source)
            .then(glow)
            .troyFocusRing()
            .clip(shape)
            .background(bg)
            .then(if (kind == ButtonKind.GHOST) Modifier else Modifier.border(1.dp, if (kind == ButtonKind.PRIMARY) c.accent else c.hair, shape))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(if (t.uppercaseLabels) text.uppercase() else text, style = t.button, color = fg)
    }
}

@Composable
fun TroyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = Troy.colors.text,
) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(48.dp)
            .pressScale(source)
            .troyFocusRing(24.dp)
            .clip(TroyShapes.Pill)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
