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
    fun defaultsToBlue() {
        val s = themeState(SettingsValues.Defaults, SystemSignals(), isArabic = false)
        assertEquals(TroyAccent.BLUE, s.accent)
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

    @Test
    fun lightModeStaysDarkOverVideo() {
        val v = SettingsValues.Defaults.with(com.troy.orcaplayer.core.settings.Settings.ThemeMode, "light")
        assertEquals(com.troy.orcaplayer.core.design.theme.ThemeMode.LIGHT, themeState(v, SystemSignals(), false).mode)
        assertEquals(com.troy.orcaplayer.core.design.theme.ThemeMode.DARK, themeState(v, SystemSignals(), false, videoSurface = true).mode)
        val auto = SettingsValues.Defaults.with(com.troy.orcaplayer.core.settings.Settings.ThemeMode, "auto")
        assertEquals(com.troy.orcaplayer.core.design.theme.ThemeMode.LIGHT, themeState(auto, SystemSignals(systemDark = false), false).mode)
    }
}
