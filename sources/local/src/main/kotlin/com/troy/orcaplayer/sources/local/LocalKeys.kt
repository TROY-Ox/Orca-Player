package com.troy.orcaplayer.sources.local

import android.content.ContentResolver
import android.net.Uri
import com.troy.orcaplayer.core.model.MediaKey
import com.troy.orcaplayer.core.model.MediaKeys
import java.io.FileInputStream

/** Identity keys for local files opened by content or file URI: size plus a hash of the first and last 64 KB. */
object LocalKeys {
    fun forUri(resolver: ContentResolver, uri: Uri, path: String? = null): MediaKey.LocalFile? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val channel = input.channel
                val size = pfd.statSize.takeIf { it > 0 } ?: channel.size()
                val n = minOf(MediaKeys.SAMPLE_BYTES.toLong(), size).toInt()
                val head = ByteArray(n)
                val headBuf = java.nio.ByteBuffer.wrap(head)
                channel.position(0)
                while (headBuf.hasRemaining() && channel.read(headBuf) > 0) Unit
                val tail = ByteArray(n)
                val tailBuf = java.nio.ByteBuffer.wrap(tail)
                channel.position(size - n)
                while (tailBuf.hasRemaining() && channel.read(tailBuf) > 0) Unit
                MediaKey.LocalFile(size, MediaKeys.localHash(size, head, tail), path ?: uri.toString())
            }
        }
    }.getOrNull()
}
