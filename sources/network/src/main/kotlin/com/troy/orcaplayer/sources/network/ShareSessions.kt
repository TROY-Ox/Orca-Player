package com.troy.orcaplayer.sources.network

import android.content.Context
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Open clients, one per saved connection, shared by browsing and playback so seeking doesn't
 * log in again each time. A failed call drops the client and tries once more with a new one.
 */
class ShareSessions private constructor(context: Context) {
    val store = ShareStore.get(context)
    private val clients = ConcurrentHashMap<String, ShareClient>()

    fun client(id: String): ShareClient {
        clients[id]?.let { return it }
        val conn = store[id] ?: throw ShareException("Unknown connection")
        return synchronized(this) { clients[id] ?: connect(conn).also { clients[id] = it } }
    }

    /** Connects without saving or caching anything, to test a connection before it is saved. */
    fun test(conn: ShareConnection): List<ShareEntry> = connect(conn, save = false).use { it.list(it.root) }

    fun <T> withClient(id: String, block: (ShareClient) -> T): T = try {
        block(client(id))
    } catch (e: IOException) {
        invalidate(id)
        block(client(id))
    }

    fun invalidate(id: String) {
        clients.remove(id)?.let { runCatching { it.close() } }
    }

    fun closeAll() {
        clients.keys.toList().forEach(::invalidate)
    }

    private fun connect(conn: ShareConnection, save: Boolean = true): ShareClient = when (conn.type) {
        ShareType.SMB -> SmbShareClient(conn)
        ShareType.SFTP -> SftpShareClient(conn) { key -> if (save) store.save(conn.copy(hostKey = key)) }
        ShareType.FTP, ShareType.FTPS -> FtpShareClient(conn)
        ShareType.WEBDAV -> WebDavClient(conn)
        ShareType.DLNA -> DlnaClient(conn)
    }

    companion object {
        @Volatile private var instance: ShareSessions? = null
        fun get(context: Context): ShareSessions = instance ?: synchronized(this) { instance ?: ShareSessions(context).also { instance = it } }
    }
}
