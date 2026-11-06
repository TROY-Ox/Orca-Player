package com.troy.orcaplayer.ui.mini

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.theme.OrcaTheme
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.settings.ExtrasSettings
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.player.engine.PlaybackEngine
import com.troy.orcaplayer.system.SystemSignals
import com.troy.orcaplayer.themeState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The optional floating window over other apps (20.5): drag to move, pinch or drag the corner to
 * resize, with play and pause, close and full screen. Off by default; it needs the "display over
 * other apps" permission. Picture-in-picture stays the default way to watch over other apps.
 */
@AndroidEntryPoint
class FloatingPlayerService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    @Inject lateinit var engine: PlaybackEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var profiles: ProfileRepository

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE || !canDraw(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) scope.launch { show() }
        return START_NOT_STICKY
    }

    private suspend fun show() {
        val values = settings.values(profiles.currentProfileId.value).first()
        val metrics = resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        val width = (shortSide * when (values[ExtrasSettings.FloatingSize]) { "small" -> 0.45f; "large" -> 0.8f; else -> 0.62f }).toInt()
        val height = width * 9 / 16
        val corner = values[ExtrasSettings.FloatingCorner]
        val rtl = resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val start = corner.endsWith("start")
        val horizontal = if (start != rtl) Gravity.LEFT else Gravity.RIGHT
        val p = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (corner.startsWith("top")) Gravity.TOP else Gravity.BOTTOM) or horizontal
            x = (16 * metrics.density).toInt()
            y = (48 * metrics.density).toInt()
        }
        params = p
        val signals = SystemSignals.read(this)
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingPlayerService)
            setViewTreeSavedStateRegistryOwner(this@FloatingPlayerService)
            setContent {
                val arabic = resources.configuration.locales[0]?.language == "ar"
                OrcaTheme(themeState(values, signals, arabic)) { Window() }
            }
        }
        attachDrag(compose)
        view = compose
        registry.currentState = Lifecycle.State.RESUMED
        runCatching { wm.addView(compose, p) }.onFailure { stopSelf() }
    }

    /** One finger drags the window; two fingers resize it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: ComposeView) {
        var lastX = 0f
        var lastY = 0f
        var startSpan = 0f
        var startW = 0
        var startH = 0
        val metrics = resources.displayMetrics
        val minW = (160 * metrics.density).toInt()
        val maxW = maxOf(metrics.widthPixels, metrics.heightPixels)
        v.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    startSpan = kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    startW = p.width
                    startH = p.height
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2 && startSpan > 0f) {
                        val span = kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val scale = span / startSpan
                        p.width = (startW * scale).toInt().coerceIn(minW, maxW)
                        p.height = (startH * scale).toInt().coerceIn(minW * 9 / 16, maxW)
                    } else {
                        val dx = e.rawX - lastX
                        val dy = e.rawY - lastY
                        lastX = e.rawX
                        lastY = e.rawY
                        // Gravity decides which way x and y grow.
                        p.x += (if (p.gravity and Gravity.RIGHT == Gravity.RIGHT) -dx else dx).toInt()
                        p.y += (if (p.gravity and Gravity.BOTTOM == Gravity.BOTTOM) -dy else dy).toInt()
                    }
                    runCatching { wm.updateViewLayout(v, p) }
                }
                MotionEvent.ACTION_POINTER_UP -> startSpan = 0f
            }
            false
        }
    }

    @OptIn(UnstableApi::class)
    @androidx.compose.runtime.Composable
    private fun Window() {
        val c = Troy.colors
        val player = engine.player
        var playing by remember { mutableStateOf(player.isPlaying) }
        var controls by remember { mutableStateOf(true) }
        DisposableEffect(player) {
            val l = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(state: Int) {
                    if (player.mediaItemCount == 0) stopSelf()
                }
            }
            player.addListener(l)
            onDispose { player.removeListener(l) }
        }
        LaunchedEffect(controls, playing) {
            if (controls && playing) {
                delay(3000)
                controls = false
            }
        }
        Box(
            Modifier.fillMaxSize().clip(TroyShapes.Control).background(c.bg)
                .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { openFull() }) },
        ) {
            PlayerSurface(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = Modifier.fillMaxSize())
            if (controls) {
                Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.35f)))
                Row(Modifier.fillMaxWidth().align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TroyIconButton(Icons.Filled.Fullscreen, stringResource(R.string.floating_full_screen), ::openFull)
                    TroyIconButton(Icons.Filled.Close, stringResource(R.string.mini_close), {
                        engine.player.pause()
                        stopSelf()
                    })
                }
                TroyIconButton(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.player_pause else R.string.player_play),
                    { if (player.isPlaying) player.pause() else player.play() },
                    Modifier.align(Alignment.Center),
                )
            }
        }
    }

    private fun openFull() {
        stopSelf()
        startActivity(
            Intent(com.troy.orcaplayer.player.engine.PlaybackService.ACTION_OPEN_PLAYER)
                .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    override fun onDestroy() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        registry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CLOSE = "com.troy.orcaplayer.action.CLOSE_FLOATING"

        fun canDraw(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

        /** The system screen where the user allows drawing over other apps. */
        fun permissionIntent(context: Context) =
            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))

        fun start(context: Context) = context.startService(Intent(context, FloatingPlayerService::class.java))

        fun stop(context: Context) = context.startService(Intent(context, FloatingPlayerService::class.java).setAction(ACTION_CLOSE))
    }
}
