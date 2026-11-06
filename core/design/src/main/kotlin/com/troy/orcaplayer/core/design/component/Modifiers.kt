package com.troy.orcaplayer.core.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.core.design.motion.TroySprings
import com.troy.orcaplayer.core.design.theme.Troy

/**
 * The theme's focus style: accent border plus a soft 3dp ring. Put it before the clickable
 * or focusable modifier so it sees that element's focus.
 */
fun Modifier.troyFocusRing(cornerRadius: Dp = 12.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val accent = Troy.colors.accent
    this
        .onFocusChanged { focused = it.isFocused }
        .drawBehind {
            if (focused) {
                val ring = 3.dp.toPx()
                val r = cornerRadius.toPx()
                drawRoundRect(
                    color = accent.copy(alpha = 0.25f),
                    topLeft = Offset(-ring / 2f - 1.dp.toPx(), -ring / 2f - 1.dp.toPx()),
                    size = Size(size.width + ring + 2.dp.toPx(), size.height + ring + 2.dp.toPx()),
                    cornerRadius = CornerRadius(r + ring / 2f),
                    style = Stroke(width = ring),
                )
                val border = 1.5.dp.toPx()
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(border / 2f, border / 2f),
                    size = Size(size.width - border, size.height - border),
                    cornerRadius = CornerRadius(r),
                    style = Stroke(width = border),
                )
            }
        }
}

/** Feedback on press: scales to 0.97 instantly, springs back on release. Off with reduce motion. */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
    val motion = Troy.motion
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed, motion.reduceMotion) {
        when {
            motion.reduceMotion -> scale.snapTo(1f)
            pressed -> scale.snapTo(0.97f)
            else -> scale.animateTo(1f, motion.springSpec(TroySprings.Default))
        }
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
