package com.troy.orcaplayer.player.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Where HLS and DASH downloads live (16.4). Nothing is ever evicted; a download is removed only
 * when the user deletes it. The player reads from here first, so downloaded streams play offline.
 */
@OptIn(UnstableApi::class)
object OfflineCache {
    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): Cache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.filesDir, "offline-streams"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** Bytes used by downloaded streams. */
    fun bytesUsed(context: Context): Long = runCatching { get(context).cacheSpace }.getOrDefault(0L)

    /** Reads from the downloads first and from [upstream] for anything not downloaded. Never writes. */
    fun readThrough(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
