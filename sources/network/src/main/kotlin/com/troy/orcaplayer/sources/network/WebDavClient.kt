package com.troy.orcaplayer.sources.network

import com.troy.orcaplayer.sources.network.Xml.child
import com.troy.orcaplayer.sources.network.Xml.text
import com.troy.orcaplayer.sources.network.Xml.all
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** WebDAV over HTTP or HTTPS: PROPFIND to browse and ranged GET to read, with basic sign-in. */
class WebDavClient(private val conn: ShareConnection, private val http: OkHttpClient = defaultHttp) : ShareClient {
    private val origin = (if (conn.secure) "https://" else "http://") + conn.host.trim().trimEnd('/') +
        (if (conn.port > 0) ":${conn.port}" else "")
    override val root: String = "/" + conn.path.trim('/')
    private val auth: String? = if (conn.anonymous) null else Credentials.basic(conn.username, conn.password, Charsets.UTF_8)

    fun urlOf(path: String): String = origin + "/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { ShareUris.encode(it) } +
        if (path.endsWith('/')) "/" else ""

    override fun list(path: String): List<ShareEntry> {
        val req = Request.Builder()
            .url(urlOf(path.trimEnd('/') + "/"))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        val body = try {
            http.newCall(req).execute().use { res ->
                if (res.code == 401) throw ShareException("Wrong user name or password")
                if (!res.isSuccessful && res.code != 207) throw ShareException("The server answered ${res.code}")
                res.body?.string().orEmpty()
            }
        } catch (e: ShareException) {
            throw e
        } catch (e: Exception) {
            throw ShareException(e.message ?: "WebDAV error", e)
        }
        return parse(body, path)
    }

    override fun open(path: String, position: Long): ShareStream {
        val req = Request.Builder()
            .url(urlOf(path))
            .apply {
                auth?.let { header("Authorization", it) }
                if (position > 0) header("Range", "bytes=$position-")
            }
            .build()
        val res = try { http.newCall(req).execute() } catch (e: Exception) { throw ShareException(e.message ?: "WebDAV error", e) }
        if (!res.isSuccessful) {
            res.close()
            throw ShareException("The server answered ${res.code}")
        }
        val body = res.body ?: run { res.close(); throw ShareException("Empty reply") }
        val total = res.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: body.contentLength().takeIf { it >= 0 }?.let { if (res.code == 206) it + position else it }
            ?: -1L
        var input = body.byteStream()
        // A server that ignores Range sends the whole file; skip to the wanted spot.
        if (position > 0 && res.code == 200) {
            var left = position
            while (left > 0) {
                val n = input.skip(left)
                if (n <= 0) break
                left -= n
            }
        }
        return ShareStream(input, total) { res.close() }
    }

    override fun close() = Unit

    companion object {
        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""

        /** Reads a PROPFIND multistatus reply, leaving out the folder that was asked for. */
        fun parse(xml: String, requested: String): List<ShareEntry> {
            if (xml.isBlank()) return emptyList()
            val doc = Xml.parse(xml)
            val self = "/" + requested.trim('/')
            val dates = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            return doc.all("response").mapNotNull { r ->
                val href = r.text("href") ?: return@mapNotNull null
                val path = "/" + pathOf(href).trim('/')
                if (path == self) return@mapNotNull null
                val stats = r.all("propstat")
                val prop = (stats.firstOrNull { it.text("status")?.contains(" 200") == true } ?: stats.firstOrNull())?.child("prop")
                    ?: r.all("prop").firstOrNull()
                val folder = prop?.child("resourcetype")?.all("collection")?.isNotEmpty() == true
                val name = prop?.text("displayname") ?: path.substringAfterLast('/')
                if (name.startsWith(".")) return@mapNotNull null
                ShareEntry(
                    name = name,
                    path = path,
                    isFolder = folder,
                    size = if (folder) -1 else prop?.text("getcontentlength")?.toLongOrNull() ?: -1,
                    modified = prop?.text("getlastmodified")?.let { runCatching { dates.parse(it)?.time }.getOrNull() } ?: 0,
                    mimeType = prop?.text("getcontenttype")?.substringBefore(';'),
                )
            }
        }

        /** An href may be a whole URL or a path; either way, return the decoded path. */
        fun pathOf(href: String): String {
            val p = if (href.contains("://")) href.substringAfter("://").let { "/" + it.substringAfter('/', "") } else href
            return p.substringBefore('?').split('/').joinToString("/") { ShareUris.decode(it) }
        }
    }
}
