package com.troy.orcaplayer.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest

/**
 * Identity for resume, history and per-video settings.
 *
 * - Local files: size plus a hash of the first and last 64 KB, so renaming or moving keeps the data.
 * - Links: the normalised URL.
 * - Addon and extension content: source ID + title ID + episode ID, never the stream URL.
 */
@Serializable
sealed interface MediaKey {
    /** Stable string used as the database key. */
    val id: String

    @Serializable
    @SerialName("local")
    data class LocalFile(val size: Long, val hash: String, val path: String? = null) : MediaKey {
        override val id: String get() = "local:$size:$hash"
    }

    @Serializable
    @SerialName("link")
    data class Link(val normalizedUrl: String) : MediaKey {
        override val id: String get() = "link:$normalizedUrl"
    }

    @Serializable
    @SerialName("source")
    data class SourceContent(val sourceId: String, val titleId: String, val episodeId: String? = null) : MediaKey {
        override val id: String get() = "src:$sourceId:$titleId:${episodeId.orEmpty()}"
    }
}

object MediaKeys {
    const val SAMPLE_BYTES = 64 * 1024

    fun forSource(sourceId: String, titleId: String, episodeId: String? = null) =
        MediaKey.SourceContent(sourceId, titleId, episodeId)

    fun forLink(url: String) = MediaKey.Link(normalizeUrl(url))

    /** Hash of size + first and last 64 KB. Works on any readable bytes, so Android content URIs can use it too. */
    fun localHash(size: Long, head: ByteArray, tail: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(size.toString().toByteArray())
        md.update(head)
        md.update(tail)
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    fun forLocalFile(file: File): MediaKey.LocalFile {
        val size = file.length()
        RandomAccessFile(file, "r").use { raf ->
            val headLen = minOf(SAMPLE_BYTES.toLong(), size).toInt()
            val head = ByteArray(headLen).also { raf.readFully(it) }
            val tailLen = minOf(SAMPLE_BYTES.toLong(), size).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(size - tailLen)
            raf.readFully(tail)
            return MediaKey.LocalFile(size, localHash(size, head, tail), file.path)
        }
    }

    private val trackingParams = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid",
    )

    /**
     * Lower-cases scheme and host, drops default ports, fragments and tracking parameters,
     * and sorts the remaining query parameters.
     */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase() ?: return trimmed
        val host = uri.host?.lowercase() ?: return trimmed
        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in trackingParams }
            ?.sorted()
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }
        val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        return buildString {
            append(scheme).append("://").append(userInfo).append(host).append(port).append(path)
            if (query != null) append('?').append(query)
        }
    }
}
