package com.troy.orcaplayer.sources.network

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import android.net.Uri
import java.io.IOException

/**
 * Reads files on SMB, SFTP, FTP and WebDAV shares for the player. Every open starts at the
 * requested position, so seeking works on all of them.
 */
@OptIn(UnstableApi::class)
class ShareDataSource(private val sessions: ShareSessions) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var stream: ShareStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (id, path) = ShareUris.parse(dataSpec.uri.toString())
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val s = try {
            sessions.withClient(id) { it.open(path, dataSpec.position) }
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        stream = s
        if (s.length >= 0 && dataSpec.position > s.length) {
            s.close()
            stream = null
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            s.length >= 0 -> s.length - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            stream?.input?.read(buffer, offset, want) ?: -1
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            stream?.close()
        } finally {
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val sessions: ShareSessions) : DataSource.Factory {
        override fun createDataSource(): DataSource = ShareDataSource(sessions)
    }

    companion object {
        /** The URI schemes this source reads; DLNA items are plain http. */
        val schemes = ShareType.entries.filter { it != ShareType.DLNA }.map { it.scheme }
    }
}
