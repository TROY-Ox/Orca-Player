package com.troy.orcaplayer.sources.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** SMB 2 and 3 (Windows shares, Samba, NAS boxes) through smbj. Paths are "/folder/file" inside the share. */
class SmbShareClient(private val conn: ShareConnection) : ShareClient {
    private val shareName = conn.path.trim('/', '\\').substringBefore('/').substringBefore('\\')
    override val root: String = "/" + conn.path.trim('/', '\\').replace('\\', '/').substringAfter('/', "")

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withSecurityProvider(BCSecurityProvider())
            .withDfsEnabled(true)
            .build(),
    )
    private val connection: Connection
    private val session: Session
    private val share: DiskShare

    init {
        if (shareName.isEmpty()) throw ShareException("Enter the share name, for example Media")
        try {
            connection = client.connect(conn.host, conn.effectivePort)
            val auth = if (conn.anonymous) AuthenticationContext.guest()
            else AuthenticationContext(conn.username, conn.password.toCharArray(), conn.domain.ifBlank { null })
            session = connection.authenticate(auth)
            share = session.connectShare(shareName) as? DiskShare ?: throw ShareException("$shareName is not a folder share")
        } catch (e: ShareException) {
            runCatching { client.close() }
            throw e
        } catch (e: Exception) {
            runCatching { client.close() }
            throw ShareException(e.message ?: "Couldn't connect", e)
        }
    }

    private fun smbPath(path: String) = path.trim('/').replace('/', '\\')

    override fun list(path: String): List<ShareEntry> = wrap {
        share.list(smbPath(path)).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val attrs = info.fileAttributes
            if (EnumWithValue.EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_HIDDEN)) return@mapNotNull null
            val folder = EnumWithValue.EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
            ShareEntry(
                name = name,
                path = ShareUris.join(path, name),
                isFolder = folder,
                size = if (folder) -1 else info.endOfFile,
                modified = info.lastWriteTime.toEpochMillis(),
            )
        }
    }

    override fun open(path: String, position: Long): ShareStream = wrap {
        val file = share.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val length = file.fileInformation.standardInformation.endOfFile
        ShareStream(SmbInput(file, position), length) { file.close() }
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: ShareException) {
        throw e
    } catch (e: Exception) {
        throw ShareException(e.message ?: "SMB error", e)
    }

    /** Reads with explicit offsets, so each read is independent of the share's own file pointer. */
    private class SmbInput(private val file: File, private var offset: Long) : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = file.read(b, offset, off, len.coerceAtMost(CHUNK))
            if (n <= 0) return -1
            offset += n
            return n
        }
        companion object { const val CHUNK = 1 shl 20 }
    }
}
