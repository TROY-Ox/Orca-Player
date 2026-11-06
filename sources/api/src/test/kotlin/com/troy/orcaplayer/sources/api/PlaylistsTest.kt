package com.troy.orcaplayer.sources.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistsTest {
    private val list = """
        #EXTM3U
        #EXTINF:-1 tvg-logo="https://x.test/logo.png" group-title="News, World",Channel One, HD
        #EXTVLCOPT:http-referrer=https://ref.test/
        #EXTVLCOPT:http-user-agent=Orca/1
        https://x.test/one.m3u8
        #EXTINF:120,Second
        two.mp4
    """.trimIndent()

    @Test fun hlsIsNotAPlaylist() {
        assertTrue(Playlists.isHls("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nseg1.ts"))
        assertFalse(Playlists.isHls(list))
        assertTrue(Playlists.looksLikeM3u(list))
    }

    @Test fun parsesEntries() {
        val e = Playlists.parse(list, "https://x.test/lists/my.m3u")
        assertEquals(2, e.size)
        assertEquals("Channel One, HD", e[0].name)
        assertEquals("https://x.test/logo.png", e[0].logo)
        assertEquals("News, World", e[0].group)
        assertEquals("https://ref.test/", e[0].headers["Referer"])
        assertEquals("Orca/1", e[0].headers["User-Agent"])
        assertEquals("https://x.test/lists/two.mp4", e[1].url)
        assertEquals(120L, e[1].durationSec)
    }

    @Test fun exportRoundTrips() {
        val e = Playlists.parse(list, "https://x.test/lists/my.m3u")
        val again = Playlists.parse(Playlists.export(e))
        assertEquals(e.map { it.url }, again.map { it.url })
        assertEquals(e.map { it.name }, again.map { it.name })
    }

    @Test fun linkKinds() {
        assertEquals(MediaLinks.Kind.HLS, MediaLinks.kindOf("https://a.test/x/master.m3u8?t=1"))
        assertEquals(MediaLinks.Kind.DASH, MediaLinks.kindOf("https://a.test/x.mpd"))
        assertEquals(MediaLinks.Kind.STREMIO_MANIFEST, MediaLinks.kindOf("stremio://a.test/manifest.json"))
        assertEquals(MediaLinks.Kind.RTSP, MediaLinks.kindOf("rtsp://cam.test/live"))
        assertEquals(MediaLinks.Kind.MEDIA, MediaLinks.kindOf("https://a.test/movie.mkv"))
        assertEquals("https://a.test/v.mp4", MediaLinks.firstLink("watch this: https://a.test/v.mp4."))
    }
}
