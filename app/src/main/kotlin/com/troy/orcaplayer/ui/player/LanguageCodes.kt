package com.troy.orcaplayer.ui.player

import java.util.Locale
import java.util.MissingResourceException

/** Language codes from sources come as "ar", "ara", "arb", "ar-SA" or "Arabic"; this compares them. */
object LanguageCodes {
    private val threeToTwo: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        Locale.getISOLanguages().forEach { two ->
            try {
                m[Locale(two).isO3Language.lowercase(Locale.ROOT)] = two
            } catch (_: MissingResourceException) {
            }
        }
        // Bibliographic codes that differ from the terminology ones, plus common extras.
        m += mapOf(
            "arb" to "ar", "chi" to "zh", "fre" to "fr", "ger" to "de", "per" to "fa", "dut" to "nl", "gre" to "el",
            "cze" to "cs", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my",
            "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy",
            "pob" to "pt", "spn" to "es",
        )
        m
    }

    private val names: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        Locale.getISOLanguages().forEach { two ->
            val l = Locale(two)
            m[l.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT)] = two
            m[l.getDisplayLanguage(l).lowercase(l)] = two
        }
        m
    }

    fun toTwoLetter(code: String?): String {
        val c = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_').orEmpty()
        return when {
            c.length == 2 -> c
            c.length == 3 -> threeToTwo[c] ?: c
            c.isEmpty() -> ""
            else -> names[c] ?: c
        }
    }

    fun matches(code: String?, preferred: String): Boolean {
        val a = toTwoLetter(code)
        return a.isNotEmpty() && a == toTwoLetter(preferred)
    }
}
