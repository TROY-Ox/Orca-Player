package com.troy.orcaplayer.data

import android.content.Context
import android.net.Uri
import com.troy.orcaplayer.sources.api.PlaylistEntry
import com.troy.orcaplayer.sources.api.Playlists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** An M3U or M3U8 channel list the user added: a web link or a file on the device. */
@Serializable
data class LivePlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val updatedAt: Long = 0,
    val channelCount: Int = 0,
)

@Serializable
data class LiveTvData(
    val playlists: List<LivePlaylist> = emptyList(),
    /** Favourite channels by stream URL. */
    val favorites: List<String> = emptyList(),
    /** Recently watched channels by stream URL, newest first. */
    val recent: List<String> = emptyList(),
)

/** One channel, with the playlist it came from. */
data class LiveChannel(val entry: PlaylistEntry, val playlistId: String) {
    val url: String get() = entry.url
    val name: String get() = entry.name?.trim()?.ifEmpty { null } ?: entry.url.substringAfterLast('/')
    val group: String? get() = entry.group?.trim()?.ifEmpty { null }
}

/** Free, publicly available channel lists the Live TV screen offers to add with one tap. */
data class SuggestedPlaylist(val name: String, val url: String)

/**
 * Live TV (13): the user's own M3U/IPTV playlists. Lists are fetched once and kept on disk, so
 * the channel list opens instantly and offline; Refresh fetches them again. No channels are
 * built in: everything comes from lists the user chooses to add.
 */
class LiveTvRepository(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "live")
    private val store = JsonFile(File(dir, "live.json"), LiveTvData.serializer(), LiveTvData())
    val data: StateFlow<LiveTvData> = store.state

    private val _channels = MutableStateFlow<Map<String, List<LiveChannel>>>(emptyMap())
    /** Channels by playlist ID, for every list loaded so far. */
    val channels: StateFlow<Map<String, List<LiveChannel>>> = _channels.asStateFlow()

    companion object {
        /**
         * iptv-org collects channels that their broadcasters stream for free; its lists are
         * public. Offered as suggestions only; nothing is added without the user's tap.
         */
        val Suggestions = listOf(
            SuggestedPlaylist("Arabic (iptv-org)", "https://iptv-org.github.io/iptv/languages/ara.m3u"),
            SuggestedPlaylist("News (iptv-org)", "https://iptv-org.github.io/iptv/categories/news.m3u"),
            SuggestedPlaylist("Kids (iptv-org)", "https://iptv-org.github.io/iptv/categories/kids.m3u"),
            SuggestedPlaylist("Documentary (iptv-org)", "https://iptv-org.github.io/iptv/categories/documentary.m3u"),
        )

        private const val UA = "OrcaPlayer/1.0 (Android)"
    }

    private fun cacheFile(p: LivePlaylist): File {
        val md = MessageDigest.getInstance("SHA-1").digest(p.url.toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) } + ".m3u")
    }

    /** Loads every list from its disk copy (fast), fetching any that were never fetched. */
    suspend fun loadAll() = withContext(Dispatchers.IO) {
        store.value.playlists.forEach { p ->
            if (_channels.value.containsKey(p.id)) return@forEach
            val cached = cacheFile(p).takeIf { it.isFile }?.readText()
            if (cached != null) publish(p, cached) else runCatching { refresh(p.id) }
        }
    }

    /** Adds a list and fetches it. Throws with a readable message when it can't be read. */
    suspend fun add(name: String, url: String, userAgent: String? = null): LivePlaylist = withContext(Dispatchers.IO) {
        val clean = url.trim()
        store.value.playlists.firstOrNull { it.url == clean }?.let { return@withContext it }
        val p = LivePlaylist(name = name.trim().ifEmpty { guessName(clean) }, url = clean, userAgent = userAgent?.trim()?.ifEmpty { null })
        val text = fetch(p)
        val entries = Playlists.parse(text, clean.takeIf { it.startsWith("http") })
        require(entries.isNotEmpty()) { "No channels found in this list" }
        val saved = p.copy(updatedAt = System.currentTimeMillis(), channelCount = entries.size)
        store.update { d -> d.copy(playlists = d.playlists + saved) }
        dir.mkdirs()
        cacheFile(saved).writeText(text)
        publish(saved, text)
        saved
    }

    suspend fun refresh(id: String) = withContext(Dispatchers.IO) {
        val p = store.value.playlists.firstOrNull { it.id == id } ?: return@withContext
        val text = fetch(p)
        val count = publish(p, text)
        dir.mkdirs()
        cacheFile(p).writeText(text)
        store.update { d -> d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(updatedAt = System.currentTimeMillis(), channelCount = count) else it }) }
    }

    fun remove(id: String) {
        val p = store.value.playlists.firstOrNull { it.id == id } ?: return
        runCatching { cacheFile(p).delete() }
        _channels.value = _channels.value - id
        store.update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }
    }

    fun rename(id: String, name: String) = store.update { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    fun toggleFavorite(url: String) = store.update { d ->
        d.copy(favorites = if (url in d.favorites) d.favorites - url else listOf(url) + d.favorites)
    }

    fun markWatched(url: String) = store.update { d -> d.copy(recent = (listOf(url) + d.recent.filterNot { it == url }).take(30)) }

    private fun publish(p: LivePlaylist, text: String): Int {
        val entries = Playlists.parse(text, p.url.takeIf { it.startsWith("http") })
            .map { e -> if (p.userAgent != null && "User-Agent" !in e.headers) e.copy(headers = e.headers + ("User-Agent" to p.userAgent)) else e }
        _channels.value = _channels.value + (p.id to entries.map { LiveChannel(it, p.id) })
        return entries.size
    }

    private fun fetch(p: LivePlaylist): String {
        val url = p.url
        if (url.startsWith("content://") || url.startsWith("file://")) {
            return app.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes().decodeToString() }
                ?: error("Can't open the file")
        }
        require(url.startsWith("http://") || url.startsWith("https://")) { "Use a web link (http or https) or pick a file" }
        var target = URL(url)
        repeat(5) {
            val conn = target.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", p.userAgent ?: UA)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: error("HTTP $code")
                    target = URL(target, loc)
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")
                val raw = conn.inputStream
                val stream = if (conn.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(raw) else raw
                val text = stream.use { it.readBytes().decodeToString() }
                require(Playlists.looksLikeM3u(text)) { "This link isn't an M3U playlist" }
                return text
            } finally {
                conn.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun guessName(url: String): String {
        val u = Uri.parse(url)
        return u.lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: u.host ?: "Playlist"
    }
}
