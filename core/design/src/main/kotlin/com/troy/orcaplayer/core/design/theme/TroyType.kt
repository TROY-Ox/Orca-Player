package com.troy.orcaplayer.core.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.troy.orcaplayer.core.design.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

object TroyFonts {
    /** Roboto is Android's system sans-serif. */
    val Roboto: FontFamily = FontFamily.SansSerif

    /**
     * Inter (OFL-1.1): the one typeface for all Latin text, chosen for its clean, Apple-like
     * neutral shapes and its optical sizes. Arabic text uses Noto Sans Arabic.
     */
    val Inter: FontFamily = FontFamily(
        variable(R.font.inter, FontWeight.Normal),
        variable(R.font.inter, FontWeight.Medium),
        variable(R.font.inter, FontWeight.SemiBold),
        variable(R.font.inter, FontWeight.Bold),
    )

    val RobotoMono: FontFamily = FontFamily(
        variable(R.font.roboto_mono, FontWeight.Normal),
        variable(R.font.roboto_mono, FontWeight.Medium),
        variable(R.font.roboto_mono, FontWeight.Bold),
    )

    val NotoSansArabic: FontFamily = FontFamily(
        variable(R.font.noto_sans_arabic, FontWeight.Light),
        variable(R.font.noto_sans_arabic, FontWeight.Normal),
        variable(R.font.noto_sans_arabic, FontWeight.Medium),
        variable(R.font.noto_sans_arabic, FontWeight.SemiBold),
        variable(R.font.noto_sans_arabic, FontWeight.Bold),
    )
}

/**
 * One standard type scale for the whole app, modelled on Apple's: a large bold title, semibold
 * headings, 16sp body text and 13sp footnotes. Everything is sentence case: labels and buttons
 * are never uppercased or letter-spaced. Headings line height 1.2, body 1.4.
 */
@Immutable
data class TroyTypography(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val button: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
    /** Kept for callers; always false since the minimalist redesign (sentence case everywhere). */
    val uppercaseLabels: Boolean,
)

fun troyTypography(arabic: Boolean): TroyTypography {
    val family = if (arabic) TroyFonts.NotoSansArabic else TroyFonts.Inter
    val tight = if (arabic) 0.em else (-0.022).em
    val snug = if (arabic) 0.em else (-0.011).em
    return TroyTypography(
        display = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 1.2.em, letterSpacing = tight),
        headline = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 1.2.em, letterSpacing = tight),
        title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 1.25.em, letterSpacing = snug),
        body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 1.4.em, letterSpacing = snug),
        bodySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 1.4.em, letterSpacing = 0.em),
        button = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 1.3.em, letterSpacing = snug),
        label = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 1.3.em, letterSpacing = 0.em),
        mono = TextStyle(fontFamily = TroyFonts.RobotoMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 1.4.em, letterSpacing = 0.em),
        uppercaseLabels = false,
    )
}
