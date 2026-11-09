package com.troy.orcaplayer.core.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp

/** TROY Enhanced Theme v1.0 base colours (section 2). Fixed: they never follow the accent. */
object TroyBase {
    val Bg = Color(0xFF0D0D0C)
    val BgAmoled = Color(0xFF000000)
    val Glass = Color(0xC70D0D0C) // rgba(13,13,12,.78)
    val GlassSolid = Color(0xFF171716)
    val GlassHighContrast = Color(0xFF121211)
    val Text = Color(0xFFF6F5F2)
    val Text2 = Color(0xFFBAB9B3)
    val Text3 = Color(0xFF9E9D96)
    val Hair = Color(0x14FFFFFF) // rgba(255,255,255,.08)
    val HairHighContrast = Color(0x66FFFFFF) // rgba(255,255,255,.4)
    val Sheen = Color(0x0DFFFFFF) // rgba(255,255,255,.05)
    val ControlFill = Color(0x0AFFFFFF) // rgba(255,255,255,.04)

    /** Light (day) mode, after Apple's grouped system backgrounds: soft grey canvas, white cards, near-black text. */
    object Light {
        val Bg = Color(0xFFF5F5F7)
        val Glass = Color(0xEBFFFFFF) // white at 92%
        val GlassSolid = Color(0xFFFFFFFF)
        val GlassHighContrast = Color(0xFFFFFFFF)
        val Text = Color(0xFF1D1D1F)
        val Text2 = Color(0xFF515154)
        val Text3 = Color(0xFF6E6E73)
        val Hair = Color(0x1F000000) // black at 12%
        val HairHighContrast = Color(0x80000000)
        val Sheen = Color(0x80FFFFFF)
        val ControlFill = Color(0x0F000000) // black at 6%
        val Ok = Color(0xFF1E9E5A)
        val Danger = Color(0xFFD70015)
        val Warn = Color(0xFFB25000)
        val Info = Color(0xFF55606A)
    }

    val Ok = Color(0xFF5BD6A0)
    val Danger = Color(0xFFF0625B)
    val Warn = Color(0xFFFFC048)
    val Info = Color(0xFF8FA3AD)
}

@Immutable
data class AccentColors(val accent: Color, val hover: Color, val pressed: Color, val onAccent: Color)

/** The ten theme accents. Blue is the default since the minimalist redesign. */
enum class TroyAccent(val key: String, val colors: AccentColors) {
    TEAL("teal", AccentColors(Color(0xFF14B8A6), Color(0xFF2DD4BF), Color(0xFF0E8F81), Color(0xFF0D0D0C))),
    RED("red", AccentColors(Color(0xFFF03E3E), Color(0xFFFF6B6B), Color(0xFFC92A2A), Color(0xFFFFFFFF))),
    YELLOW("yellow", AccentColors(Color(0xFFFFD23F), Color(0xFFFFE070), Color(0xFFD9AE1F), Color(0xFF0D0D0C))),
    BLUE("blue", AccentColors(Color(0xFF0A84FF), Color(0xFF409CFF), Color(0xFF0071E3), Color(0xFFFFFFFF))),
    CYAN("cyan", AccentColors(Color(0xFF22D3EE), Color(0xFF67E8F9), Color(0xFF0EA5C4), Color(0xFF0D0D0C))),
    SILVER("silver", AccentColors(Color(0xFFC9CED6), Color(0xFFE3E7EC), Color(0xFFA5ABB5), Color(0xFF0D0D0C))),
    GREEN("green", AccentColors(Color(0xFF2BFF88), Color(0xFF6BFFAD), Color(0xFF18D96A), Color(0xFF0D0D0C))),
    VIOLET("violet", AccentColors(Color(0xFF8B5CF6), Color(0xFFA78BFA), Color(0xFF6D3FD9), Color(0xFFFFFFFF))),
    PINK("pink", AccentColors(Color(0xFFFF2E93), Color(0xFFFF6BB5), Color(0xFFD41876), Color(0xFFFFFFFF))),
    ORANGE("orange", AccentColors(Color(0xFFFF8A1F), Color(0xFFFFA94D), Color(0xFFD96C0A), Color(0xFF0D0D0C))),
    ;

    companion object {
        val Default = BLUE
        fun fromKey(key: String?): TroyAccent? = entries.firstOrNull { it.key == key }
    }
}

object AccentMath {
    private val Dark = TroyBase.Bg
    private val White = Color(0xFFFFFFFF)

    /** WCAG contrast ratio between two colours. */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /** For a custom accent: hover lighter, pressed darker, on-accent dark or white by contrast. */
    fun derive(base: Color): AccentColors {
        val hover = lerp(base, White, 0.22f)
        val pressed = lerp(base, Color.Black, 0.2f)
        val on = if (contrast(base, Dark) >= contrast(base, White)) Dark else White
        return AccentColors(base.copy(alpha = 1f), hover, pressed, on)
    }
}

/** Resolved colours for the current theme state. Screens read these, never raw values. */
@Immutable
data class TroyColors(
    val bg: Color,
    val glass: Color,
    val glassSolid: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val hair: Color,
    val sheen: Color,
    val controlFill: Color,
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val ok: Color = TroyBase.Ok,
    val danger: Color = TroyBase.Danger,
    val warn: Color = TroyBase.Warn,
    val info: Color = TroyBase.Info,
    /** Gradient scrim used over video, where glass can't blur. */
    val scrim: Color = Color(0xB3000000),
    /** Day mode: dark text on light surfaces. Video surfaces stay dark either way. */
    val isLight: Boolean = false,
)
