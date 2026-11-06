package com.troy.orcaplayer.player.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Data sources for the app's own URI schemes: network shares (smb, sftp, ftp, ftps) and the
 * private folder. They are registered once at start-up and bypass the stream cache, so files from
 * the private folder are never written anywhere unencrypted.
 */
@OptIn(UnstableApi::class)
object CustomSchemes {
    private val factories = ConcurrentHashMap<String, DataSource.Factory>()

    fun register(scheme: String, factory: DataSource.Factory) {
        factories[scheme.lowercase()] = factory
    }

    fun factoryFor(uri: Uri): DataSource.Factory? = uri.scheme?.lowercase()?.let { factories[it] }

    fun handles(uri: String): Boolean = factories.containsKey(uri.substringBefore(':').lowercase())
}

/** Opens custom schemes with their own source and everything else with [fallback]. */
@OptIn(UnstableApi::class)
internal class SchemeRoutingDataSource(private val fallback: DataSource) : DataSource {
    private var current: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val custom = CustomSchemes.factoryFor(dataSpec.uri)?.createDataSource()
        val source = custom?.also { s -> listeners.forEach(s::addTransferListener) } ?: fallback
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val s = current
        current = null
        s?.close()
    }

    class Factory(private val fallback: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SchemeRoutingDataSource(fallback.createDataSource())
    }
}
