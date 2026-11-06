package com.troy.orcaplayer.core.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Radius: 18 (panels), 12 (controls, cards), 8 (chips, art, alerts). */
object TroyShapes {
    val Panel = RoundedCornerShape(18.dp)
    val Control = RoundedCornerShape(12.dp)
    val Chip = RoundedCornerShape(8.dp)
    val Pill = RoundedCornerShape(50)
}

object TroySpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 18.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Minimum touch target. */
    val touch = 48.dp
}
