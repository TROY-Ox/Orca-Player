package com.troy.orcaplayer.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import com.troy.orcaplayer.core.model.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the phone shows while a Chromecast plays. */
data class CastState(
    val device: String,
    val title: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Double = 1.0,
    val subtitles: List<Pair<Long, String>> = emptyList(),
    val activeSubtitle: Long? = null,
    val loading: Boolean = false,
)

enum class CastProblem { NO_NETWORK, FORMAT, LOAD_FAILED }

/**
 * Chromecast and Google TV (20.7): the phone becomes the remote. Local files, shares and the
 * private folder are served by [CastServer]; links that need custom headers go through it too.
 */
class CastController private constructor(private val context: Context) {
    private val _state = MutableStateFlow<CastState?>(null)
    val state: StateFlow<CastState?> = _state.asStateFlow()
    private val _problem = MutableStateFlow<CastProblem?>(null)
    val problem: StateFlow<CastProblem?> = _problem.asStateFlow()

    /** Called when a session starts, so the player can hand over what it's playing. */
    var onSessionStarted: (() -> Unit)? = null
    /** Called when casting ends, with the position the TV reached. */
    var onSessionEnded: ((Long) -> Unit)? = null

    private val castContext: CastContext? by lazy {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) null
        else runCatching {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(context)
        }.getOrNull()
    }

    val available: Boolean get() = castContext != null

    private val session: CastSession? get() = castContext?.sessionManager?.currentCastSession
    private val client: RemoteMediaClient? get() = session?.remoteMediaClient
    private var lastPosition = 0L

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = refresh()
        override fun onMetadataUpdated() = refresh()
    }
    private val progress = RemoteMediaClient.ProgressListener { pos, dur ->
        lastPosition = pos
        _state.value = _state.value?.copy(positionMs = pos, durationMs = dur)
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) = attach(s, started = true)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = attach(s, started = false)
        override fun onSessionEnded(s: CastSession, error: Int) = detach()
        override fun onSessionSuspended(s: CastSession, reason: Int) = Unit
        override fun onSessionStarting(s: CastSession) = Unit
        override fun onSessionStartFailed(s: CastSession, error: Int) = Unit
        override fun onSessionEnding(s: CastSession) { lastPosition = s.remoteMediaClient?.approximateStreamPosition ?: lastPosition }
        override fun onSessionResuming(s: CastSession, id: String) = Unit
        override fun onSessionResumeFailed(s: CastSession, error: Int) = Unit
    }
    private var listening = false

    fun start() {
        val ctx = castContext ?: return
        if (listening) return
        listening = true
        ctx.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        ctx.sessionManager.currentCastSession?.let { attach(it, started = false) }
    }

    private fun attach(s: CastSession, started: Boolean) {
        val c = s.remoteMediaClient ?: return
        c.unregisterCallback(callback)
        c.registerCallback(callback)
        c.removeProgressListener(progress)
        c.addProgressListener(progress, 1000)
        _state.value = CastState(device = s.castDevice?.friendlyName.orEmpty())
        refresh()
        if (started) onSessionStarted?.invoke()
    }

    private fun detach() {
        val end = lastPosition
        _state.value = null
        CastServer.get(context).clear()
        onSessionEnded?.invoke(end)
    }

    private fun refresh() {
        val c = client ?: return
        val status = c.mediaStatus
        val info = c.mediaInfo
        val subs = info?.mediaTracks.orEmpty().filter { it.type == MediaTrack.TYPE_TEXT }.map { it.id to (it.name ?: it.language ?: "") }
        _state.value = (_state.value ?: CastState(session?.castDevice?.friendlyName.orEmpty())).copy(
            title = info?.metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
            playing = status?.playerState == MediaStatus.PLAYER_STATE_PLAYING,
            buffering = status?.playerState == MediaStatus.PLAYER_STATE_BUFFERING || status?.playerState == MediaStatus.PLAYER_STATE_LOADING,
            durationMs = info?.streamDuration?.takeIf { it > 0 } ?: _state.value?.durationMs ?: 0,
            volume = session?.volume ?: 1.0,
            subtitles = subs,
            activeSubtitle = status?.activeTrackIds?.firstOrNull { id -> subs.any { it.first == id } },
        )
    }

    /** Sends [item] to the TV, starting at [positionMs]. */
    suspend fun load(item: PlayItem, positionMs: Long): Boolean {
        val c = client ?: return false
        _problem.value = null
        _state.value = _state.value?.copy(loading = true, title = item.title)
        val mime = mimeFor(item)
        if (mime == null) {
            _problem.value = CastProblem.FORMAT
            _state.value = _state.value?.copy(loading = false)
            return false
        }
        val server = CastServer.get(context)
        val direct = (item.uri.startsWith("https://") || item.uri.startsWith("http://")) && item.headers.isEmpty()
        val url = if (direct) item.uri else server.serve(item.uri, mime, item.headers)
        if (url == null) {
            _problem.value = CastProblem.NO_NETWORK
            _state.value = _state.value?.copy(loading = false)
            return false
        }
        val tracks = withContext(Dispatchers.IO) {
            item.subtitles.take(8).mapIndexedNotNull { i, s ->
                val vtt = runCatching { vttFor(s.url, s.headers) }.getOrNull() ?: return@mapIndexedNotNull null
                val subUrl = server.serveText(vtt, "text/vtt") ?: return@mapIndexedNotNull null
                MediaTrack.Builder((i + 1).toLong(), MediaTrack.TYPE_TEXT)
                    .setName(s.label ?: s.language ?: "Subtitle ${i + 1}")
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(subUrl)
                    .setContentType("text/vtt")
                    .apply { s.language?.let { setLanguage(it) } }
                    .build()
            }
        }
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            item.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            item.poster?.takeIf { it.startsWith("http") }?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(meta)
            .setMediaTracks(tracks)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(positionMs)
            .build()
        c.load(request).setResultCallback { r ->
            _state.value = _state.value?.copy(loading = false)
            if (!r.status.isSuccess) _problem.value = CastProblem.LOAD_FAILED
        }
        return true
    }

    fun toggle() {
        val c = client ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(ms: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(ms.coerceAtLeast(0)).build())
    }

    fun seekBy(deltaMs: Long) = seekTo((client?.approximateStreamPosition ?: 0) + deltaMs)

    fun setVolume(v: Double) {
        runCatching { session?.volume = v.coerceIn(0.0, 1.0) }
        _state.value = _state.value?.copy(volume = v)
    }

    fun setSubtitle(id: Long?) {
        client?.setActiveMediaTracks(if (id == null) LongArray(0) else longArrayOf(id))
    }

    fun position(): Long = client?.approximateStreamPosition ?: lastPosition

    fun stop() {
        lastPosition = position()
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun clearProblem() { _problem.value = null }

    private fun mimeFor(item: PlayItem): String? {
        val m = item.mimeType?.lowercase()
        val path = item.uri.substringBefore('?').lowercase()
        val byExt = when {
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".m4a") || path.endsWith(".aac") -> "audio/mp4"
            path.endsWith(".flac") -> "audio/flac"
            path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
            path.endsWith(".wav") -> "audio/wav"
            else -> null
        }
        val type = when (m) {
            "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "application/x-mpegURL"
            null -> byExt ?: (if (item.uri.startsWith("http")) "video/mp4" else null)
            else -> m
        }
        // The Default Media Receiver plays MP4, WebM, HLS, DASH and common audio; Google TV also plays MKV.
        return type?.takeIf { t -> CASTABLE.any { t.startsWith(it) } }
    }

    private fun vttFor(url: String, headers: Map<String, String>): String? {
        val text = when {
            url.startsWith("http") -> {
                val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }
            else -> context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes().decodeToString() }
        } ?: return null
        return if (text.trimStart { it.code == 0xFEFF }.startsWith("WEBVTT")) text
        else if (url.substringBefore('?').lowercase().endsWith(".srt") || text.contains("-->")) CastServer.srtToVtt(text)
        else null
    }

    private val http by lazy { OkHttpClient() }

    companion object {
        private val CASTABLE = listOf("video/mp4", "video/webm", "video/x-matroska", "application/x-mpegurl", "application/x-mpegURL", "application/dash+xml", "audio/")

        @Volatile private var instance: CastController? = null
        fun get(context: Context): CastController = instance ?: synchronized(this) { instance ?: CastController(context.applicationContext).also { instance = it } }
    }
}
