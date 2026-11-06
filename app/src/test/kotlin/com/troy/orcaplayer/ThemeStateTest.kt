package com.troy.orcaplayer

import com.troy.orcaplayer.core.design.theme.TroyAccent
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.system.SystemSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStateTest {
    @Test
    fun defaultsToTeal() {
        val s = themeState(SettingsValues.Defaults, SystemSignals(), isArabic = false)
        assertEquals(TroyAccent.TEAL, s.accent)
        assertFalse(s.reduceMotion)
        assertFalse(s.arabicDigits)
    }

    @Test
    fun followsSystemSignals() {
        val s = themeState(SettingsValues.Defaults, SystemSignals(removeAnimations = true, highContrast = true, isTv = true), isArabic = true)
        assertTrue(s.reduceMotion)
        assertTrue(s.highContrast)
        assertTrue(s.lite)
        assertTrue(s.arabicDigits)
    }
}
