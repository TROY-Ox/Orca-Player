package com.troy.orcaplayer.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Uri
import com.troy.orcaplayer.sources.network.ShareSessions
import com.troy.orcaplayer.sources.network.ShareUris
import com.troy.orcaplayer.vault.Vault
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.Channels
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone's small web server for casting (20.7): the Chromecast reads local files, files on
 * network shares, the private folder and streams that need custom headers from here, with Range
 * support for seeking. Every address carries a random token and the server only answers the
 * local network.
 */
class CastServer private constructor(private val context: Context) {
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "orca-cast-http").apply { isDaemon = true } }
    private var socket: ServerSocket? = null
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    private sealed interface Entry { val mime: String }
    private data class Media(val uri: String, val headers: Map<String, String>, override val mime: String) : Entry
    private data class Text(val body: ByteArray, override val mime: String) : Entry

    /** An address for [uri] that the Chromecast can read, or null when the phone isn't on a local network. */
    fun serve(uri: String, mime: String, headers: Map<String, String> = emptyMap()): String? {
        val base = base() ?: return null
        val id = "m" + entries.size + "-" + uri.hashCode().toUInt().toString(16)
        entries[id] = Media(uri, headers, mime)
        return "$base/$token/$id"
    }

    fun serveText(text: String, mime: String): String? {
        val base = base() ?: return null
        val id = "t" + entries.size + "-" + text.hashCode().toUInt().toString(16)
        entries[id] = Text(text.toByteArray(), mime)
        return "$base/$token/$id"
    }

    fun clear() = entries.clear()

    @Synchronized
    private fun base(): String? {
        val ip = localAddress() ?: return null
        val s = socket ?: ServerSocket().also {
            it.reuseAddress = true
            it.bind(InetSocketAddress(0))
            socket = it
            pool.execute { accept(it) }
        }
        return "http://${ip.hostAddress}:${s.localPort}"
    }

    private fun localAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?: props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            pool.execute { runCatching { handle(client) }; runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val remote = client.inetAddress
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val request = input.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val out = client.getOutputStream()
        val parts = request.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?').trim('/').split('/')
        if (!(remote.isSiteLocalAddress || remote.isLoopbackAddress || remote.isLinkLocalAddress)) return status(out, 403, "Forbidden")
        if (path.size != 2 || path[0] != token) return status(out, 404, "Not Found")
        val entry = entries[path[1]] ?: return status(out, 404, "Not Found")
        if (method == "OPTIONS") return status(out, 204, "No Content")
        val head = method == "HEAD"
        when (entry) {
            is Text -> respond(out, 200, "OK", entry.mime, entry.body.size.toLong(), null, head) { it.write(entry.body) }
            is Media -> serveMedia(out, entry, headers["range"], head)
        }
    }

    private fun serveMedia(out: OutputStream, m: Media, range: String?, head: Boolean) {
        val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
        val endAsked = range?.substringAfter('-', "")?.toLongOrNull()
        val (stream, total) = open(m, start) ?: return status(out, 404, "Not Found")
        stream.use { src ->
            if (total in 0..start && total > 0) return status(out, 416, "Range Not Satisfiable")
            val end = if (total > 0) minOf(endAsked ?: (total - 1), total - 1) else -1
            val length = if (end >= 0) end - start + 1 else -1
            val partial = range != null && total > 0
            val contentRange = if (partial) "bytes $start-$end/$total" else null
            respond(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", m.mime, length, contentRange, head) { o ->
                val buf = ByteArray(64 * 1024)
                var left = if (length >= 0) length else Long.MAX_VALUE
                while (left > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    /** The file from [position] and its total size (-1 when unknown). */
    private fun open(m: Media, position: Long): Pair<InputStream, Long>? {
        val uri = m.uri
        val scheme = uri.substringBefore(':')
        return when {
            scheme == "content" || scheme == "file" -> {
                val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r") ?: return null
                val input = afd.createInputStream()
                val total = afd.length.takeIf { it >= 0 } ?: -1L
                var left = position
                while (left > 0) { val n = input.skip(left); if (n <= 0) break; left -= n }
                input to total
            }
            scheme == Vault.SCHEME -> {
                val vault = Vault.get(context)
                val id = Vault.idOf(Uri.parse(uri)) ?: return null
                val channel = vault.open(id)
                channel.position(position)
                val size = channel.size()
                Channels.newInputStream(channel) to size
            }
            ShareUris.parse(uri) != null -> {
                val (id, path) = ShareUris.parse(uri)!!
                val s = ShareSessions.get(context).withClient(id) { it.open(path, position) }
                object : java.io.FilterInputStream(s.input) {
                    override fun close() = s.close()
                } to s.length
            }
            scheme == "http" || scheme == "https" -> {
                val req = Request.Builder().url(uri).apply {
                    m.headers.forEach { (k, v) -> header(k, v) }
                    if (position > 0) header("Range", "bytes=$position-")
                }.build()
                val res = http.newCall(req).execute()
                if (!res.isSuccessful) { res.close(); return null }
                val body = res.body ?: run { res.close(); return null }
                val total = res.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: body.contentLength().takeIf { it >= 0 }?.let { if (res.code == 206) it + position else it } ?: -1L
                body.byteStream() to total
            }
            else -> null
        }
    }

    private fun status(out: OutputStream, code: Int, text: String) {
        respond(out, code, text, "text/plain", 0, null, false) {}
    }

    private fun respond(out: OutputStream, code: Int, text: String, mime: String, length: Long, contentRange: String?, head: Boolean, body: (OutputStream) -> Unit) {
        val sb = StringBuilder()
            .append("HTTP/1.1 $code $text\r\n")
            .append("Content-Type: $mime\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Access-Control-Allow-Headers: Range, Content-Type\r\n")
            .append("Access-Control-Expose-Headers: Content-Range, Content-Length\r\n")
            .append("Connection: close\r\n")
        if (length >= 0) sb.append("Content-Length: $length\r\n")
        contentRange?.let { sb.append("Content-Range: $it\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) runCatching { body(out) }
        out.flush()
    }

    companion object {
        @Volatile private var instance: CastServer? = null
        fun get(context: Context): CastServer = instance ?: synchronized(this) { instance ?: CastServer(context.applicationContext).also { instance = it } }

        /** SRT to WebVTT, which is what Cast receivers read. */
        fun srtToVtt(srt: String): String {
            val body = srt.trimStart { it.code == 0xFEFF }.replace("\r\n", "\n").replace('\r', '\n')
                .replace(Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
            return "WEBVTT\n\n" + body.trim() + "\n"
        }
    }
}
