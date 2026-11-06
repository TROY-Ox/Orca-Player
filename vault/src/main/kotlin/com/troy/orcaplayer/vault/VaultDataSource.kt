package com.troy.orcaplayer.vault

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.common.PlaybackException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Reads private-folder items for the player, decrypting only the segments it needs (20.4). */
@OptIn(UnstableApi::class)
class VaultDataSource(private val vault: Vault) : BaseDataSource(false) {
    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = Vault.idOf(dataSpec.uri) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val ch = try {
            vault.open(id)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        channel = ch
        try {
            val size = ch.size()
            if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            ch.position(dataSpec.position)
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val ch = channel ?: return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val n = try {
            ch.read(ByteBuffer.wrap(buffer, offset, want))
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            channel?.close()
        } finally {
            channel = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val vault: Vault) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(vault)
    }
}
