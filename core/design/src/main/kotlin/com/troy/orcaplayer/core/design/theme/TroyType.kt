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
 * Type scale from the theme: large text slightly negative tracking, body near zero,
 * small uppercase labels wide. Headings line height 1.1, body 1.5.
 *
 * In Arabic, labels are never uppercased and never get letter spacing.
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
    /** Labels and buttons are uppercased (English) or not (Arabic). */
    val uppercaseLabels: Boolean,
)

fun troyTypography(arabic: Boolean): TroyTypography {
    val family = if (arabic) TroyFonts.NotoSansArabic else TroyFonts.Roboto
    val tight = if (arabic) 0.em else (-0.02).em
    val wide = if (arabic) 0.em else 0.16.em
    val buttonTracking = if (arabic) 0.em else 0.14.em
    return TroyTypography(
        display = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 1.1.em, letterSpacing = tight),
        headline = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 1.1.em, letterSpacing = tight),
        title = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 1.25.em, letterSpacing = if (arabic) 0.em else (-0.01).em),
        body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 1.5.em, letterSpacing = 0.em),
        bodySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 1.5.em, letterSpacing = 0.em),
        button = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 1.3.em, letterSpacing = buttonTracking),
        label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 1.4.em, letterSpacing = wide),
        mono = TextStyle(fontFamily = TroyFonts.RobotoMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 1.4.em, letterSpacing = 0.em),
        uppercaseLabels = !arabic,
    )
}
