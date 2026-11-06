package com.troy.orcaplayer.core.design.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes

/** Loading placeholder. Static with reduce motion. */
fun Modifier.shimmer(): Modifier = composed {
    val c = Troy.colors
    val base = c.controlFill
    val highlight = c.text.copy(alpha = 0.08f)
    if (Troy.motion.reduceMotion) {
        background(base)
    } else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val x by transition.animateFloat(
            initialValue = -600f,
            targetValue = 1200f,
            animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
            label = "shimmerX",
        )
        background(Brush.linearGradient(listOf(base, highlight, base), start = Offset(x, 0f), end = Offset(x + 600f, 300f)))
    }
}

@Composable
fun ShimmerBlock(modifier: Modifier = Modifier) {
    Box(modifier.clip(TroyShapes.Control).shimmer())
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Troy.colors
    val t = Troy.type
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).materialize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(72.dp).clip(TroyShapes.Panel).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(36.dp))
            }
            Text(title, style = t.title, color = c.text, textAlign = TextAlign.Center)
            Text(message, style = t.bodySmall, color = c.text2, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                TroyButton(actionLabel, onAction, kind = ButtonKind.PRIMARY, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun ErrorState(title: String, message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(Icons.Outlined.ErrorOutline, title, message, modifier, retryLabel, onRetry)
}

@Composable
fun OfflineState(title: String, message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(Icons.Outlined.CloudOff, title, message, modifier, retryLabel, onRetry)
}

@Composable
fun LoadingList(rows: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) { ShimmerBlock(Modifier.fillMaxWidth().height(64.dp)) }
    }
}
