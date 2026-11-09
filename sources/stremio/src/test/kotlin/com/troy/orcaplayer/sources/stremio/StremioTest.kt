package com.troy.orcaplayer.sources.stremio

import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.StreamKind
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.sources.api.PageRequest
import com.troy.orcaplayer.sources.api.SourceException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

private val MANIFEST = """
{
  "id": "org.test.open", "version": "1.2.0", "name": "Open Films",
  "types": ["movie", "series"],
  "resources": ["catalog", {"name": "meta", "types": ["movie","series"], "idPrefixes": ["of:"]}, {"name":"stream","types":["movie","series"],"idPrefixes":["of:","tt"]}, "subtitles"],
  "idPrefixes": ["of:"],
  "catalogs": [
    {"type": "movie", "id": "films", "name": "Films", "extra": [{"name": "genre", "options": ["Animation", "Drama"]}, {"name": "skip"}, {"name": "search"}]},
    {"type": "series", "id": "byGenre", "name": "By genre", "extra": [{"name": "genre", "isRequired": "true", "options": ["Animation"]}]},
    {"type": "movie", "id": "old", "name": "Old style", "extraSupported": ["search"], "extraRequired": ["search"]},
    {"type": 5}
  ],
  "behaviorHints": {"p2p": 0, "adult": "false", "configurable": true},
  "unknown": {"x": 1}
}
"""

class ManifestTest {
    @Test fun parsesLeniently() {
        val m = AddonManifest.parse(MANIFEST)
        assertEquals("org.test.open", m.id)
        assertEquals(3, m.catalogs.size) // the catalog without an id is dropped, not the whole manifest
        assertTrue(m.behaviorHints.configurable)
        assertFalse(m.behaviorHints.p2p)
        assertTrue(m.catalogs[1].requiredExtras.isNotEmpty())
        assertTrue(m.catalogs[2].searchOnly)
        assertEquals(listOf("Animation", "Drama"), m.catalogs[0].genres)
    }

    @Test fun respectsIdPrefixesAndTypes() {
        val m = AddonManifest.parse(MANIFEST)
        assertTrue(m.supports("meta", "movie", "of:bbb"))
        assertFalse(m.supports("meta", "movie", "tt0111161"))
        assertTrue(m.supports("stream", "series", "tt0903747:1:2"))
        assertFalse(m.supports("stream", "channel", "of:x"))
        assertTrue(m.supports("subtitles", "movie", "of:x"))
        assertFalse(m.supports("addon_catalog", "movie", "x"))
    }

