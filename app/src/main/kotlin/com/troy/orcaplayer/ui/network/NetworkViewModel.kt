package com.troy.orcaplayer.ui.network

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.sources.network.DlnaServer
import com.troy.orcaplayer.sources.network.ShareConnection
import com.troy.orcaplayer.sources.network.ShareEntry
import com.troy.orcaplayer.sources.network.ShareMedia
import com.troy.orcaplayer.sources.network.ShareSessions
import com.troy.orcaplayer.sources.network.ShareType
import com.troy.orcaplayer.sources.network.ShareUris
import com.troy.orcaplayer.sources.network.Ssdp
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject

/** What the share browser shows: one folder of one connection. */
data class BrowseState(
    val connection: ShareConnection? = null,
    val path: String = "",
    val entries: List<ShareEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

sealed interface TestResult {
    data object Running : TestResult
    data class Ok(val count: Int) : TestResult
    data class Failed(val message: String) : TestResult
}

data class CopyProgress(val name: String, val done: Long, val total: Long)

/** Saved connections, discovery, browsing, playback and Copy to device for network shares (20.2). */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val sessions = ShareSessions.get(context)
    val connections: StateFlow<List<ShareConnection>> = sessions.store.connections

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _servers = MutableStateFlow<List<DlnaServer>?>(null)
    val servers: StateFlow<List<DlnaServer>?> = _servers.asStateFlow()
    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _test = MutableStateFlow<TestResult?>(null)
    val test: StateFlow<TestResult?> = _test.asStateFlow()

    private val _copy = MutableStateFlow<CopyProgress?>(null)
    val copy: StateFlow<CopyProgress?> = _copy.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages

    private var loadJob: Job? = null

    fun save(conn: ShareConnection) {
        viewModelScope.launch(Dispatchers.IO) {
            if (conn.id.isNotBlank()) sessions.invalidate(conn.id)
            sessions.store.save(conn)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sessions.invalidate(id)
            sessions.store.delete(id)
        }
    }

    fun test(conn: ShareConnection) {
        _test.value = TestResult.Running
        viewModelScope.launch(Dispatchers.IO) {
            _test.value = runCatching { TestResult.Ok(sessions.test(conn).size) }
                .getOrElse { TestResult.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    fun clearTest() { _test.value = null }

    fun discover() {
        if (_discovering.value) return
        _discovering.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _servers.value = runCatching { Ssdp.discover(context) }.getOrDefault(emptyList())
            _discovering.value = false
        }
    }

    fun addServer(server: DlnaServer) {
        save(ShareConnection(id = "", type = ShareType.DLNA, name = server.name, host = server.location))
        _servers.value = _servers.value?.filterNot { it.location == server.location }
    }

    fun open(id: String) {
        val conn = sessions.store[id] ?: return
        if (_browse.value.connection?.id == id && _browse.value.path.isNotEmpty()) return
        load(conn, null)
    }

    fun openFolder(entry: ShareEntry) {
        val conn = _browse.value.connection ?: return
        load(conn, entry.path)
    }

    /** Goes up one folder; false at the top of the share. */
    fun up(): Boolean {
        val s = _browse.value
        val conn = s.connection ?: return false
        if (s.loading && s.entries.isEmpty() && s.path.isEmpty()) return false
        val parent = runCatching { sessions.client(conn.id).parent(s.path) }.getOrNull() ?: return false
        load(conn, parent)
        return true
    }

    fun refresh() {
        val s = _browse.value
        s.connection?.let { load(it, s.path.ifEmpty { null }) }
    }

    private fun load(conn: ShareConnection, path: String?) {
        loadJob?.cancel()
        _browse.value = _browse.value.copy(connection = conn, path = path ?: "", loading = true, error = null,
            entries = if (_browse.value.connection?.id == conn.id) _browse.value.entries else emptyList())
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val (at, list) = sessions.withClient(conn.id) { c ->
                    val p = path ?: c.root
                    p to c.list(p)
                }
                val sorted = list.filter { it.isFolder || ShareMedia.isPlayable(it) }
                    .sortedWith(compareBy<ShareEntry>({ !it.isFolder }, { it.name.lowercase() }))
                _browse.value = BrowseState(conn, at, sorted, loading = false)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _browse.value = _browse.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The folder's media as one queue, starting from [entry]. */
    fun request(entry: ShareEntry): PlayRequest? {
        val s = _browse.value
        val conn = s.connection ?: return null
        val media = s.entries.filter { ShareMedia.isPlayable(it) }
        val index = media.indexOf(entry).coerceAtLeast(0)
        val from = (index - 100).coerceAtLeast(0)
        val window = media.subList(from, (index + 100).coerceAtMost(media.size))
        val items = window.mapNotNull { e ->
            val uri = if (conn.type == ShareType.DLNA) e.url ?: return@mapNotNull null else ShareUris.build(conn, e.path)
            PlayItem(
                uri = uri,
                title = e.name.substringBeforeLast('.').takeIf { conn.type != ShareType.DLNA } ?: e.name,
                key = MediaKeys.forSource("share:" + conn.id, e.path),
                source = SourceRef(SourceKind.NETWORK_SHARE, conn.id, conn.name),
                subtitle = conn.name,
                // The user's own shares are always allowed under the saving rule (3.7).
                canSave = true,
                downloadable = false,
                mimeType = ShareMedia.mimeType(e),
            )
        }
        if (items.isEmpty()) return null
        return PlayRequest(items, (index - from).coerceIn(0, items.size - 1))
    }

    /** Copy to device: saves the file in Downloads/ORCA Player. */
    fun copyToDevice(entry: ShareEntry) {
        val conn = _browse.value.connection ?: return
        if (_copy.value != null || entry.isFolder) return
        _copy.value = CopyProgress(entry.name, 0, entry.size)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val share = if (conn.type == ShareType.DLNA) null else sessions.withClient(conn.id) { it.open(entry.path, 0) }
                val input = share?.input ?: java.net.URL(entry.url ?: error("No link")).openStream()
                try { input.use { inp ->
                    val t = target(entry)
                    try {
                        val out = t.out
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > 2_000_000) {
                                last = done
                                _copy.value = CopyProgress(entry.name, done, entry.size)
                            }
                        }
                        out.flush()
                        out.close()
                        t.finish()
                    } finally {
                        t.close()
                    }
                } } finally { share?.close() }
            }.isSuccess
            _copy.value = null
            _messages.tryEmit(if (ok) R.string.network_copied else R.string.network_copy_failed)
        }
    }

    private class Target(val out: OutputStream, val finish: () -> Unit) {
        fun close() { runCatching { out.close() } }
    }

    private fun target(entry: ShareEntry): Target {
        val name = entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                ShareMedia.mimeType(entry)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ORCA Player")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file")
            val out = resolver.openOutputStream(uri) ?: error("Couldn't open the file")
            return Target(out) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ORCA Player").apply { mkdirs() }
        val file = File(dir, name)
        return Target(FileOutputStream(file)) {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }

    suspend fun connection(id: String): ShareConnection? = withContext(Dispatchers.IO) { sessions.store[id] }
}
