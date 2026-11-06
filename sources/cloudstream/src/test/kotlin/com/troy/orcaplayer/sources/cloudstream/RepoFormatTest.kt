package com.troy.orcaplayer.sources.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoFormatTest {
    @Test
    fun parsesRepoAndPlugins() {
        val repo = RepoFormat.parseRepo(
            """{"name":"Test repo","description":"Open films","manifestVersion":1,"pluginLists":["plugins.json"],"extra":true}""",
        )
        assertEquals("Test repo", repo.name)
        assertEquals(listOf("plugins.json"), repo.pluginLists)

        val plugins = RepoFormat.parsePlugins(
            """[
              {"url":"https://example.test/Open.cs3","status":1,"version":3,"apiVersion":1,"name":"Open","internalName":"OpenFilms",
               "authors":["a"],"language":"en","tvTypes":["Movie","TvSeries"],"fileSize":1234},
              {"url":"https://example.test/New.cs3","status":3,"version":1,"apiVersion":2,"name":"New","internalName":"New"},
              {"url":"","name":"Broken","internalName":"Broken"}
            ]""",
        )
        assertEquals(2, plugins.size)
        assertTrue(plugins[0].compatible)
        assertFalse(plugins[1].compatible)
        assertEquals(1234L, plugins[0].fileSize)
    }

    @Test
    fun normalizesRepositoryLinks() {
        assertEquals("https://host.test/repo.json", RepoFormat.normalizeRepoUrl(" cloudstreamrepo://host.test/repo.json "))
        assertEquals("https://host.test/r.json", RepoFormat.normalizeRepoUrl("https://host.test/r.json"))
        assertNull(RepoFormat.normalizeRepoUrl("shortcode"))
        assertNull(RepoFormat.normalizeRepoUrl("https://"))
    }

    @Test
    fun resolvesRelativeUrlsAndNames() {
        assertEquals("https://h.test/a/plugins.json", RepoFormat.resolve("https://h.test/a/repo.json", "plugins.json"))
        assertEquals("https://x.test/p.json", RepoFormat.resolve("https://h.test/a/repo.json", "https://x.test/p.json"))
        assertEquals("Open_Films_.._x.cs3", RepoFormat.fileName("Open Films/../x"))
    }

    @Test
    fun idsRoundTrip() {
        val key = RepoFormat.providerKey("OpenFilms", "Open Films")
        assertEquals("OpenFilms.Open_Films", key)
        val t = ExtensionIds.title(key, "https://site.test/movie?id=1&x=ü")
        assertEquals(false to "https://site.test/movie?id=1&x=ü", ExtensionIds.parse(key, t))
        val e = ExtensionIds.episode(key, """{"ep":2}""")
        assertEquals(true to """{"ep":2}""", ExtensionIds.parse(key, e))
        assertNull(ExtensionIds.parse("Other.key", t))
        assertTrue(t.startsWith(ExtensionIds.prefix(key)))
    }
}
