package com.troy.orcaplayer.sources.local

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Makes thumbnails in the background and keeps them in a memory cache, so scrolling never waits. */
class Thumbnails(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val app = context.applicationContext
    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** A few at a time, so fast flings don't flood the disk. */
    private val gate = Semaphore(3)

    fun cached(media: LocalMedia): Bitmap? = cache.get(media.uri)

    suspend fun load(media: LocalMedia, widthPx: Int = 320, heightPx: Int = 180): Bitmap? {
        cache.get(media.uri)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(media.uri) ?: make(media, widthPx, heightPx)?.also { cache.put(media.uri, it) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun make(media: LocalMedia, w: Int, h: Int): Bitmap? = runCatching {
        val uri = Uri.parse(media.uri)
        when {
            Build.VERSION.SDK_INT >= 29 -> resolver.loadThumbnail(uri, Size(w, h), null)
            !media.isAudio && media.id < (1L shl 40) ->
                MediaStore.Video.Thumbnails.getThumbnail(resolver, media.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            else -> frame(uri)
        }
    }.getOrNull() ?: runCatching { if (!media.isAudio) frame(Uri.parse(media.uri)) else null }.getOrNull()

    private fun frame(uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, uri)
            r.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it) }
        } finally {
            runCatching { r.release() }
        }
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        val w = 320
        if (b.width <= w) return b
        val h = (b.height * w.toFloat() / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true).also { if (it !== b) b.recycle() }
    }

    private companion object {
        fun maxCacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(64L * 1024 * 1024).toInt()
    }
}
