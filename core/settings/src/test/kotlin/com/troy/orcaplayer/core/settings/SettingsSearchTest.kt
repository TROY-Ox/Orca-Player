package com.troy.orcaplayer.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val entries = listOf(
        SearchEntry("a", "Accent colour", "Main colour of buttons", "Appearance", "color theme teal"),
        SearchEntry("b", "Reduce motion", "Turn off springs", "Accessibility", "animation"),
        SearchEntry("c", "اللغة", "لغة التطبيق", "اللغة والمنطقة", "عربي انجليزي"),
    )

    @Test
    fun matchesKeywords() {
        assertEquals(listOf("a"), SettingsSearch.search("teal", entries).map { it.key })
    }

    @Test
    fun titleRanksFirst() {
        val result = SettingsSearch.search("motion", entries)
        assertEquals("b", result.first().key)
    }

    @Test
    fun allWordsMustMatch() {
        assertTrue(SettingsSearch.search("accent animation", entries).isEmpty())
    }

    @Test
    fun arabicIgnoresHamzaAndDiacritics() {
        assertEquals(listOf("c"), SettingsSearch.search("إنجليزي", entries).map { it.key })
        assertEquals(listOf("c"), SettingsSearch.search("اللُّغة", entries).map { it.key })
    }

    @Test
    fun choiceDecodeFallsBackToDefault() {
        assertEquals("blue", Settings.Accent.decode("not-a-colour"))
        assertEquals(0xFF112233, Settings.CustomAccent.decode(Settings.CustomAccent.encode(0xFF112233)))
    }

    @Test
    fun keysAreUnique() {
        assertEquals(Settings.all.size, Settings.all.map { it.key }.toSet().size)
    }
}
