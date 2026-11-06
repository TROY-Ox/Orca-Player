package com.troy.orcaplayer.player.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import com.troy.orcaplayer.core.model.PlayItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the one ExoPlayer instance of the app. [PlaybackService] exposes it through a MediaSession;
 * the player screen uses it directly inside the app process. Switching the decoder mode builds a
 * new player and hands it to the session, keeping the queue and position.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine private constructor(context: Context) {
    private val app = context.applicationContext

    /** Request headers per host, so links that need a referer or cookie keep working for every segment. */
    private val headersByHost = ConcurrentHashMap<String, Map<String, String>>()

    private val _decoderMode = MutableStateFlow(DecoderMode.Default)
    val decoderMode: StateFlow<DecoderMode> = _decoderMode.asStateFlow()

    private val _player = MutableStateFlow(build(DecoderMode.Default))
    val playerFlow: StateFlow<ExoPlayer> = _player.asStateFlow()
    val player: ExoPlayer get() = _player.value

    /** Items currently in the queue, in order, so the UI can map media IDs back to PlayItems. */
    @Volatile var queue: List<PlayItem> = emptyList()
        private set

    /** Called by the service when the player instance changes. */
    internal var onPlayerReplaced: ((ExoPlayer) -> Unit)? = null

    private fun build(mode: DecoderMode): ExoPlayer {
        val renderers = DefaultRenderersFactory(app)
            .setMediaCodecSelector(DecoderSelectors.forMode(mode))
            // HW+ falls back to the next decoder (software) when one fails to start.
            .setEnableDecoderFallback(mode != DecoderMode.HW)
            .setExtensionRendererMode(
                if (mode == DecoderMode.SW) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent("ORCA Player")
        val upstream = DefaultDataSource.Factory(app, http)
        val resolving = ResolvingDataSource.Factory(upstream) { spec: DataSpec ->
            val host = spec.uri.host
            val extra = host?.let { headersByHost[it.lowercase()] }
            if (extra.isNullOrEmpty()) spec else spec.withAdditionalHeaders(extra)
        }
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val sources = DefaultMediaSourceFactory(resolving, extractors)
        val selector = DefaultTrackSelector(app)
        return ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(sources)
            .setTrackSelector(selector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    /** Loads a queue. Positions come from [startPositionMs]; smart resume is applied by the caller. */
    fun load(items: List<PlayItem>, startIndex: Int, startPositionMs: Long, playWhenReady: Boolean = true) {
        queue = items
        items.forEach { item ->
            if (item.headers.isNotEmpty()) Uri.parse(item.uri).host?.let { headersByHost[it.lowercase()] = item.headers }
        }
        val p = player
        p.setMediaItems(items.mapIndexed { i, item -> item.toMediaItem(i) }, startIndex, startPositionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = playWhenReady
    }

    fun itemAt(index: Int): PlayItem? = queue.getOrNull(index)

    /** Rebuilds the player with another decoder mode, keeping queue, position, speed and play state. */
    fun setDecoderMode(mode: DecoderMode) {
        if (mode == _decoderMode.value) return
        _decoderMode.value = mode
        val old = player
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playWhenReady = old.playWhenReady
        val params = old.playbackParameters
        val repeat = old.repeatMode
        val shuffle = old.shuffleModeEnabled
        val pauseAtEnd = old.pauseAtEndOfMediaItems
        val next = build(mode)
        next.playbackParameters = params
        next.repeatMode = repeat
        next.shuffleModeEnabled = shuffle
        next.pauseAtEndOfMediaItems = pauseAtEnd
        if (items.isNotEmpty()) {
            next.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
            next.prepare()
        }
        next.playWhenReady = playWhenReady
        _player.value = next
        onPlayerReplaced?.invoke(next)
        old.release()
    }

    /** Stops playback and clears the queue (used when the player closes without background play). */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
    }

    companion object {
        @Volatile private var instance: PlaybackEngine? = null

        fun get(context: Context): PlaybackEngine =
            instance ?: synchronized(this) { instance ?: PlaybackEngine(context).also { instance = it } }

        internal fun peek(): PlaybackEngine? = instance
    }
}

/** External subtitles are rendered by the app's own subtitle layer, so only the video goes to ExoPlayer. */
fun PlayItem.toMediaItem(index: Int): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(uri)
        .setMediaId("$index:${key.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setDisplayTitle(title)
                .build(),
        )
    val lower = uri.substringBefore('?').lowercase()
    when {
        lower.endsWith(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        lower.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
    }
    return builder.build()
}
