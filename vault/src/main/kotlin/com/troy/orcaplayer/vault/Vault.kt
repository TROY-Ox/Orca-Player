package com.troy.orcaplayer.vault

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.troy.orcaplayer.core.model.MediaKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.UUID

/** One file in the private folder. Names and details are kept in an encrypted index. */
@Serializable
data class VaultItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val durationMs: Long = 0,
    val addedAt: Long = 0,
    /** The file's identity before it was hidden, so resume and per-video settings carry over. */
    val key: MediaKey.LocalFile? = null,
    val isAudio: Boolean = false,
) {
    val uri: String get() = Vault.SCHEME + "://item/" + id
}

/**
 * The private folder (20.4). Files live in app-internal storage, encrypted with Tink streaming AEAD
 * (AES-256-GCM-HKDF, 1 MB segments) so the player can seek in them without decrypting the whole
 * file. Keys are wrapped by the Android Keystore. Uninstalling the app or clearing its data
 * deletes the folder for good.
 */
class Vault private constructor(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")
    private val json = Json { ignoreUnknownKeys = true }

    private val streaming: StreamingAead
    private val aead: Aead

    init {
        StreamingAeadConfig.register()
        AeadConfig.register()
        streaming = keyset("vault_files", "AES256_GCM_HKDF_1MB").getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        aead = keyset("vault_index", "AES256_GCM").getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun keyset(name: String, template: String): KeysetHandle = AndroidKeysetManager.Builder()
        .withSharedPref(app, "orca_$name", "orca_vault_keys")
        .withKeyTemplate(KeyTemplates.get(template))
        .withMasterKeyUri("android-keystore://orca_${name}_master")
        .build()
        .keysetHandle

    private val _items = MutableStateFlow(readIndex())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    private fun readIndex(): List<VaultItem> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val plain = aead.decrypt(indexFile.readBytes(), INDEX_AAD)
        json.decodeFromString(ListSerializer(VaultItem.serializer()), String(plain))
    }.getOrDefault(emptyList())

    @Synchronized
    private fun writeIndex(list: List<VaultItem>) {
        val bytes = aead.encrypt(json.encodeToString(ListSerializer(VaultItem.serializer()), list).toByteArray(), INDEX_AAD)
        val tmp = File(dir, "index.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(indexFile)
        _items.value = list
    }

    private fun fileOf(id: String) = File(dir, "$id.enc")

    fun item(id: String): VaultItem? = _items.value.firstOrNull { it.id == id }

    /**
     * Encrypts [source] into the folder. The caller removes the original afterwards (through a
     * MediaStore request, so Android asks once per batch).
     */
    suspend fun add(
        name: String, sizeBytes: Long, mimeType: String?, durationMs: Long, key: MediaKey.LocalFile?, isAudio: Boolean,
        source: () -> InputStream, onProgress: (Long) -> Unit,
    ): VaultItem = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = fileOf(id)
        try {
            source().use { input ->
                streaming.newEncryptingStream(target.outputStream(), id.toByteArray()).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done)
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        val item = VaultItem(id, name, target.length().let { sizeBytes.takeIf { s -> s > 0 } ?: it }, mimeType, durationMs, System.currentTimeMillis(), key, isAudio)
        writeIndex(_items.value + item)
        item
    }

    /** A seekable plaintext view of an item, for the player. */
    fun open(id: String): SeekableByteChannel {
        val f = fileOf(id)
        return streaming.newSeekableDecryptingChannel(FileInputStream(f).channel, id.toByteArray())
    }

    /** Decrypts an item back into Movies (or Music) so the gallery sees it again, then removes it here. */
    suspend fun unhide(id: String, onProgress: (Long) -> Unit): Uri = withContext(Dispatchers.IO) {
        val item = item(id) ?: error("Not found")
        val resolver = app.contentResolver
        val uri: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (item.isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                item.mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (item.isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/ORCA Player")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, values) ?: error("Couldn't create the file")
            try {
                copyOut(id, resolver.openOutputStream(uri) ?: error("Couldn't write the file"), onProgress)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(if (item.isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES), "ORCA Player").apply { mkdirs() }
            var out = File(folder, item.name)
            var n = 1
            while (out.exists()) out = File(folder, item.name.substringBeforeLast('.') + " ($n)." + item.name.substringAfterLast('.', "mp4")).also { n++ }
            copyOut(id, out.outputStream(), onProgress)
            MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), null, null)
            uri = Uri.fromFile(out)
        }
        delete(id)
        uri
    }

    private fun copyOut(id: String, sink: java.io.OutputStream, onProgress: (Long) -> Unit) {
        streaming.newDecryptingStream(FileInputStream(fileOf(id)), id.toByteArray()).use { input ->
            sink.use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    onProgress(done)
                }
            }
        }
    }

    fun rename(id: String, name: String) {
        val n = name.trim().ifBlank { return }
        writeIndex(_items.value.map { if (it.id == id) it.copy(name = n) else it })
    }

    fun delete(id: String) {
        fileOf(id).delete()
        writeIndex(_items.value.filterNot { it.id == id })
    }

    companion object {
        const val SCHEME = "orcavault"
        private val INDEX_AAD = "orca-vault-index".toByteArray()

        @Volatile private var instance: Vault? = null

        fun get(context: Context): Vault = instance ?: synchronized(this) { instance ?: Vault(context).also { instance = it } }

        fun idOf(uri: Uri): String? = uri.takeIf { it.scheme == SCHEME }?.lastPathSegment
    }
}
