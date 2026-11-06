package com.troy.orcaplayer.sources.network

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Saved connections (20.2), kept in one file encrypted with a key held by the Android Keystore.
 * They are device-wide, like the other storage settings.
 */
class ShareStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "shares/connections.bin")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ShareConnection.serializer())
    private val aead: Aead

    init {
        AeadConfig.register()
        aead = AndroidKeysetManager.Builder()
            .withSharedPref(app, "orca_shares", "orca_share_keys")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://orca_shares_master")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val _connections = MutableStateFlow(read())
    val connections: StateFlow<List<ShareConnection>> = _connections.asStateFlow()

    operator fun get(id: String): ShareConnection? = _connections.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(conn: ShareConnection): ShareConnection {
        val c = if (conn.id.isBlank()) conn.copy(id = newId()) else conn
        val list = _connections.value.toMutableList()
        val i = list.indexOfFirst { it.id == c.id }
        if (i >= 0) list[i] = c else list += c
        write(list)
        return c
    }

    @Synchronized
    fun delete(id: String) {
        write(_connections.value.filterNot { it.id == id })
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun read(): List<ShareConnection> = runCatching {
        if (!file.exists()) return emptyList()
        val plain = aead.decrypt(file.readBytes(), AD)
        json.decodeFromString(serializer, plain.decodeToString())
    }.getOrDefault(emptyList())

    private fun write(list: List<ShareConnection>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(aead.encrypt(json.encodeToString(serializer, list).encodeToByteArray(), AD))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        _connections.value = list
    }

    companion object {
        private val AD = "orca-shares-v1".encodeToByteArray()

        @Volatile private var instance: ShareStore? = null
        fun get(context: Context): ShareStore = instance ?: synchronized(this) { instance ?: ShareStore(context).also { instance = it } }
    }
}
