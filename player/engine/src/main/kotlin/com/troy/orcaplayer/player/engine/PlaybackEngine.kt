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
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import java.io.File
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.player.audio.AudioChain
import com.troy.orcaplayer.player.audio.ChainConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /** The one software audio chain, shared by every player instance. */
    val audioChain = AudioChain()

    /** Audio delay per output device (10.6). */
    val audioDelay = AudioDelayController(app)

    // Picture filters (20.6) and audio-only mode (20.3).
    private var filter = com.troy.orcaplayer.core.model.VideoFilter()
    private var filterSkipHdr = true
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _stats = MutableStateFlow(PlaybackStats())
    val stats: StateFlow<PlaybackStats> = _stats.asStateFlow()

    private val _notices = MutableSharedFlow<EngineNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<EngineNotice> = _notices.asSharedFlow()

    // Output preferences from Settings.
    @Volatile private var passthroughSetting = false
    @Volatile private var spatialSetting = true
    /** Whether the current player instance may send bitstreams; fixed per instance. */
    @Volatile private var passthroughAllowed = false
    private var toneMap = ToneMapMethod.SYSTEM
    private var toneBrightness = 0f
    private var effectsApplied = false
    private var displayHdr = false
    @Volatile private var frameStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS

    // Stream settings (13). Headers and user agent apply to the next request; buffer to the next video.
    @Volatile private var defaultHeaders: Map<String, String> = emptyMap()
    @Volatile private var userAgent: String = DEFAULT_USER_AGENT
    @Volatile private var bufferSec = 50
    private var builtBufferSec = 50
    @Volatile private var cacheMb = 200L
    private var cache: SimpleCache? = null
    @Volatile private var cacheChanged = false

    private val _player = MutableStateFlow(build(DecoderMode.Default))
    val playerFlow: StateFlow<ExoPlayer> = _player.asStateFlow()
    val player: ExoPlayer get() = _player.value

    /** Items currently in the queue, in order, so the UI can map media IDs back to PlayItems. */
    @Volatile var queue: List<PlayItem> = emptyList()
        private set

    /** Called by the service when the player instance changes. */
    internal var onPlayerReplaced: ((ExoPlayer) -> Unit)? = null

    private inner class Renderers(mode: DecoderMode) : DefaultRenderersFactory(app) {
        init {
            setMediaCodecSelector(DecoderSelectors.forMode(mode))
            // HW+ falls back to the next decoder (software) when one fails to start.
            setEnableDecoderFallback(mode != DecoderMode.HW)
            setExtensionRendererMode(
                if (mode == DecoderMode.SW) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_ON,
            )
        }

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
            val sink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setAudioProcessors(arrayOf(FxAudioProcessor(audioChain)))
                .build()
            return AudioDelaySink(PassthroughGate(sink) { passthroughAllowed }) { audioDelay.delayUs }
        }
    }

    private fun audioAttributes() = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setSpatializationBehavior(if (spatialSetting) C.SPATIALIZATION_BEHAVIOR_AUTO else C.SPATIALIZATION_BEHAVIOR_NEVER)
        .build()

    private fun build(mode: DecoderMode): ExoPlayer {
        val renderers = Renderers(mode)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            // No factory user agent: it would override the one a link or addon sends. It is added below instead.
            .setUserAgent(null)
        val upstream = DefaultDataSource.Factory(app, http)
        val cached = streamCache()?.let { c ->
            CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
        // Downloaded HLS and DASH streams are read from the offline store first (16.4).
        val offline = OfflineCache.readThrough(app, cached ?: upstream)
        val resolving = ResolvingDataSource.Factory(offline) { spec: DataSpec -> withStreamHeaders(spec) }
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val sources = DefaultMediaSourceFactory(resolving, extractors)
        val selector = DefaultTrackSelector(app)
        // With spatial audio on, multichannel tracks are kept when the device can spatialize them.
        selector.setParameters(selector.buildUponParameters().setConstrainAudioChannelCountToDeviceCapabilities(true))
        effectsApplied = false
        _stats.value = PlaybackStats()
        builtBufferSec = bufferSec
        val maxMs = bufferSec * 1000
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minOf(15_000, maxMs), maxMs, 2_500, 5_000)
            .build()
        val p = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            .setTrackSelector(selector)
            .setAudioAttributes(audioAttributes(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.videoChangeFrameRateStrategy = frameStrategy
        p.addAnalyticsListener(StatsTracker(_stats, { spatialSetting && Spatial.systemActive(app) }, ::onVideoFormat))
        return p
    }

    // Audio

    /** Sets the effect values. Turning an effect on while passthrough is in use switches to decoded audio. */
    fun setAudioConfig(config: ChainConfig) {
        audioChain.config = config
        if (config.isActive && _stats.value.audioOutput == AudioOutputMode.PASSTHROUGH) {
            passthroughAllowed = false
            rebuild(_decoderMode.value)
            _notices.tryEmit(EngineNotice.DECODED_FOR_EFFECTS)
        }
    }

    /** Playback speed. Passthrough runs only at 1x, so other speeds switch to decoded audio. */
    fun setSpeed(speed: Float) {
        if (speed != 1f && _stats.value.audioOutput == AudioOutputMode.PASSTHROUGH) {
            passthroughAllowed = false
            rebuild(_decoderMode.value)
            _notices.tryEmit(EngineNotice.DECODED_FOR_SPEED)
        }
        player.setPlaybackSpeed(speed)
    }

    fun setOutputPrefs(passthrough: Boolean, spatial: Boolean) {
        val spatialChanged = spatial != spatialSetting
        spatialSetting = spatial
        passthroughSetting = passthrough
        if (spatialChanged) player.setAudioAttributes(audioAttributes(), true)
        // Passthrough changes need a new audio sink: apply now if something is playing.
        val want = wantPassthrough()
        if (want != passthroughAllowed) {
            passthroughAllowed = want
            if (player.mediaItemCount > 0) rebuild(_decoderMode.value)
        }
    }

    private fun wantPassthrough() = passthroughSetting && !audioChain.config.isActive && player.playbackParameters.speed == 1f

    /** Peak output level, 0..1, for the equalizer's level meter. */
    val outputLevel: Float get() = audioChain.peakLevel

    // Video

    fun setToneMapping(method: ToneMapMethod, brightness: Float, displayIsHdr: Boolean) {
        toneMap = method
        toneBrightness = brightness
        displayHdr = displayIsHdr
        player.videoFormat?.let(::onVideoFormat)
    }

    fun setFrameRateStrategy(seamlessOnly: Boolean) {
        frameStrategy = if (seamlessOnly) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        player.videoChangeFrameRateStrategy = frameStrategy
    }

    /** Picture filters for the video that plays now. Effects are re-applied at once. */
    fun setFilter(value: com.troy.orcaplayer.core.model.VideoFilter, skipHdr: Boolean) {
        filter = value
        filterSkipHdr = skipHdr
        player.videoFormat?.let(::onVideoFormat) ?: run {
            if (!value.isNeutral) runCatching { player.setVideoEffects(VideoFilters.effects(value)) }.onSuccess { effectsApplied = true }
        }
    }

    /** Whether filters are in use for the current video (they may be skipped for HDR). */
    private val _filtersActive = MutableStateFlow(false)
    val filtersActive: StateFlow<Boolean> = _filtersActive.asStateFlow()

    /** Plays the sound only: the video track is turned off, so the decoder and screen can rest (20.3). */
    fun setAudioOnly(on: Boolean) {
        if (_audioOnly.value == on) return
        _audioOnly.value = on
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on)
            .build()
    }

    /** GPU tone mapping applies only to HDR video on screens that can't show HDR; filters to SDR video unless allowed. */
    private fun onVideoFormat(format: Format) {
        val hdr = hdrKindOf(format) != HdrKind.SDR
        val tone = if (hdr && !displayHdr) ToneMapping.effects(toneMap, toneBrightness) else emptyList()
        val filters = if (hdr && filterSkipHdr) emptyList() else VideoFilters.effects(filter)
        _filtersActive.value = filters.isNotEmpty()
        val want = tone + filters
        if (want.isEmpty() && !effectsApplied) return
        runCatching { player.setVideoEffects(want) }
        effectsApplied = want.isNotEmpty()
    }

    private fun withStreamHeaders(spec: DataSpec): DataSpec {
        val scheme = spec.uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return spec
        val extra = spec.uri.host?.let { headersByHost[it.lowercase()] }.orEmpty()
        val all = LinkedHashMap<String, String>()
        all.putAll(defaultHeaders)
        all.putAll(extra)
        all.putAll(spec.httpRequestHeaders)
        if (all.keys.none { it.equals("User-Agent", ignoreCase = true) }) all["User-Agent"] = userAgent
        return spec.buildUpon().setHttpRequestHeaders(all).build()
    }

    /** Settings for links and streams (13). */
    fun setStreamPrefs(headers: Map<String, String>, agent: String, bufferSeconds: Int, cacheMegabytes: Long) {
        defaultHeaders = headers
        userAgent = agent.ifBlank { DEFAULT_USER_AGENT }
        bufferSec = bufferSeconds.coerceIn(5, 600)
        val mb = cacheMegabytes.coerceAtLeast(0)
        if (mb != cacheMb) {
            cacheMb = mb
            cacheChanged = true
        }
    }

    /** The disk cache for streamed video. Its size is fixed for the life of the process. */
    private fun streamCache(): SimpleCache? {
        if (cacheMb <= 0) return null
        cache?.let { return it }
        return runCatching {
            SimpleCache(File(app.cacheDir, "stream-cache"), LeastRecentlyUsedCacheEvictor(cacheMb * 1024 * 1024), StandaloneDatabaseProvider(app))
        }.getOrNull()?.also { cache = it }
    }

    /**
     * Swaps the item at [index] for another stream of the same video and continues from
     * [positionMs]. Used by the automatic fallback (14.7).
     */
    fun replaceItem(index: Int, item: PlayItem, positionMs: Long) {
        if (index !in queue.indices) return
        queue = queue.toMutableList().also { it[index] = item }
        if (item.headers.isNotEmpty()) Uri.parse(item.uri).host?.let { headersByHost[it.lowercase()] = item.headers }
        val p = player
        p.replaceMediaItem(index, item.toMediaItem(index))
        p.seekTo(index, positionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
    }

    /** Loads a queue. Positions come from [startPositionMs]; smart resume is applied by the caller. */
    fun load(items: List<PlayItem>, startIndex: Int, startPositionMs: Long, playWhenReady: Boolean = true) {
        // Each new video may use passthrough again when nothing needs decoded audio.
        val want = wantPassthrough()
        if (cacheChanged) {
            // A new cache size needs a new cache: stop what is playing first so nothing reads the old one.
            cacheChanged = false
            passthroughAllowed = want
            player.stop()
            player.clearMediaItems()
            runCatching { cache?.release() }
            cache = null
            rebuild(_decoderMode.value)
        } else if (want != passthroughAllowed || builtBufferSec != bufferSec) {
            passthroughAllowed = want
            rebuild(_decoderMode.value)
        }
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

    /** Adds [item] to the end of the queue, for the next episode found while playing (17.6). */
    fun append(item: PlayItem) {
        queue = queue + item
        if (item.headers.isNotEmpty()) Uri.parse(item.uri).host?.let { headersByHost[it.lowercase()] = item.headers }
        player.addMediaItem(item.toMediaItem(queue.lastIndex))
    }

    /** Rebuilds the player with another decoder mode, keeping queue, position, speed and play state. */
    fun setDecoderMode(mode: DecoderMode) {
        if (mode == _decoderMode.value) return
        _decoderMode.value = mode
        rebuild(mode)
    }

    private fun rebuild(mode: DecoderMode) {
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
        next.trackSelectionParameters = old.trackSelectionParameters
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

        const val DEFAULT_USER_AGENT = "ORCA Player"
    }
}

/** External subtitles are rendered by the app's own subtitle layer, so only the video goes to ExoPlayer. */
@OptIn(UnstableApi::class)
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
        mimeType != null -> builder.setMimeType(mimeType)
        lower.endsWith(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        lower.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
    }
    // A downloaded stream plays only the tracks that were downloaded.
    if (streamKeys.isNotEmpty()) builder.setStreamKeys(streamKeys.mapNotNull(::parseStreamKey))
    return builder.build()
}

/** "period.group.stream" as stored with a download. */
@OptIn(UnstableApi::class)
fun parseStreamKey(text: String): androidx.media3.common.StreamKey? {
    val parts = text.split('.').mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        3 -> androidx.media3.common.StreamKey(parts[0], parts[1], parts[2])
        2 -> androidx.media3.common.StreamKey(parts[0], parts[1])
        else -> null
    }
}
