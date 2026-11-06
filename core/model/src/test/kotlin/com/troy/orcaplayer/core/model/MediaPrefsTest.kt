package com.troy.orcaplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPrefsTest {
    @Test
    fun videoWinsOverFolderFieldByField() {
        val store = MediaPrefsStore()
            .updateFolder("dir:/movies") { it.copy(speed = 1.25f, aspect = "fill", decoder = "sw") }
            .updateMedia("local:1:a") { it.copy(speed = 1.5f) }
        val p = store.resolve("local:1:a", "dir:/movies")
        assertEquals(1.5f, p.speed)
        assertEquals("fill", p.aspect)
        assertEquals("sw", p.decoder)
        assertNull(p.subtitleDelayMs)
        // Another video in the folder gets the folder values only.
        assertEquals(1.25f, store.resolve("local:2:b", "dir:/movies").speed)
        // Nothing saved: everything stays null, so profile and app defaults apply.
        assertTrue(store.resolve("x", null).isEmpty)
    }

    @Test
    fun resetAndEmptyEntriesAreRemoved() {
        val store = MediaPrefsStore().updateMedia("k") { it.copy(zoom = 2f) }
        assertEquals(1, store.perMedia.size)
        assertTrue(store.resetMedia("k").perMedia.isEmpty())
        assertTrue(store.updateMedia("k") { MediaPrefs() }.perMedia.isEmpty())
    }

    @Test
    fun toolsLayoutDefaults() {
        assertTrue(ToolsLayout.Default.tools.all { it.inTab })
        assertTrue(ToolId.SCREENSHOT in ToolsLayout.Default.panel)
        assertTrue(ToolId.SCREENSHOT !in ToolsLayout.Default.tools)
    }
}
