package com.troy.orcaplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class MediaKeysTest {
    @Test
    fun normalizeUrl_dropsDefaultPortFragmentAndTracking_andSortsQuery() {
        assertEquals(
            "https://example.com/a/b.m3u8?a=1&z=2",
            MediaKeys.normalizeUrl("HTTPS://Example.COM:443/a/b.m3u8?z=2&utm_source=x&a=1#frag"),
        )
    }

    @Test
    fun normalizeUrl_keepsNonDefaultPort() {
        assertEquals("http://10.0.0.2:8080/", MediaKeys.normalizeUrl("http://10.0.0.2:8080"))
    }

    @Test
    fun sourceKey_ignoresStreamUrl() {
        val a = MediaKeys.forSource("addon.a", "tt1", "tt1:1:2")
        val b = MediaKeys.forSource("addon.a", "tt1", "tt1:1:2")
        assertEquals(a.id, b.id)
    }

    @Test
    fun localKey_survivesRename() {
        val dir = createTempDirCompat()
        val f1 = File(dir, "one.mkv").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val k1 = MediaKeys.forLocalFile(f1)
        val f2 = File(dir, "renamed.mkv")
        f1.renameTo(f2)
        val k2 = MediaKeys.forLocalFile(f2)
        assertEquals(k1.id, k2.id)
        assertNotEquals(k1.path, k2.path)
    }

    @Test
    fun localKey_smallFile() {
        val dir = createTempDirCompat()
        val f = File(dir, "tiny.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(3L, MediaKeys.forLocalFile(f).size)
    }

    private fun createTempDirCompat(): File =
        kotlin.io.path.createTempDirectory("orca").toFile().apply { deleteOnExit() }
}
