package com.troy.orcaplayer.core.design.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.troy.orcaplayer.core.design.theme.Troy
import java.text.NumberFormat
import java.util.Locale

/** Locale-aware numbers that follow the Western or Arabic-Indic digits setting. */
object Digits {
    fun locale(base: Locale, arabicDigits: Boolean): Locale {
        val tag = base.toLanguageTag()
        return Locale.forLanguageTag(if (arabicDigits) "$tag-u-nu-arab" else "$tag-u-nu-latn")
    }

    fun format(value: Number, base: Locale, arabicDigits: Boolean): String =
        NumberFormat.getInstance(locale(base, arabicDigits)).format(value)

    /** h:mm:ss or m:ss time code. */
    fun timecode(ms: Long, base: Locale, arabicDigits: Boolean): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val l = locale(base, arabicDigits)
        return if (h > 0) String.format(l, "%d:%02d:%02d", h, m, s) else String.format(l, "%d:%02d", m, s)
    }
}

@Composable
fun formatNumber(value: Number): String =
    Digits.format(value, LocalConfiguration.current.locales[0] ?: Locale.getDefault(), Troy.state.arabicDigits)
