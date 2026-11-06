package com.troy.orcaplayer.core.design.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.core.design.motion.TroySprings
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes

/** Toggle: 44 x 26, knob springs (damping 0.8), track mixes into the accent, soft glow when on.
 *
 * Pass null for [onCheckedChange] when a parent row handles the toggle, so D-pad focus lands
 * on the row only.
 */
@Composable
fun TroyToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Troy.colors
    val motion = Troy.motion
    val source = remember { MutableInteractionSource() }
    val knob by animateDpAsState(if (checked) 21.dp else 3.dp, motion.spec(TroySprings.Toggle), label = "knob")
    val track by animateColorAsState(if (checked) c.accent else lerp(c.controlFill, c.text3, 0.35f), motion.spec(TroySprings.Toggle), label = "track")
    val glow = if (checked) Modifier.shadow(10.dp, TroyShapes.Pill, clip = false, ambientColor = c.accent.copy(alpha = 0.4f), spotColor = c.accent.copy(alpha = 0.4f)) else Modifier
    Box(
        modifier
            .padding(11.dp) // 48dp touch target around the 44 x 26 track
            .size(44.dp, 26.dp)
            .then(glow)
            .then(if (onCheckedChange != null) Modifier.troyFocusRing(13.dp) else Modifier)
            .clip(TroyShapes.Pill)
            .background(track)
            .border(1.dp, c.hair, TroyShapes.Pill)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(checked, source, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) c.onAccent else c.text),
        )
    }
}

@Composable
fun TroyChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Troy.colors
    val t = Troy.type
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.18f) else c.controlFill, Troy.motion.spec(), label = "chip")
    Box(
        modifier
            .pressScale(source)
            .troyFocusRing(8.dp)
            .clip(TroyShapes.Chip)
            .background(bg)
            .border(1.dp, if (selected) c.accent else c.hair, TroyShapes.Chip)
            .selectable(selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = t.bodySmall, color = if (selected) c.text else c.text2)
    }
}

enum class AlertKind { OK, DANGER, WARN, INFO }

/** Alerts: semantic dot and a 3dp start border. They never use the accent. */
@Composable
fun TroyAlert(text: String, kind: AlertKind, modifier: Modifier = Modifier) {
    val c = Troy.colors
    val color = when (kind) {
        AlertKind.OK -> c.ok
        AlertKind.DANGER -> c.danger
        AlertKind.WARN -> c.warn
        AlertKind.INFO -> c.info
    }
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(TroyShapes.Chip)
            .background(c.controlFill)
            .border(1.dp, c.hair, TroyShapes.Chip),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = Troy.type.bodySmall, color = c.text, modifier = Modifier.padding(12.dp))
    }
}

/** Inputs: accent border and a 3dp soft ring on focus. */
@Composable
fun TroyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Troy.colors
    val t = Troy.type
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        interactionSource = source,
        textStyle = t.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier.drawBehind {
            if (focused) {
                val ring = 3.dp.toPx()
                drawRoundRect(
                    color = c.accent.copy(alpha = 0.25f),
                    topLeft = Offset(-ring / 2f, -ring / 2f),
                    size = Size(size.width + ring, size.height + ring),
                    cornerRadius = CornerRadius(12.dp.toPx() + ring / 2f),
                    style = Stroke(ring),
                )
            }
        },
        decorationBox = { inner ->
            Row(
                Modifier
                    .clip(TroyShapes.Control)
                    .background(c.controlFill)
                    .border(1.dp, if (focused) c.accent else c.hair, TroyShapes.Control)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = t.body, color = c.text3)
                    inner()
                }
            }
        },
    )
}

/** Small, dim label. Uppercase with wide tracking in English, plain in Arabic. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Troy.colors.text3) {
    val t = Troy.type
    Text(if (t.uppercaseLabels) text.uppercase() else text, style = t.label, color = color, modifier = modifier)
}
