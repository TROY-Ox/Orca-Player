package com.troy.orcaplayer.sources.cloudstream

import com.troy.orcaplayer.sources.cloudstream.host.PluginRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingClassTest {
    @Test
    fun namesTheMissingClass() {
        val e = NoClassDefFoundError("Failed resolution of: Lcom/lagradost/cloudstream3/utils/AppContextUtils;")
        assertEquals("com.lagradost.cloudstream3.utils.AppContextUtils", PluginRuntime.missingClass(e))
        assertTrue(PluginRuntime.describe(e).contains("com.lagradost.cloudstream3.utils.AppContextUtils"))
    }

    @Test
    fun followsCauses() {
        val e = RuntimeException("load failed", ClassNotFoundException("com.lagradost.cloudstream3.ui.Foo"))
        assertEquals("com.lagradost.cloudstream3.ui.Foo", PluginRuntime.missingClass(e))
    }
}
