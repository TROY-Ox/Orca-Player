package com.troy.orcaplayer.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** True on Android TV and Google TV (21.1). */
val LocalIsTv = staticCompositionLocalOf { false }

/** The 10-foot interface (21.1): larger text and cards, and safe margins for overscan. */
object TenFoot {
    /** Text and cards are drawn this much larger on a TV than the same layout on a tablet. */
    const val SCALE = 1.2f

    /** The usual 5% title-safe area at the edges of a TV picture. */
    val OverscanHorizontal = 48.dp
    val OverscanVertical = 27.dp

    /** Touch-only settings that make no sense with a remote (21.1). */
    fun hiddenOnTv(key: String): Boolean = when {
        key == "gestures.skip_seconds" -> false
        key.startsWith("gestures.") -> true
        key.startsWith("player.floating") -> true
        key == "sources.clipboard" -> true
        else -> false
    }
}

@Composable
fun TenFootHost(isTv: Boolean, content: @Composable () -> Unit) {
    if (!isTv) {
        CompositionLocalProvider(LocalIsTv provides false, content = content)
        return
    }
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalIsTv provides true,
        LocalDensity provides Density(d.density * TenFoot.SCALE, d.fontScale),
    ) {
        Box(Modifier.fillMaxSize().background(com.troy.orcaplayer.core.design.theme.Troy.colors.bg).padding(horizontal = TenFoot.OverscanHorizontal, vertical = TenFoot.OverscanVertical)) { content() }
    }
}

/** The 10-foot scale without overscan margins, for the full-screen player. */
@Composable
fun TenFootScale(isTv: Boolean, content: @Composable () -> Unit) {
    if (!isTv) {
        content()
        return
    }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalIsTv provides true, LocalDensity provides Density(d.density * TenFoot.SCALE, d.fontScale), content = content)
}
