package com.troy.orcaplayer.sources.network

import android.content.Context
import android.net.wifi.WifiManager
import com.troy.orcaplayer.sources.network.Xml.all
import com.troy.orcaplayer.sources.network.Xml.child
import com.troy.orcaplayer.sources.network.Xml.children
import com.troy.orcaplayer.sources.network.Xml.text
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.net.URI

/** A DLNA or UPnP media server found on the local network. */
data class DlnaServer(val name: String, val location: String)

/** SSDP discovery of media servers (UPnP Device Architecture 1.1, section 1.3). */
object Ssdp {
    private const val ADDRESS = "239.255.255.250"
    private const val PORT = 1900
    private const val TARGET = "urn:schemas-upnp-org:device:MediaServer:1"

    /** Sends M-SEARCH and collects answers for [timeoutMs]. Blocking; call it off the main thread. */
    fun discover(context: Context, timeoutMs: Int = 3000, http: OkHttpClient = WebDavClient.defaultHttp): List<DlnaServer> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("orca-dlna")?.apply { setReferenceCounted(false); acquire() }
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val msg = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $ADDRESS:$PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $TARGET\r\n\r\n"
                    ).toByteArray()
                val group = InetAddress.getByName(ADDRESS)
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, PORT)) }
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    locationOf(String(packet.data, 0, packet.length))?.let { locations += it }
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
        return locations.mapNotNull { loc ->
            runCatching { describe(loc, http) }.getOrNull()?.let { DlnaServer(it.name, loc) }
        }
    }

    /** The LOCATION header of an SSDP reply. */
    fun locationOf(reply: String): String? = reply.lineSequence()
        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }

    data class Description(val name: String, val controlUrl: String)

    fun describe(location: String, http: OkHttpClient): Description {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { res ->
            if (!res.isSuccessful) throw ShareException("The server answered ${res.code}")
            res.body?.string().orEmpty()
        }
        return parseDescription(xml, location)
    }

    fun parseDescription(xml: String, location: String): Description {
        val doc = Xml.parse(xml)
        val root = doc.documentElement
        val base = root.text("URLBase") ?: location
        val device = root.all("device").firstOrNull() ?: throw ShareException("Not a UPnP device")
        val name = device.text("friendlyName") ?: URI(location).host
        val service = root.all("service").firstOrNull { it.text("serviceType")?.contains("ContentDirectory") == true }
            ?: throw ShareException("$name doesn't share media")
        val control = service.text("controlURL") ?: throw ShareException("$name doesn't share media")
        return Description(name, URI(base).resolve(control).toString())
    }
}

/** Browses a media server's ContentDirectory. Paths are object ids; "0" is the root. */
class DlnaClient(private val conn: ShareConnection, private val http: OkHttpClient = WebDavClient.defaultHttp) : ShareClient {
    override val root: String = "0"
    private val control: String = try {
        Ssdp.describe(conn.host, http).controlUrl
    } catch (e: ShareException) {
        throw e
    } catch (e: Exception) {
        throw ShareException(e.message ?: "Couldn't reach the media server", e)
    }

    /** Remembers each container's parent so Up works, since object ids aren't paths. */
    private val parents = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun parent(path: String): String? = if (path == root) null else parents[path] ?: root

    override fun list(path: String): List<ShareEntry> {
        val out = ArrayList<ShareEntry>()
        var start = 0
        while (true) {
            val (page, total) = browse(path, start, 500)
            out += page
            start += page.size
            if (page.isEmpty() || start >= total || start >= 10_000) break
        }
        out.filter { it.isFolder }.forEach { parents[it.path] = path }
        return out
    }

    private fun browse(id: String, start: Int, count: Int): Pair<List<ShareEntry>, Int> {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">""" +
            "<ObjectID>${escape(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>" +
            "<StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>" +
            "</u:Browse></s:Body></s:Envelope>"
        val req = Request.Builder()
            .url(control)
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            .build()
        val xml = try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw ShareException("The server answered ${res.code}")
                res.body?.string().orEmpty()
            }
        } catch (e: ShareException) {
            throw e
        } catch (e: Exception) {
            throw ShareException(e.message ?: "DLNA error", e)
        }
        return parseBrowse(xml)
    }

    override fun open(path: String, position: Long): ShareStream = throw ShareException("DLNA items play from their own links")

    override fun close() = Unit

    companion object {
        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** Reads a Browse reply: the DIDL-Lite result (escaped inside the SOAP body) and the total count. */
        fun parseBrowse(xml: String): Pair<List<ShareEntry>, Int> {
            val doc = Xml.parse(xml)
            val result = doc.all("Result").firstOrNull()?.textContent.orEmpty()
            val total = doc.all("TotalMatches").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: 0
            return parseDidl(result) to total
        }

        fun parseDidl(didl: String): List<ShareEntry> {
            if (didl.isBlank()) return emptyList()
            val root = Xml.parse(didl).documentElement
            val folders = root.children("container").mapNotNull { c ->
                val id = c.getAttribute("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ShareEntry(name = c.text("title") ?: id, path = id, isFolder = true)
            }
            val items = root.children("item").mapNotNull { i ->
                val id = i.getAttribute("id")
                val res = i.children("res").firstOrNull { r ->
                    val info = r.getAttribute("protocolInfo")
                    info.startsWith("http-get") && (info.contains(":video/") || info.contains(":audio/"))
                } ?: i.child("res") ?: return@mapNotNull null
                val url = res.textContent?.trim()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val mime = res.getAttribute("protocolInfo").split(':').getOrNull(2)?.takeIf { it.contains('/') }
                if (mime != null && !mime.startsWith("video/") && !mime.startsWith("audio/")) return@mapNotNull null
                ShareEntry(
                    name = i.text("title") ?: id,
                    path = id,
                    isFolder = false,
                    size = res.getAttribute("size").toLongOrNull() ?: -1,
                    url = url,
                    mimeType = mime,
                )
            }
            return folders + items
        }
    }
}
