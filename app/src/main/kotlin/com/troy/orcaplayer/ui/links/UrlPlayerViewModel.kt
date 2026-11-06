package com.troy.orcaplayer.ui.links

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.UriSchemes
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.SourceSettings
import com.troy.orcaplayer.data.HistoryEntry
import com.troy.orcaplayer.data.LinkFolder
import com.troy.orcaplayer.data.LinksData
import com.troy.orcaplayer.data.LinksRepository
import com.troy.orcaplayer.data.SavedLink
import com.troy.orcaplayer.player.engine.PlaybackEngine
import com.troy.orcaplayer.sources.api.MediaLinks
import com.troy.orcaplayer.sources.api.PlaylistEntry
import com.troy.orcaplayer.sources.api.Playlists
import com.troy.orcaplayer.ui.player.LaunchOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** What the user typed for one link: the link plus optional headers and a subtitle link (13). */
data class LinkInput(
    val url: String,
    val name: String? = null,
    val referer: String = "",
    val userAgent: String = "",
    val headers: String = "",
    val subtitleUrl: String = "",
) {
    fun headerMap(): Map<String, String> = buildMap {
        putAll(SourceSettings.parseHeaders(headers))
        referer.trim().takeIf { it.isNotEmpty() }?.let { put("Referer", it) }
        userAgent.trim().takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) }
    }
}