    @Test fun rejectsNonManifests() {
        try { AddonManifest.parse("<html>"); fail() } catch (_: IllegalArgumentException) {}
        try { AddonManifest.parse("{\"name\":\"x\"}"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun changesBetweenVersions() {
        val a = AddonManifest.parse(MANIFEST)
        val b = a.copy(version = "1.3.0", catalogs = a.catalogs.drop(1) + CatalogDecl("movie", "new", "New"))
        val c = ManifestChanges.between(a, b)
        assertEquals(listOf("New"), c.addedCatalogs)
        assertEquals(listOf("Films"), c.removedCatalogs)
    }
}

class UrlsTest {
    @Test fun normalizes() {
        assertEquals("https://a.test/x/manifest.json", StremioUrls.normalizeManifestUrl("stremio://a.test/x/manifest.json"))
        assertEquals("https://a.test/manifest.json", StremioUrls.normalizeManifestUrl("a.test"))
        assertEquals("https://a.test/cfg/manifest.json", StremioUrls.normalizeManifestUrl(" https://a.test/cfg/ "))
        assertNull(StremioUrls.normalizeManifestUrl("hello world"))
        assertNull(StremioUrls.normalizeManifestUrl("ftp://a.test/manifest.json"))
    }

    @Test fun buildsResourceUrls() {
        val base = StremioUrls.baseOf("https://a.test/abc/manifest.json")
        assertEquals("https://a.test/abc", base)
        assertEquals("https://a.test/abc/meta/series/tt1%3A1%3A2.json", StremioUrls.resource(base, "meta", "series", "tt1:1:2"))
        assertEquals(
            "https://a.test/abc/catalog/movie/films/genre=Science%20Fiction&skip=100.json",
            StremioUrls.resource(base, "catalog", "movie", "films", linkedMapOf("genre" to "Science Fiction", "skip" to "100")),
        )
        assertEquals("https://a.test/abc/catalog/movie/films/search=%D8%B9%D8%B1%D8%A8.json", StremioUrls.resource(base, "catalog", "movie", "films", mapOf("search" to "عرب")))
    }
}

class LocalAddonUrlTest {
    @Test fun stremioLinksToLanAddonsUseHttp() {
        assertEquals("http://192.168.1.20:7000/manifest.json", StremioUrls.normalizeManifestUrl("stremio://192.168.1.20:7000/manifest.json"))
        assertEquals("http://localhost:11470/manifest.json", StremioUrls.normalizeManifestUrl("stremio://localhost:11470/manifest.json"))
        assertEquals("https://addon.example.com/manifest.json", StremioUrls.normalizeManifestUrl("stremio://addon.example.com/manifest.json"))
        assertEquals("http://10.0.0.5/manifest.json", StremioUrls.normalizeManifestUrl("http://10.0.0.5/manifest.json"))
        assertTrue(StremioUrls.isLocalHost("nas.local:8080"))
        assertTrue(StremioUrls.isLocalHost("172.20.1.1"))
        assertFalse(StremioUrls.isLocalHost("172.32.1.1"))
        assertFalse(StremioUrls.isLocalHost("8.8.8.8"))
    }

    @Test fun torrentStreamsKeepFileAndTrackers() {
        val text = """{"streams":[{"infoHash":"0123456789abcdef0123456789abcdef01234567","fileIdx":2,"sources":["tracker:udp://t.test:80/announce","dht:0123"]}]}"""
        val s = StremioParse.streams(text, SourceRef(SourceKind.STREMIO_ADDON, "a", "A")).single()
        assertEquals(StreamKind.TORRENT, s.kind)
        assertEquals(2, s.fileIndex)
        assertEquals(listOf("udp://t.test:80/announce"), s.trackers)
    }
}

class ParseTest {
    private val src = SourceRef(SourceKind.STREMIO_ADDON, "a", "A")

    @Test fun oneBadMetaDoesNotDropTheCatalog() {
        val text = """{"metas":[{"id":"of:1","type":"movie","name":"Big Buck Bunny","releaseInfo":2008,"genres":"Animation, Comedy","imdbRating":7.2},{"type":"movie"},null,5,{"id":"of:2","name":"Sintel"}]}"""
        val m = StremioParse.metas(text, src, TitleType.MOVIE)
        assertEquals(listOf("of:1", "of:2"), m.map { it.id })
        assertEquals(2008, m[0].year)
        assertEquals(listOf("Animation", "Comedy"), m[0].genres)
        assertEquals("7.2", m[0].rating)
    }

    @Test fun seriesEpisodes() {
        val text = """{"meta":{"id":"tt9","type":"series","name":"S","videos":[{"id":"tt9:2:1","season":"2","episode":1,"title":"Two"},{"id":"tt9:1:1","season":1,"number":"1","released":"2020-01-01T00:00:00Z"},{"season":1}],
            "links":[{"name":"Jane","category":"Cast"}],"trailers":[{"source":"yt1","type":"Trailer"}]}}"""
        val t = StremioParse.metaDetail(text, src, TitleType.SERIES)!!
        assertEquals(listOf("tt9:1:1", "tt9:2:1"), t.episodes.map { it.id })
        assertEquals("tt9", t.imdbId)
        assertEquals(listOf("Jane"), t.cast)
        assertEquals(listOf("yt1"), t.trailers)
    }

    @Test fun streamKinds() {
        val text = """{"streams":[
            {"url":"https://cdn.test/a.mp4","name":"Open\n1080p","title":"Big Buck Bunny 1080p","behaviorHints":{"bingeGroup":"open-1080","filename":"bbb.mp4","videoSize":"12345","proxyHeaders":{"request":{"Referer":"https://ref.test","X-N":5}}},
             "subtitles":[{"url":"https://cdn.test/ar.srt","lang":"ara"},{"url":"data:x"}]},
            {"ytId":"abc"},{"externalUrl":"https://site.test/watch"},{"infoHash":"deadbeef","fileIdx":"0"},{"url":"magnet:?xt=1"},{"name":"nothing"}]}"""
        val s = StremioParse.streams(text, src)
        assertEquals(listOf(StreamKind.URL, StreamKind.YOUTUBE, StreamKind.EXTERNAL, StreamKind.TORRENT, StreamKind.UNSUPPORTED), s.map { it.kind })
        val a = s[0]
        assertEquals("https://ref.test", a.headers["Referer"])
        assertEquals("5", a.headers["X-N"])
        assertEquals("open-1080", a.bingeGroup)
        assertEquals(12345L, a.videoSize)
        assertEquals("Big Buck Bunny 1080p", a.description)
        assertEquals(1, a.subtitles.size)
        assertEquals("ara", a.subtitles[0].language)
    }

    @Test fun notJsonIsAnError() {
        try { StremioParse.streams("<html>oops", src); fail() } catch (_: StremioParse.BadResponse) {}
        assertTrue(StremioParse.streams("{}", src).isEmpty())
    }
}

class AddonTest {
    private class FakeHttp : HttpClient {
        val responses = mutableMapOf<String, () -> HttpResponse>()
        val calls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
            calls += url
            return responses[url]?.invoke() ?: HttpResponse(404, "")
        }
    }

    private var now = 1_000_000L
    private val http = FakeHttp()
    private val cache = MemoryCacheStore()
    private val results = mutableListOf<RequestResult>()
    private val addon = StremioAddon("https://a.test/manifest.json", AddonManifest.parse(MANIFEST), http, cache, clock = { now }, onRequest = { results += it })

    private fun metas(vararg ids: String) = HttpResponse(200, ids.joinToString(",", "{\"metas\":[", "]}") { "{\"id\":\"$it\",\"name\":\"$it\",\"type\":\"movie\"}" })

    @Test fun pagesWithSkip() = runTest {
        http.responses["https://a.test/catalog/movie/films/genre=Animation.json"] = { metas("of:1", "of:2") }
        http.responses["https://a.test/catalog/movie/films/genre=Animation&skip=2.json"] = { metas("of:3") }
        http.responses["https://a.test/catalog/movie/films/genre=Animation&skip=3.json"] = { metas() }
        val cat = addon.catalogs().first()
        val p1 = addon.catalogPage(cat, mapOf("genre" to "Animation"))
        assertEquals(PageRequest(2, 100), p1.next)
        val p2 = addon.catalogPage(cat, mapOf("genre" to "Animation"), p1.next!!)
        assertEquals(listOf("of:3"), p2.items.map { it.id })
        val p3 = addon.catalogPage(cat, mapOf("genre" to "Animation"), p2.next!!)
        assertNull(p3.next)
    }

    @Test fun requiredExtraMustBeChosen() = runTest {
        val cat = addon.catalogs()[1]
        try { addon.catalogPage(cat); fail() } catch (_: SourceException) {}
    }

    @Test fun cachesAndServesStaleOnError() = runTest {
        var fail = false
        http.responses["https://a.test/meta/movie/of%3A1.json"] = {
            if (fail) throw IOException("offline") else HttpResponse(200, "{\"meta\":{\"id\":\"of:1\",\"name\":\"B\"},\"cacheMaxAge\":60}")
        }
        assertNotNull(addon.details(TitleType.MOVIE, "of:1"))
        assertNotNull(addon.details(TitleType.MOVIE, "of:1"))
        assertEquals(1, http.calls.size) // second came from the cache
        now += 61_000
        fail = true
        assertEquals("B", addon.details(TitleType.MOVIE, "of:1")?.name) // stale but shown when the addon fails
        assertEquals(2, http.calls.size)
        assertFalse(results.last { !it.fromCache }.ok)
    }

    @Test fun streamsAreNotServedStale() = runTest {
        var fail = false
        val url = "https://a.test/stream/movie/of%3A1.json"
        http.responses[url] = { if (fail) HttpResponse(500, "") else HttpResponse(200, "{\"streams\":[{\"url\":\"https://x.test/a.mp4\"}]}") }
        assertEquals(1, addon.streams(TitleType.MOVIE, "of:1").size)
        now += 6 * 60_000
        fail = true
        try { addon.streams(TitleType.MOVIE, "of:1"); fail() } catch (_: SourceException) {}
    }

    @Test fun doesNotAskAboutUnsupportedIds() = runTest {
        assertNull(addon.details(TitleType.MOVIE, "tt0111161"))
        assertTrue(http.calls.isEmpty())
    }

    @Test fun brokenJsonIsAnErrorForThisAddonOnly() = runTest {
        http.responses["https://a.test/stream/movie/of%3A9.json"] = { HttpResponse(200, "{\"streams\": [") }
        try { addon.streams(TitleType.MOVIE, "of:9"); fail() } catch (e: SourceException) { assertEquals("org.test.open", e.source.sourceId) }
    }

    @Test fun searchUsesSearchCatalogs() = runTest {
        http.responses["https://a.test/catalog/movie/films/search=bunny.json"] = { metas("of:1") }
        http.responses["https://a.test/catalog/movie/old/search=bunny.json"] = { metas("of:1", "of:5") }
        assertEquals(listOf("of:1", "of:5"), addon.search("bunny", null).map { it.id })
    }

    @Test fun installsFromStremioLink() = runTest {
        http.responses["https://a.test/manifest.json"] = { HttpResponse(200, MANIFEST) }
        val (url, m) = StremioAddon.fetchManifest(http, "stremio://a.test/manifest.json")
        assertEquals("https://a.test/manifest.json", url)
        assertEquals("Open Films", m.name)
        try { StremioAddon.fetchManifest(http, "https://b.test/manifest.json"); fail() } catch (e: ManifestException) { assertEquals(ManifestException.Reason.HTTP, e.reason) }
    }
}
