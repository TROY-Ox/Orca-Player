package com.troy.orcaplayer.ui.player

import android.content.Context
import android.content.Intent
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.player.engine.DecoderMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Options that come with a request from another app (MX Player and VLC extras). */
data class LaunchOptions(
    val decoderMode: DecoderMode? = null,
    /** The calling app asked for the final position and duration back. */
    val returnResult: Boolean = false,
    /** External subtitle files (URIs) with optional names, loaded when the video starts. */
    val subtitles: List<Pair<String, String?>> = emptyList(),
    val enabledSubtitle: String? = null,
)

/**
 * Hands a [PlayRequest] to the player screen. Requests stay in memory and only a short token goes
 * through the Intent, so long queues never hit the Binder size limit.
 */
object PlayerLaunch {
    private const val EXTRA_TOKEN = "orca.request"
    private val pending = ConcurrentHashMap<String, Pair<PlayRequest, LaunchOptions>>()

    fun intent(context: Context, request: PlayRequest, options: LaunchOptions = LaunchOptions()): Intent {
        val token = UUID.randomUUID().toString()
        pending[token] = request to options
        return Intent(context, PlayerActivity::class.java).putExtra(EXTRA_TOKEN, token)
    }

    fun play(context: Context, request: PlayRequest) {
        context.startActivity(intent(context, request))
    }

    /** The request carried by [intent], once. Null when the intent didn't come from inside the app. */
    fun take(intent: Intent): Pair<PlayRequest, LaunchOptions>? {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return null
        return pending.remove(token)
    }
}
