package com.troy.orcaplayer.core.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import android.os.Build
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.core.design.motion.TroySprings
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Translucent panel, faint diagonal sheen, 1px hairline. Bigger surfaces get deeper shadows; in
 * day mode the shadow is soft and light, as on Apple's cards.
 */
fun Modifier.glass(shape: Shape = TroyShapes.Panel, elevation: Dp = 8.dp): Modifier = composed {
    val g = Troy.glass
    val light = Troy.colors.isLight
    val shadowColor = if (light) Color.Black.copy(alpha = 0.18f) else Color.Black
    this
        .shadow(if (light) elevation / 2 else elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        .background(g.panel)
        .background(Brush.linearGradient(0f to g.sheen, 0.4f to Color.Transparent))
        .border(1.dp, g.border, shape)
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = TroyShapes.Panel,
    elevation: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, elevation).padding(contentPadding), content = content)
}

/**
 * Panels materialize with fade, scale 0.96 to 1 and a 14dp rise (plus a 10dp blur on Android 12+
 * outside the lite tier), staggered by 70 ms per [index].
 *
 * Everything runs in one graphics layer: the blur is a render effect with decal edges (no hard,
 * clipped rectangle around the content), it clears on an ease-out curve ahead of the fade, and it is
 * dropped below half a pixel, so there is no shimmer or last-frame pop as it settles.
 */
fun Modifier.materialize(index: Int = 0): Modifier = composed {
    val motion = Troy.motion
    val density = LocalDensity.current
    val progress = remember { Animatable(if (motion.reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (motion.reduceMotion) {
            progress.snapTo(1f)
        } else {
            delay((index * motion.staggerMillis).toLong())
            launch { progress.animateTo(1f, motion.springSpec(TroySprings.Materialize)) }
        }
    }
    val rise = with(density) { 14.dp.toPx() }
    val maxBlur = with(density) { 10.dp.toPx() }
    val blurOn = motion.allowBlur && Build.VERSION.SDK_INT >= 31
    this.graphicsLayer {
        val p = progress.value.coerceIn(0f, 1f)
        alpha = p
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
        translationY = rise * (1f - p)
        val remaining = 1f - p
        val radius = maxBlur * remaining * remaining
        renderEffect = if (blurOn && radius >= 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
    }
}
