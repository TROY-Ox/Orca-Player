package com.troy.orcaplayer.sources.network

import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder

/** The kinds of network share the app can browse (20.2). */
enum class ShareType(val scheme: String, val defaultPort: Int) {
    SMB("smb", 445),
    SFTP("sftp", 22),
    FTP("ftp", 21),
    FTPS("ftps", 21),
    WEBDAV("orcadav", 80),
    DLNA("http", 0),
}

/**
 * A saved connection. The password lives only in the encrypted store, never in a URI, so the
 * player, resume positions and logs only ever see the connection's id.
 */
@Serializable
data class ShareConnection(
    val id: String,
    val type: ShareType,
    val name: String,
    /** Server name or address. For DLNA, the device description URL. */
    val host: String,
    val port: Int = 0,
    /** SMB: share name with an optional folder ("Media/Films"). Others: the start folder. */
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    /** WebDAV over HTTPS. */
    val secure: Boolean = false,
    /** SFTP host key fingerprint, kept on first connect and checked after that. */
    val hostKey: String? = null,
) {
    val effectivePort: Int get() = if (port > 0) port else if (type == ShareType.WEBDAV && secure) 443 else type.defaultPort
    val anonymous: Boolean get() = username.isBlank()
}

/** One file or folder on a share. [path] is the client's own absolute path for it. */
data class ShareEntry(
    val name: String,
    val path: String,
    val isFolder: Boolean,
    val size: Long = -1,
    val modified: Long = 0,
    /** A direct http(s) link, for DLNA items. */
    val url: String? = null,
    val mimeType: String? = null,
)

/** An open file read from [position]; [length] is the whole file's size, or -1 when unknown. */
class ShareStream(val input: InputStream, val length: Long, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() {
        runCatching { input.close() }
        runCatching { onClose() }
    }
}

/** Browsing and reading for one connection. Calls block, so run them off the main thread. */
interface ShareClient : Closeable {
    /** Where browsing starts. */
    val root: String
    fun list(path: String): List<ShareEntry>
    fun open(path: String, position: Long): ShareStream
    fun parent(path: String): String? = ShareUris.parentOf(path, root)
}

class ShareException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** URIs for files on shares: `<scheme>://<connection id>/<percent-encoded path>`. */
object ShareUris {
    fun build(conn: ShareConnection, path: String): String {
        val encoded = path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encode(it) }
        return conn.type.scheme + "://" + conn.id + "/" + encoded
    }

    /** The connection id and absolute path of a share URI, or null if it isn't one. */
    fun parse(uri: String): Pair<String, String>? {
        val scheme = uri.substringBefore("://", "")
        if (scheme.isEmpty() || ShareType.entries.none { it.scheme == scheme && it != ShareType.DLNA }) return null
        val rest = uri.substringAfter("://")
        val id = rest.substringBefore('/')
        if (id.isEmpty()) return null
        val path = rest.substringAfter('/', "").substringBefore('?').split('/').filter { it.isNotEmpty() }.joinToString("/") { decode(it) }
        return id to "/$path"
    }

    fun parentOf(path: String, root: String): String? {
        val p = "/" + path.trim('/')
        val r = "/" + root.trim('/')
        if (p == r || p.length <= r.length) return null
        val parent = p.substringBeforeLast('/')
        return parent.ifEmpty { "/" }
    }

    fun join(folder: String, name: String): String = ("/" + folder.trim('/') + "/" + name).replace("//", "/")

    fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    fun decode(segment: String): String = runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
}
