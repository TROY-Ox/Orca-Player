package com.troy.orcaplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NavLayoutTest {
    @Test
    fun oldLayoutsGainLiveTvAfterHome() {
        val old = NavLayout(listOf(MainTab.HOME, MainTab.LOCAL, MainTab.ADDONS, MainTab.SEARCH, MainTab.TOOLS).map { NavTabConfig(it) })
        val now = old.withNewTabs()
        assertEquals(listOf(MainTab.HOME, MainTab.LIVE, MainTab.LOCAL, MainTab.ADDONS, MainTab.SEARCH, MainTab.TOOLS), now.tabs.map { it.tab })
    }

    @Test
    fun completeLayoutsAreUnchanged() {
        assertEquals(NavLayout.Default, NavLayout.Default.withNewTabs())
    }
}