sealed interface UrlEvent {
    data class Play(val request: PlayRequest, val options: LaunchOptions) : UrlEvent
    data class Install(val url: String) : UrlEvent
    data class PlaylistFound(val url: String, val entries: List<PlaylistEntry>) : UrlEvent
    data object Bad : UrlEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UrlPlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val links: LinksRepository,
    settingsRepo: SettingsRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    val data: StateFlow<LinksData> = links.data
    val settings: StateFlow<SettingsValues> = profiles.currentProfileId.flatMapLatest { settingsRepo.values(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsValues.Defaults)

    private val _queue = MutableStateFlow<List<LinkInput>>(emptyList())
    /** Links queued to play one after another. */
    val queue: StateFlow<List<LinkInput>> = _queue.asStateFlow()

    private val _events = MutableStateFlow<UrlEvent?>(null)
    val events: StateFlow<UrlEvent?> = _events.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun consume() {
        _events.value = null
    }

    private fun valid(url: String): Boolean {
        val u = url.trim()
        return u.contains("://") && UriSchemes.schemeOf(u) in setOf("http", "https", "rtsp")
    }

    /** Plays a link; playlists are offered for import and addon links for install. */
    fun play(input: LinkInput) {
        val url = normalize(input.url)
        if (url == null) {
            if (MediaLinks.kindOf(input.url.trim()) == MediaLinks.Kind.STREMIO_MANIFEST) _events.value = UrlEvent.Install(input.url.trim())
            else _events.value = UrlEvent.Bad
            return
        }
        val kind = MediaLinks.kindOf(url)
        if (kind == MediaLinks.Kind.STREMIO_MANIFEST) {
            _events.value = UrlEvent.Install(url)
            return
        }
        if (kind == MediaLinks.Kind.PLAYLIST || kind == MediaLinks.Kind.HLS) {
            // ".m3u8" is both HLS and plain playlists: any #EXT-X- tag means HLS (13).
            _busy.value = true
            viewModelScope.launch {
                val text = withContext(Dispatchers.IO) { runCatching { fetchText(url, input.headerMap()) }.getOrNull() }
                _busy.value = false
                if (text != null && !Playlists.isHls(text) && Playlists.looksLikeM3u(text)) {
                    val entries = Playlists.parse(text, url)
                    if (entries.isNotEmpty()) {
                        _events.value = UrlEvent.PlaylistFound(url, entries)
                        return@launch
                    }
                }
                start(listOf(input.copy(url = url)), 0)
            }
            return
        }
        start(listOf(input.copy(url = url)), 0)
    }

    private fun normalize(raw: String): String? {
        val t = raw.trim()
        val u = if (!t.contains("://") && t.contains('.') && !t.contains(' ')) "https://$t" else t
        return u.takeIf { valid(it) }
    }

    fun playQueue() {
        val q = _queue.value
        if (q.isNotEmpty()) start(q, 0)
    }

    fun playEntries(entries: List<PlaylistEntry>, index: Int) {
        start(entries.map { LinkInput(it.url, it.name, headers = it.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" }) }, index)
    }

    fun playSaved(link: SavedLink) = start(listOf(link.toInput()), 0)

    fun playHistory(h: HistoryEntry) = start(listOf(LinkInput(h.url, h.title, headers = h.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" }, subtitleUrl = h.subtitleUrl.orEmpty())), 0)

    private fun start(inputs: List<LinkInput>, index: Int) {
        val playable = inputs.filter { valid(it.url) }
        if (playable.isEmpty()) {
            _events.value = UrlEvent.Bad
            return
        }
        val limit = settings.value[SourceSettings.HistoryLength].toIntOrNull() ?: 50
        val items = playable.map { i ->
            val headers = i.headerMap()
            val sub = i.subtitleUrl.trim().takeIf { it.startsWith("http", ignoreCase = true) }
            links.addHistory(HistoryEntry(i.url.trim(), i.name, headers, sub), limit)
            PlayItem(
                uri = i.url.trim(),
                title = i.name ?: Uri.parse(i.url.trim()).lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: Uri.parse(i.url.trim()).host ?: i.url,
                key = MediaKeys.forLink(i.url.trim()),
                source = SourceRef(SourceKind.LINK, "link"),
                headers = headers,
                subtitle = Uri.parse(i.url.trim()).host,
            )
        }
        val first = playable.getOrNull(index) ?: playable.first()
        val sub = first.subtitleUrl.trim().takeIf { it.startsWith("http", ignoreCase = true) }
        val options = LaunchOptions(subtitles = listOfNotNull(sub?.let { it to Uri.parse(it).lastPathSegment }))
        _events.value = UrlEvent.Play(PlayRequest(items, index.coerceIn(items.indices), profiles.currentProfileId.value), options)
    }

    fun enqueue(input: LinkInput) {
        val url = normalize(input.url) ?: run { _events.value = UrlEvent.Bad; return }
        _queue.value = _queue.value + input.copy(url = url)
    }

    fun removeFromQueue(index: Int) {
        _queue.value = _queue.value.toMutableList().also { if (index in it.indices) it.removeAt(index) }
    }

    fun clearQueue() {
        _queue.value = emptyList()
    }

    // Saved links and folders

    fun save(input: LinkInput, name: String, folderId: String?, existingId: String? = null) {
        val url = normalize(input.url) ?: return
        val link = SavedLink(
            url = url, name = name.ifBlank { Uri.parse(url).lastPathSegment ?: url }, headers = input.headerMap(),
            subtitleUrl = input.subtitleUrl.trim().ifEmpty { null }, folderId = folderId,
        )
        links.save(if (existingId != null) link.copy(id = existingId) else link)
    }

    fun delete(id: String) = links.delete(id)
    fun move(id: String, delta: Int) = links.move(id, delta)
    fun addFolder(name: String): LinkFolder = links.addFolder(name)
    fun renameFolder(id: String, name: String) = links.renameFolder(id, name)
    fun deleteFolder(id: String) = links.deleteFolder(id)
    fun moveFolder(id: String, delta: Int) = links.moveFolder(id, delta)
    fun removeHistory(url: String) = links.removeHistory(url)
    fun clearHistory() = links.clearHistory()

    /** Imports playlist entries as saved links in a new folder named after the list. */
    fun importEntries(name: String, entries: List<PlaylistEntry>) {
        val folder = links.addFolder(name)
        links.saveAll(entries.map { SavedLink(url = it.url, name = it.name ?: it.url, headers = it.headers, folderId = folder.id, logo = it.logo) })
    }

    fun importText(name: String, text: String): Int {
        val entries = Playlists.parse(text)
        if (entries.isEmpty()) return 0
        importEntries(name, entries)
        return entries.size
    }

    /** M3U text for all saved links, or one folder's. */
    fun exportM3u(folderId: String?): String {
        val d = data.value
        val list = if (folderId == null) d.saved else d.saved.filter { it.folderId == folderId }
        return Playlists.export(list.map { l -> PlaylistEntry(l.url, l.name, l.logo, d.folders.firstOrNull { it.id == l.folderId }?.name, headers = l.headers) })
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", settings.value[SourceSettings.UserAgent].ifBlank { PlaybackEngine.DEFAULT_USER_AGENT })
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { input ->
                val buf = ByteArray(2 * 1024 * 1024)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}

fun SavedLink.toInput() = LinkInput(
    url = url,
    name = name,
    headers = headers.entries.joinToString("\n") { (k, v) -> "$k: $v" },
    subtitleUrl = subtitleUrl.orEmpty(),
)
