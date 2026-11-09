package com.troy.orcaplayer.ui.vlc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.AppViewModel
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.theme.OrcaTheme
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.sources.network.ShareSessions
import com.troy.orcaplayer.sources.network.ShareType
import com.troy.orcaplayer.sources.network.ShareUris
import com.troy.orcaplayer.system.SystemSignals
import com.troy.orcaplayer.themeState
import com.troy.orcaplayer.ui.player.timecode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.URLEncoder

/**
 * The libVLC engine (20.8): a second, simpler player for files Media3 can't open, such as some
 * WMV, RMVB or old AVI files. It plays, pauses and seeks; the rest of the app's player features
 * aren't available here, and the screen says so.
 */
@AndroidEntryPoint
class VlcPlayerActivity : AppCompatActivity() {
    private val appVm: AppViewModel by viewModels()
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var pfd: ParcelFileDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        appVm.updateSignals(SystemSignals.read(this))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = intent.getStringExtra(EXTRA_URI)
        if (uri == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val start = intent.getLongExtra(EXTRA_POSITION, 0)
        val vlc = LibVLC(this, arrayListOf("--no-stats", "--audio-time-stretch", "--http-reconnect"))
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        player = mp
        val media = runCatching { mediaFor(vlc, uri) }.getOrNull()

        setContent {
            val values by appVm.settings.collectAsStateWithLifecycle()
            val signals by appVm.signals.collectAsStateWithLifecycle()
            val isArabic = LocalConfiguration.current.locales[0]?.language == "ar"
            OrcaTheme(themeState(values ?: SettingsValues.Defaults, signals, isArabic, videoSurface = true)) {
                VlcScreen(mp, media, title, start, ::finish)
            }
        }
    }

    private fun mediaFor(vlc: LibVLC, uri: String): Media {
        val parsed = Uri.parse(uri)
        val media = when (parsed.scheme) {
            "content" -> {
                val fd = contentResolver.openFileDescriptor(parsed, "r") ?: error("Can't open")
                pfd = fd
                Media(vlc, fd.fileDescriptor)
            }
            "file" -> Media(vlc, parsed.path ?: error("No path"))
            else -> Media(vlc, Uri.parse(networkMrl(uri)))
        }
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>).orEmpty()
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { media.addOption(":http-user-agent=" + it.value) }
        headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { media.addOption(":http-referrer=" + it.value) }
        media.setHWDecoderEnabled(true, false)
        return media
    }

    /** Share URIs become real VLC addresses, with the saved sign-in kept in memory only. */
    private fun networkMrl(uri: String): String {
        val (id, path) = ShareUris.parse(uri) ?: return uri
        val conn = ShareSessions.get(this).store[id] ?: return uri
        val auth = if (conn.anonymous) "" else enc(conn.username) + ":" + enc(conn.password) + "@"
        val port = if (conn.port > 0) ":${conn.port}" else ""
        val encodedPath = path.trim('/').split('/').joinToString("/") { ShareUris.encode(it) }
        return when (conn.type) {
            ShareType.SMB -> "smb://$auth${conn.host}$port/" + ShareUris.encode(conn.path.trim('/').substringBefore('/')) + "/" + encodedPath
            ShareType.SFTP -> "sftp://$auth${conn.host}$port/$encodedPath"
            ShareType.FTP -> "ftp://$auth${conn.host}$port/$encodedPath"
            ShareType.FTPS -> "ftpes://$auth${conn.host}$port/$encodedPath"
            ShareType.WEBDAV -> (if (conn.secure) "https://" else "http://") + "$auth${conn.host}$port/$encodedPath"
            ShareType.DLNA -> uri
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.detachViews() }
            runCatching { p.release() }
        }
        runCatching { libVlc?.release() }
        runCatching { pfd?.close() }
        player = null
        libVlc = null
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_HEADERS = "headers"

        /** The private folder is encrypted inside the app, so only Media3 can read it. */
        fun canOpen(uri: String): Boolean = !uri.startsWith("orcavault:")

        fun start(context: Context, item: PlayItem, positionMs: Long) {
            context.startActivity(
                Intent(context, VlcPlayerActivity::class.java)
                    .putExtra(EXTRA_URI, item.uri)
                    .putExtra(EXTRA_TITLE, item.title)
                    .putExtra(EXTRA_POSITION, positionMs)
                    .putExtra(EXTRA_HEADERS, HashMap(item.headers)),
            )
        }
    }
}

@Composable
private fun VlcScreen(player: MediaPlayer, media: Media?, title: String, startMs: Long, onClose: () -> Unit) {
    val c = Troy.colors
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(media == null) }
    var controls by remember { mutableStateOf(true) }
    var started by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    player.attachViews(layout, null, true, false)
                    player.setEventListener { e ->
                        when (e.type) {
                            MediaPlayer.Event.Playing -> playing = true
                            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                            MediaPlayer.Event.LengthChanged -> length = player.length
                            MediaPlayer.Event.EndReached -> onClose()
                            MediaPlayer.Event.EncounteredError -> failed = true
                        }
                    }
                    if (media != null && !started) {
                        started = true
                        player.media = media
                        media.release()
                        player.play()
                        if (startMs > 0) player.setTime(startMs)
                    }
                }
            },
            modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
        )
        LaunchedEffect(Unit) {
            while (true) {
                if (dragging == null) position = player.time.coerceAtLeast(0)
                if (length <= 0) length = player.length
                delay(500)
            }
        }
        LaunchedEffect(controls, playing) {
            if (controls && playing) {
                delay(4000)
                controls = false
            }
        }
        AnimatedVisibility(controls || failed, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.45f)).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TroyIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back), onClose)
                    Text(title, style = Troy.type.title, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
                }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp)) {
                    TroyAlert(stringResource(if (failed) R.string.vlc_failed else R.string.vlc_limited), if (failed) AlertKind.DANGER else AlertKind.INFO)
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    TroyIconButton(Icons.Filled.Replay10, stringResource(R.string.vlc_back_10), { player.setTime((player.time - 10_000).coerceAtLeast(0)) })
                    TroyIconButton(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (playing) R.string.vlc_pause else R.string.vlc_play),
                        { if (playing) player.pause() else player.play() },
                        modifier = Modifier.size(64.dp),
                    )
                    TroyIconButton(Icons.Filled.Forward10, stringResource(R.string.vlc_forward_10), { player.setTime(player.time + 10_000) })
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val shown = dragging?.let { (it * length).toLong() } ?: position
                    Row {
                        Text(timecode(shown), style = Troy.type.mono, color = c.text)
                        Box(Modifier.weight(1f))
                        Text(timecode(length), style = Troy.type.mono, color = c.text2)
                    }
                    val value = dragging ?: if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
                    Slider(
                        value = value,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { f -> if (length > 0) player.setTime((f * length).toLong()) }
                            dragging = null
                        },
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.controlFill),
                    )
                }
            }
        }
    }
}
