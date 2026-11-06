package com.troy.orcaplayer.sources.cloudstream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.troy.orcaplayer.sources.cloudstream.host.ExtensionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's side of the ":extensions" process: binds to it, sends requests with timeouts and
 * cancellation, and reloads the extensions when the process died (19.3).
 */
class ExtensionHost(context: Context, private val files: () -> List<ExtensionFile>) {
    private val app = context.applicationContext
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Bundle>>()
    private val connectLock = Mutex()
    @Volatile private var service: Messenger? = null
    @Volatile private var connecting: CompletableDeferred<Messenger>? = null
    @Volatile private var loadedFor: List<ExtensionFile>? = null

    private val _providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    /** Providers registered by the loaded extensions. */
    val providers: StateFlow<List<ProviderInfo>> = _providers.asStateFlow()

    private val _failures = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Extensions that failed to load, with why: by internal name. */
    val failures: StateFlow<Map<String, String>> = _failures.asStateFlow()

    private val replies = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            val id = msg.data?.getLong(Protocol.KEY_ID) ?: 0
            pending.remove(id)?.complete(Bundle(msg.data))
            true
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = Messenger(binder)
            service = m
            connecting?.complete(m)
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()

        override fun onBindingDied(name: ComponentName?) {
            died()
            runCatching { app.unbindService(this) }
        }
    }

    private fun died() {
        service = null
        connecting = null
        loadedFor = null
        // Everything in flight fails; the next request starts the process again.
        pending.values.forEach { it.completeExceptionally(IllegalStateException("The extension process stopped")) }
        pending.clear()
    }

    private suspend fun messenger(): Messenger {
        service?.let { return it }
        return connectLock.withLock {
            service?.let { return@withLock it }
            val d = connecting ?: CompletableDeferred<Messenger>().also {
                connecting = it
                val ok = app.bindService(Intent(app, ExtensionService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!ok) {
                    connecting = null
                    it.completeExceptionally(IllegalStateException("Couldn't start the extension process"))
                }
            }
            try {
                withTimeout(15_000) { d.await() }
            } catch (e: Exception) {
                connecting = null
                throw e
            }
        }
    }

    private suspend fun <T> call(op: Int, json: String, out: KSerializer<T>, timeoutMs: Long): T {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<Bundle>()
        pending[id] = d
        val m = messenger()
        val msg = Message.obtain(null, op).apply {
            data = Bundle().apply { putLong(Protocol.KEY_ID, id); putString(Protocol.KEY_JSON, json) }
            replyTo = replies
        }
        m.send(msg)
        val reply = try {
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            runCatching { m.send(Message.obtain(null, Protocol.OP_CANCEL).apply { data = Bundle().apply { putLong(Protocol.KEY_ID, id) } }) }
            throw IllegalStateException("The extension didn't answer in time", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            pending.remove(id)
            runCatching { m.send(Message.obtain(null, Protocol.OP_CANCEL).apply { data = Bundle().apply { putLong(Protocol.KEY_ID, id) } }) }
            throw e
        }
        reply.getString(Protocol.KEY_ERROR)?.let { throw IllegalStateException(it) }
        return RepoFormat.json.decodeFromString(out, reply.getString(Protocol.KEY_JSON) ?: error("Empty answer"))
    }

    private val loadLock = Mutex()

    /** Loads the installed, enabled extensions if they aren't loaded in the current process. */
    suspend fun ensureLoaded(): List<ProviderInfo> = loadLock.withLock {
        val want = files()
        if (loadedFor == want && service != null) return@withLock _providers.value
        val r = call(Protocol.OP_LOAD, RepoFormat.json.encodeToString(LoadRequest.serializer(), LoadRequest(want)), LoadResult.serializer(), 60_000)
        loadedFor = want
        _providers.value = r.providers
        _failures.value = r.failures
        r.providers
    }

    /**
     * Starts a fresh process, for example after an extension was updated or removed: a class
     * loader can't unload code, so the old process is stopped.
     */
    suspend fun restart() {
        loadLock.withLock {
            runCatching { app.unbindService(connection) }
            died()
            stopProcess()
        }
        ensureLoaded()
    }

    private fun stopProcess() {
        val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return
        am.runningAppProcesses?.firstOrNull { it.processName == app.packageName + ":extensions" }?.let { android.os.Process.killProcess(it.pid) }
    }

    suspend fun mainPage(provider: String, section: Int, page: Int, timeoutMs: Long): MainPageDto {
        ensureLoaded()
        return call(Protocol.OP_MAIN_PAGE, RepoFormat.json.encodeToString(MainPageRequestDto.serializer(), MainPageRequestDto(provider, section, page)), MainPageDto.serializer(), timeoutMs)
    }

    suspend fun search(provider: String, query: String, timeoutMs: Long): SearchDto {
        ensureLoaded()
        return call(Protocol.OP_SEARCH, RepoFormat.json.encodeToString(SearchRequestDto.serializer(), SearchRequestDto(provider, query)), SearchDto.serializer(), timeoutMs)
    }

    suspend fun details(provider: String, url: String, timeoutMs: Long): DetailsDto {
        ensureLoaded()
        return call(Protocol.OP_DETAILS, RepoFormat.json.encodeToString(DetailsRequestDto.serializer(), DetailsRequestDto(provider, url)), DetailsDto.serializer(), timeoutMs)
    }

    suspend fun links(provider: String, data: String, isCasting: Boolean, timeoutMs: Long): LinksDto {
        ensureLoaded()
        return call(Protocol.OP_LINKS, RepoFormat.json.encodeToString(LinksRequestDto.serializer(), LinksRequestDto(provider, data, isCasting)), LinksDto.serializer(), timeoutMs)
    }
}
