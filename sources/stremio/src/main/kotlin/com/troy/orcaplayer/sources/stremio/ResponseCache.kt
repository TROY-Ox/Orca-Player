package com.troy.orcaplayer.sources.stremio

/**
 * Where cached responses live. The app keeps them on disk so they show when offline (14.1); tests
 * use the in-memory one.
 */
interface CacheStore {
    fun read(key: String): CachedEntry?
    fun write(key: String, entry: CachedEntry)
    fun clear()
}

data class CachedEntry(
    val body: String,
    /** When it was fetched (ms since epoch). */
    val fetchedAt: Long,
    /** Fresh until this time: served without asking the addon. */
    val freshUntil: Long,
    /** After freshness ends, still served at once while a refresh runs, until this time. */
    val revalidateUntil: Long,
    /** When the addon fails, still served until this time. */
    val errorUntil: Long,
)

class MemoryCacheStore(private val maxEntries: Int = 500) : CacheStore {
    private val map = object : LinkedHashMap<String, CachedEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEntry>?): Boolean = size > maxEntries
    }
    @Synchronized override fun read(key: String): CachedEntry? = map[key]
    @Synchronized override fun write(key: String, entry: CachedEntry) { map[key] = entry }
    @Synchronized override fun clear() = map.clear()
}

/** Default lifetimes when the addon gives none (14.1): catalogs and details for a while, streams briefly. */
enum class CacheClass(val defaultMaxAgeSec: Long, val defaultStaleErrorSec: Long) {
    MANIFEST(6 * 3600L, 30 * 86400L),
    CATALOG(3 * 3600L, 7 * 86400L),
    META(12 * 3600L, 30 * 86400L),
    STREAM(5 * 60L, 0L),
    SUBTITLES(3600L, 86400L),
}

object CachePolicy {
    fun entry(body: String, now: Long, cls: CacheClass, hints: StremioParse.CacheHints): CachedEntry {
        val maxAge = (hints.maxAge ?: cls.defaultMaxAgeSec).coerceIn(0, 365 * 86400L)
        val revalidate = (hints.staleRevalidate ?: 0L).coerceIn(0, 365 * 86400L)
        // Streams are never shown from a stale cache when the addon fails: an old link is likely dead.
        val staleError = if (cls == CacheClass.STREAM) 0L else (hints.staleError ?: cls.defaultStaleErrorSec).coerceIn(0, 365 * 86400L)
        val fresh = now + maxAge * 1000
        return CachedEntry(body, now, fresh, fresh + revalidate * 1000, fresh + maxOf(revalidate, staleError) * 1000)
    }
}
