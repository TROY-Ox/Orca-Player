package com.troy.orcaplayer.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendToTvTest {
    @Test
    fun parsesFormBodies() {
        val got = SendToTvServer.parseForm("url=https%3A%2F%2Fexample.com%2Fmanifest.json&pass=a+b%26c&empty=")
        assertEquals("https://example.com/manifest.json", got["url"])
        assertEquals("a b&c", got["pass"])
        assertEquals("", got["empty"])
    }

    @Test
    fun escapesHtml() {
        assertEquals("&lt;b&gt;&quot;x&quot; &amp; &#39;y&#39;", SendToTvServer.esc("<b>\"x\" & 'y'"))
    }

    @Test
    fun touchOnlySettingsAreHiddenOnTv() {
        assertTrue(TenFoot.hiddenOnTv("gestures.double_tap"))
        assertTrue(TenFoot.hiddenOnTv("player.floating"))
        assertTrue(TenFoot.hiddenOnTv("sources.clipboard"))
        assertFalse(TenFoot.hiddenOnTv("gestures.skip_seconds"))
        assertFalse(TenFoot.hiddenOnTv("player.speed"))
    }

    @Test
    fun qrCodeEncodesAnAddress() {
        val m = SendToTvServer.qr("http://192.168.1.20:41234/0123456789abcdef01234567")
        assertTrue(m.width >= 25)
        assertEquals(m.width, m.height)
    }
}
