package com.troy.orcaplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

internal val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false; coerceInputValues = true }

/**
 * A small JSON document on disk with its value in memory. Reads once at start; every change is
 * written in the background through a temporary file, so a crash never leaves half a file.
 */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, default: T) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _state = MutableStateFlow(load() ?: default)
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    private fun load(): T? = runCatching {
        if (file.exists()) StoreJson.decodeFromString(serializer, file.readText()) else null
    }.getOrNull()

    @Synchronized
    fun update(change: (T) -> T): T {
        val next = change(_state.value)
        if (next == _state.value) return next
        _state.value = next
        scope.launch { write(next) }
        return next
    }

    private suspend fun write(value: T) = writeLock.withLock {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(StoreJson.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
