package com.troy.orcaplayer.data

import com.troy.orcaplayer.sources.stremio.CacheStore
import com.troy.orcaplayer.sources.stremio.CachedEntry
import java.io.File
import java.security.MessageDigest

/**
 * Addon responses on disk, so catalogs and details still show when offline (14.1). Kept under
 * [maxBytes]; the oldest files go first.
 */
class DiskCacheStore(private val dir: File, private val maxBytes: Long = 30L * 1024 * 1024) : CacheStore {
    private val memory = HashMap<String, CachedEntry>()
    private var writes = 0

    private fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hash)
    }

    @Synchronized
    override fun read(key: String): CachedEntry? {
        memory[key]?.let { return it }
        val f = fileFor(key)
        if (!f.exists()) return null
        return runCatching {
            val text = f.readText()
            val parts = text.split('\n', limit = 5)
            CachedEntry(parts[4], parts[0].toLong(), parts[1].toLong(), parts[2].toLong(), parts[3].toLong())
        }.getOrNull()?.also { remember(key, it) }
    }

    @Synchronized
    override fun write(key: String, entry: CachedEntry) {
        remember(key, entry)
        runCatching {
            dir.mkdirs()
            fileFor(key).writeText("${entry.fetchedAt}\n${entry.freshUntil}\n${entry.revalidateUntil}\n${entry.errorUntil}\n${entry.body}")
        }
        if (++writes % 25 == 0) trim()
    }

    @Synchronized
    override fun clear() {
        memory.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun remember(key: String, entry: CachedEntry) {
        if (memory.size > 200) memory.clear()
        memory[key] = entry
    }

    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
