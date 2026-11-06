package com.troy.orcaplayer.core.design.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import com.troy.orcaplayer.core.design.motion.TroyMotion

enum class ThemeMode { DARK, AMOLED }

/** Everything the theme needs from settings and the system. */
@Immutable
data class ThemeState(
    val accent: TroyAccent = TroyAccent.Default,
    /** Overrides [accent] when set. */
    val customAccent: Color? = null,
    val mode: ThemeMode = ThemeMode.DARK,
    val frostedGlass: Boolean = false,
    val solidPanels: Boolean = false,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val lite: Boolean = false,
    /** Western (false) or Arabic-Indic (true) digits. */
    val arabicDigits: Boolean = false,
    /** Mirror the media timeline in RTL. Off by default: media controls are not mirrored. */
    val mirrorTimeline: Boolean = false,
) {
    val accentColors: AccentColors get() = customAccent?.let(AccentMath::derive) ?: accent.colors
}

/** Glass behaviour derived from the theme state. */
@Immutable
data class GlassStyle(
    val panel: Color,
    val border: Color,
    val sheen: Color,
    /** Blur radius in dp for frosted glass over app content (Android 12+), 0 when off. */
    val blurDp: Float,
)

val LocalTroyColors = staticCompositionLocalOf { darkTroyColors(ThemeState()) }
val LocalTroyType = staticCompositionLocalOf { troyTypography(arabic = false) }
val LocalTroyMotion = staticCompositionLocalOf { TroyMotion() }
val LocalGlassStyle = staticCompositionLocalOf { glassStyleFor(ThemeState()) }
val LocalThemeState = staticCompositionLocalOf { ThemeState() }

object Troy {
    val colors: TroyColors @Composable get() = LocalTroyColors.current
    val type: TroyTypography @Composable get() = LocalTroyType.current
    val motion: TroyMotion @Composable get() = LocalTroyMotion.current
    val glass: GlassStyle @Composable get() = LocalGlassStyle.current
    val state: ThemeState @Composable get() = LocalThemeState.current
}

fun darkTroyColors(state: ThemeState, accent: AccentColors = state.accentColors): TroyColors = TroyColors(
    bg = if (state.mode == ThemeMode.AMOLED) TroyBase.BgAmoled else TroyBase.Bg,
    glass = TroyBase.Glass,
    glassSolid = TroyBase.GlassSolid,
    text = TroyBase.Text,
    text2 = TroyBase.Text2,
    text3 = TroyBase.Text3,
    hair = if (state.highContrast) TroyBase.HairHighContrast else TroyBase.Hair,
    sheen = TroyBase.Sheen,
    controlFill = TroyBase.ControlFill,
    accent = accent.accent,
    accentHover = accent.hover,
    accentPressed = accent.pressed,
    onAccent = accent.onAccent,
)

fun glassStyleFor(state: ThemeState): GlassStyle = GlassStyle(
    panel = when {
        state.highContrast -> TroyBase.GlassHighContrast
        state.solidPanels -> TroyBase.GlassSolid
        else -> TroyBase.Glass
    },
    border = if (state.highContrast) TroyBase.HairHighContrast else TroyBase.Hair,
    sheen = if (state.solidPanels || state.highContrast) Color.Transparent else TroyBase.Sheen,
    blurDp = if (state.frostedGlass && !state.solidPanels && !state.lite) 18f else 0f,
)

@Composable
fun OrcaTheme(state: ThemeState = ThemeState(), content: @Composable () -> Unit) {
    val target = state.accentColors
    // Accent changes cross-fade over about 350 ms.
    val spec = if (state.reduceMotion) snap<Color>() else tween<Color>(350)
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val hover by animateColorAsState(target.hover, spec, label = "accentHover")
    val pressed by animateColorAsState(target.pressed, spec, label = "accentPressed")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "onAccent")
    val colors = darkTroyColors(state, AccentColors(accent, hover, pressed, onAccent))

    val layoutDirectionIsArabic = LocalConfiguration.current.locales[0]?.language == "ar"
    val type = remember(layoutDirectionIsArabic) { troyTypography(layoutDirectionIsArabic) }
    val motion = remember(state.reduceMotion, state.lite) { TroyMotion(state.reduceMotion, state.lite) }
    val glass = remember(state) { glassStyleFor(state) }

    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentPressed,
        onPrimaryContainer = colors.text,
        secondary = colors.accentHover,
        onSecondary = colors.onAccent,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.bg,
        onSurface = colors.text,
        surfaceVariant = glass.panel,
        onSurfaceVariant = colors.text2,
        surfaceContainer = TroyBase.GlassSolid,
        surfaceContainerHigh = TroyBase.GlassSolid,
        surfaceContainerHighest = TroyBase.GlassSolid,
        surfaceContainerLow = TroyBase.GlassSolid,
        surfaceContainerLowest = colors.bg,
        outline = colors.hair,
        outlineVariant = colors.hair,
        error = colors.danger,
        onError = TroyBase.Bg,
    )

    CompositionLocalProvider(
        LocalTroyColors provides colors,
        LocalTroyType provides type,
        LocalTroyMotion provides motion,
        LocalGlassStyle provides glass,
        LocalThemeState provides state,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
