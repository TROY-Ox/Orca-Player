package com.troy.orcaplayer.flavor

import android.content.Context
import com.troy.orcaplayer.core.model.StreamOption
import com.troy.orcaplayer.data.TorrentProgress
import com.troy.orcaplayer.data.TorrentResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.troy.orcaplayer.R
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentFlags
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/** User choices for torrent streaming, kept in the extended build's own preferences. */
class TorrentSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("torrent_streaming", Context.MODE_PRIVATE)

    /** Most space finished torrents may keep in the cache, in GB. */
    var cacheGb: Int
        get() = prefs.getInt("cache_gb", 2)
        set(v) = prefs.edit().putInt("cache_gb", v).apply()

    /** Remove a torrent (stop sharing it) as soon as its playback ends. */
    var stopSeeding: Boolean
        get() = prefs.getBoolean("stop_seeding", true)
        set(v) = prefs.edit().putBoolean("stop_seeding", v).apply()

    var noticeAccepted: Boolean
        get() = prefs.getBoolean("notice_accepted", false)
        set(v) = prefs.edit().putBoolean("notice_accepted", v).apply()

    companion object {
        val CACHE_CHOICES = listOf(1, 2, 5, 10)
    }
}

/**
 * Streams torrent files (extended build, 19): fetches the metadata, downloads the chosen video
 * file in order (first and last pieces first, then wherever the player reads) and serves it on
 * 127.0.0.1 with a random port and a random token in the path, so only this device can open it.
 * The player's range requests wait for the pieces they need.
 */
