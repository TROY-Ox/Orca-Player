package com.troy.orcaplayer.sources.cloudstream.host

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.troy.orcaplayer.sources.cloudstream.DetailsRequestDto
import com.troy.orcaplayer.sources.cloudstream.LinksRequestDto
import com.troy.orcaplayer.sources.cloudstream.LoadRequest
import com.troy.orcaplayer.sources.cloudstream.MainPageRequestDto
import com.troy.orcaplayer.sources.cloudstream.Protocol
import com.troy.orcaplayer.sources.cloudstream.RepoFormat
import com.troy.orcaplayer.sources.cloudstream.SearchRequestDto
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The ":extensions" process (19.3). The app sends one request per message and gets one reply;
 * requests run in parallel, can be cancelled, and errors come back as messages, never as crashes
 * in the app. If an extension brings this whole process down, the app reconnects.
 */
class ExtensionService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private val runtime by lazy { PluginRuntime(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val jobs = ConcurrentHashMap<Long, Job>()

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("extensions-ipc").apply { start() }
        messenger = Messenger(Handler(thread.looper) { msg -> handle(Message.obtain(msg)); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val data = msg.data ?: Bundle()
        val id = data.getLong(Protocol.KEY_ID)
        val reply = msg.replyTo
        if (msg.what == Protocol.OP_CANCEL) {
            jobs.remove(id)?.cancel()
            return
        }
        val json = data.getString(Protocol.KEY_JSON).orEmpty()
        val j = RepoFormat.json
        jobs[id] = scope.launch {
            val out = Bundle().apply { putLong(Protocol.KEY_ID, id) }
            try {
                val result: String = when (msg.what) {
                    Protocol.OP_LOAD -> j.encodeToString(
                        com.troy.orcaplayer.sources.cloudstream.LoadResult.serializer(),
                        runtime.load(j.decodeFromString(LoadRequest.serializer(), json).files),
                    )
                    Protocol.OP_MAIN_PAGE -> j.decodeFromString(MainPageRequestDto.serializer(), json).let {
                        j.encodeToString(com.troy.orcaplayer.sources.cloudstream.MainPageDto.serializer(), runtime.mainPage(it.provider, it.section, it.page))
                    }
                    Protocol.OP_SEARCH -> j.decodeFromString(SearchRequestDto.serializer(), json).let {
                        j.encodeToString(com.troy.orcaplayer.sources.cloudstream.SearchDto.serializer(), runtime.search(it.provider, it.query))
                    }
                    Protocol.OP_DETAILS -> j.decodeFromString(DetailsRequestDto.serializer(), json).let {
                        j.encodeToString(com.troy.orcaplayer.sources.cloudstream.DetailsDto.serializer(), runtime.details(it.provider, it.url))
                    }
                    Protocol.OP_LINKS -> j.decodeFromString(LinksRequestDto.serializer(), json).let {
                        j.encodeToString(com.troy.orcaplayer.sources.cloudstream.LinksDto.serializer(), runtime.links(it.provider, it.data, it.isCasting))
                    }
                    else -> error("Unknown request")
                }
                out.putString(Protocol.KEY_JSON, result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Extension code can throw anything, including linkage errors from a missing API.
                out.putString(Protocol.KEY_ERROR, PluginRuntime.describe(e))
            } finally {
                jobs.remove(id)
            }
            runCatching { reply?.send(Message.obtain(null, msg.what).apply { this.data = out }) }
        }
    }
}