class TorrentStreamer(
    context: Context,
    /** Where progress goes; shared with the screens, which may subscribe before the streamer exists. */
    private val _progress: MutableStateFlow<TorrentProgress?> = MutableStateFlow(null),
) : TorrentResolver {
    private val app = context.applicationContext
    val settings = TorrentSettings(app)
    private val root = File(app.cacheDir, "torrents")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    override val progress: StateFlow<TorrentProgress?> = _progress.asStateFlow()

    @Volatile private var session: SessionManager? = null

    /** A torrent being played: its handle, the file served and when the player last read from it. */
    private class Active(
        val hash: String,
        val handle: TorrentHandle,
        val info: TorrentInfo,
        val fileIndex: Int,
        val file: File,
        val dir: File,
        val token: String,
    ) {
        @Volatile var lastRead: Long = System.currentTimeMillis()
        val openReads = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /** By token. The current torrent and at most one more (the next episode, prepared ahead). */
    private val active = ConcurrentHashMap<String, Active>()
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "orca-torrent-http").apply { isDaemon = true } }

    init {
        // Leftovers of an earlier run are removed at app start (Flavor.init).
        scope.launch { watchdog() }
    }

    override fun needsNotice(): Boolean = !settings.noticeAccepted

    override fun acceptNotice() {
        settings.noticeAccepted = true
    }

    @Synchronized
    private fun session(): SessionManager = session ?: SessionManager(false).also { s ->
        // Listen on every interface, find peers through the DHT (bootstrapped from the well-known
        // routers), local discovery and port mapping, as well as through trackers.
        val pack = SettingsPack()
            .connectionsLimit(200)
            .activeDownloads(4)
            .listenInterfaces("0.0.0.0:6881,[::]:6881")
        pack.setEnableDht(true)
        pack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        pack.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP)
        s.start(SessionParams(pack))
        if (!s.isDhtRunning) runCatching { s.startDht() }
        session = s
    }

    override suspend fun resolve(option: StreamOption): String = withContext(Dispatchers.IO) {
        _progress.value = TorrentProgress()
        try {
            val s = session()
            val (handle, info) = open(s, option)
            coroutineContext.ensureActive()
            val hash = info.infoHash().toHex()
            val index = pickFile(info, option)
            // Already prepared (the next episode, or the same stream again).
            active.values.firstOrNull { it.hash == hash && it.fileIndex == index }?.let { a ->
                ensureServer()
                _progress.value = null
                return@withContext url(a)
            }
            makeRoom(keep = 1)
            val fs = info.files()
            // Only the chosen file, in order (first and last pieces get deadlines in prebuffer).
            handle.prioritizeFiles(Array(fs.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE })
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle.resume()
            val dir = File(handle.savePath())
            val a = Active(hash, handle, info, index, File(dir, fs.filePath(index)), dir, token())
            active[a.token] = a
            prebuffer(s, a)
            ensureServer()
            _progress.value = null
            url(a)
        } catch (e: Throwable) {
            _progress.value = null
            throw e
        }
    }

    /**
     * Adds the torrent and waits for its file list: from a .torrent link at once, from a magnet
     * link through peers found on the trackers and the DHT. Progress shows DHT nodes, peers and seeds
     * while waiting, and a clear error comes after [METADATA_TIMEOUT_MS].
     */
    private suspend fun open(s: SessionManager, option: StreamOption): Pair<TorrentHandle, TorrentInfo> {
        val link = option.url.trim()
        if (link.startsWith("http://", true) || link.startsWith("https://", true)) {
            val info = TorrentInfo.bdecode(download(link))
            val dir = File(root, info.infoHash().toHex()).apply { mkdirs() }
            s.find(info.infoHash())?.let { return it to info }
            s.download(info, dir, null, Array(info.numFiles()) { Priority.IGNORE }, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            return (s.find(info.infoHash()) ?: error(app.getString(R.string.torrent_err_start))) to info
        }
        val magnet = magnetFor(link, option.trackers, option.filename ?: option.name)
        val hash = runCatching { AddTorrentParams.parseMagnetUri(magnet).infoHashes.best }.getOrNull()
            ?: error(app.getString(R.string.torrent_err_link))
        val dir = File(root, hash.toHex()).apply { mkdirs() }
        if (s.find(hash) == null) {
            // Added in the background; nothing downloads until a file is chosen.
            s.download(magnet, dir, TorrentFlags.SEQUENTIAL_DOWNLOAD.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD))
        }
        val started = System.currentTimeMillis()
        var announced = false
        while (true) {
            coroutineContext.ensureActive()
            val h = s.find(hash)
            val st = h?.status()
            if (h != null && st != null && st.hasMetadata()) {
                val info = h.torrentFile() ?: error(app.getString(R.string.torrent_err_start))
                return h to info
            }
            _progress.value = TorrentProgress(
                hasMetadata = false, peers = st?.numPeers() ?: 0, seeds = st?.numSeeds() ?: 0,
                dhtNodes = s.dhtNodes().toInt(), downloadRate = st?.downloadRate() ?: 0,
            )
            val waited = System.currentTimeMillis() - started
            // Once the DHT is up, ask it and the trackers again rather than waiting for their timers.
            if (!announced && h != null && s.dhtNodes() > 20) {
                announced = true
                runCatching { h.forceDHTAnnounce(); h.forceReannounce() }
            }
            if (waited > METADATA_TIMEOUT_MS) {
                h?.let { runCatching { s.remove(it, SessionHandle.DELETE_FILES) } }
                error(
                    if (s.dhtNodes() == 0L && (st?.numPeers() ?: 0) == 0) app.getString(R.string.torrent_err_network)
                    else app.getString(R.string.torrent_err_no_seeders),
                )
            }
            delay(500)
        }
    }

    /** Waits for the start of the file (and its last piece, where many containers keep their index). */
    private suspend fun prebuffer(s: SessionManager, a: Active) {
        val fs = a.info.files()
        val size = fs.fileSize(a.fileIndex)
        val first = fs.mapFile(a.fileIndex, 0, 1).piece()
        val last = fs.mapFile(a.fileIndex, (size - 1).coerceAtLeast(0), 1).piece()
        // About 8 MB from the start, at least 4 pieces.
        val count = maxOf(4, (8L * 1024 * 1024 / a.info.pieceLength()).toInt()).coerceAtMost(last - first + 1)
        val wanted = (first until first + count).toList() + listOf(last)
        wanted.forEachIndexed { i, p -> a.handle.setPieceDeadline(p, 500 + i * 300) }
        val started = System.currentTimeMillis()
        while (true) {
            coroutineContext.ensureActive()
            val have = wanted.count { a.handle.havePiece(it) }
            val st = a.handle.status()
            _progress.value = TorrentProgress(
                hasMetadata = true, peers = st.numPeers(), seeds = st.numSeeds(), dhtNodes = s.dhtNodes().toInt(),
                percent = have * 100 / wanted.size, downloadRate = st.downloadRate(),
            )
            if (have == wanted.size) return
            val waited = System.currentTimeMillis() - started
            // Nothing at all for a long time: there is nobody to download from.
            if (waited > PREBUFFER_TIMEOUT_MS || (waited > 90_000 && st.totalDownload() == 0L)) {
                remove(a.token)
                error(app.getString(R.string.torrent_err_no_seeders))
            }
            delay(400)
        }
    }

    private fun pickFile(info: TorrentInfo, option: StreamOption): Int {
        val fs = info.files()
        val n = fs.numFiles()
        option.fileIndex?.takeIf { it in 0 until n }?.let { return it }
        val videos = (0 until n).filter { i -> fs.fileName(i).substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXT }
        return (videos.ifEmpty { (0 until n).toList() }).maxBy { fs.fileSize(it) }
    }

    private fun url(a: Active): String {
        val port = server?.localPort ?: error("The local server isn't running")
        val name = android.net.Uri.encode(File(a.info.files().fileName(a.fileIndex)).name)
        return "http://127.0.0.1:$port/${a.token}/$name"
    }

    private fun token(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    override fun stop() {
        active.keys.toList().forEach { remove(it) }
        _progress.value = null
    }

    private fun remove(token: String) {
        val a = active.remove(token) ?: return
        runCatching { session?.remove(a.handle, SessionHandle.DELETE_FILES) }
        a.dir.deleteRecursively()
    }

    /** Keeps at most [keep] other torrents and the cache under the size limit. */
    private fun makeRoom(keep: Int) {
        val byAge = active.values.sortedBy { it.lastRead }
        if (byAge.size > keep) byAge.take(byAge.size - keep).forEach { remove(it.token) }
        val cap = settings.cacheGb.toLong() * 1024 * 1024 * 1024
        var used = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        for (a in active.values.sortedBy { it.lastRead }) {
            if (used <= cap) break
            used -= a.dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            remove(a.token)
        }
    }

    /** A torrent nobody has read from for a while is stopped (no sharing after playback, when chosen). */
    private suspend fun watchdog() {
        while (true) {
            delay(30_000)
            val now = System.currentTimeMillis()
            active.values.filter { it.openReads.get() == 0 && now - it.lastRead > IDLE_MS }.forEach { a ->
                if (settings.stopSeeding) remove(a.token)
            }
            if (active.isEmpty()) {
                synchronized(this) {
                    runCatching { server?.close() }
                    server = null
                }
            }
        }
    }

    // The local HTTP server

    @Synchronized
    private fun ensureServer() {
        if (server?.isClosed == false) return
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        pool.execute {
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (_: SocketException) { break }
                pool.execute { runCatching { serve(socket) }; runCatching { socket.close() } }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 60_000
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        val out = socket.getOutputStream()
        val a = active[path.removePrefix("/").substringBefore('/')]
        if (a == null || (method != "GET" && method != "HEAD")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val size = a.info.files().fileSize(a.fileIndex)
        var start = 0L
        var end = size - 1
        val range = headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val s = spec.substringBefore('-').trim()
            val e = spec.substringAfter('-').trim()
            if (s.isEmpty()) {
                start = (size - (e.toLongOrNull() ?: 0)).coerceAtLeast(0)
            } else {
                start = s.toLongOrNull() ?: 0
                e.toLongOrNull()?.let { end = minOf(it, size - 1) }
            }
            if (start >= size) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
        }
        val length = end - start + 1
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: ${mimeOf(a.file.name)}\r\n")
            append("Content-Length: $length\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (method == "HEAD") return
        a.openReads.incrementAndGet()
        try {
            send(a, start, end, out)
        } finally {
            a.openReads.decrementAndGet()
            a.lastRead = System.currentTimeMillis()
        }
    }

    /** Sends bytes [start]..[end] of the file, waiting for each piece and asking for the next ones first. */
    private fun send(a: Active, start: Long, end: Long, out: java.io.OutputStream) {
        val fs = a.info.files()
        val pieceLen = a.info.pieceLength().toLong()
        val fileOffset = fs.fileOffset(a.fileIndex)
        val lastPiece = fs.mapFile(a.fileIndex, fs.fileSize(a.fileIndex) - 1, 1).piece()
        val buf = ByteArray(64 * 1024)
        var pos = start
        var raf: RandomAccessFile? = null
        try {
            while (pos <= end) {
                val piece = ((fileOffset + pos) / pieceLen).toInt()
                if (!a.handle.havePiece(piece)) {
                    // What the player needs now first, then the next few pieces.
                    for (k in 0 until READ_AHEAD) {
                        val p = piece + k
                        if (p > lastPiece) break
                        if (!a.handle.havePiece(p)) a.handle.setPieceDeadline(p, k * 200)
                    }
                    val waitStart = System.currentTimeMillis()
                    while (!a.handle.havePiece(piece)) {
                        if (!active.containsKey(a.token)) return
                        if (System.currentTimeMillis() - waitStart > 3 * 60_000L) return
                        a.lastRead = System.currentTimeMillis()
                        Thread.sleep(150)
                    }
                }
                val pieceEnd = (piece + 1L) * pieceLen - fileOffset - 1
                val until = minOf(end, pieceEnd)
                if (raf == null) {
                    // The file exists once its first piece is written.
                    var tries = 0
                    while (!a.file.exists() && tries++ < 50) Thread.sleep(100)
                    raf = RandomAccessFile(a.file, "r")
                }
                raf.seek(pos)
                while (pos <= until) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), until - pos + 1).toInt())
                    if (n <= 0) {
                        Thread.sleep(100)
                        continue
                    }
                    out.write(buf, 0, n)
                    pos += n
                    a.lastRead = System.currentTimeMillis()
                }
            }
            out.flush()
        } catch (_: SocketException) {
            // The player closed the connection (a seek or stop).
        } finally {
            runCatching { raf?.close() }
        }
    }

    private fun download(link: String): ByteArray {
        val c = URL(link).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }.also { require(it.size < 20 * 1024 * 1024) { "Too large" } }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val IDLE_MS = 90_000L
        private const val READ_AHEAD = 6
        private const val METADATA_TIMEOUT_MS = 120_000L
        private const val PREBUFFER_TIMEOUT_MS = 5 * 60_000L
        private const val DHT_BOOTSTRAP = "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.libtorrent.org:25401"

        private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp")

        /** Public trackers added to every magnet link, so peers are found even when the source gives none. */
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
        )

        fun magnetFor(link: String, trackers: List<String>, name: String?): String {
            val base = if (link.startsWith("magnet:", true)) link else "magnet:?xt=urn:btih:" + link
            val sb = StringBuilder(base)
            if (name != null && !base.contains("&dn=")) sb.append("&dn=").append(android.net.Uri.encode(name.lineSequence().first()))
            (trackers + DEFAULT_TRACKERS).distinct().forEach { t -> if (!base.contains(android.net.Uri.encode(t))) sb.append("&tr=").append(android.net.Uri.encode(t)) }
            return sb.toString()
        }

        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }
}
