package com.troy.orcaplayer.ui.player

import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.troy.orcaplayer.core.model.SaveDecision
import com.troy.orcaplayer.core.model.ToolId
import com.troy.orcaplayer.core.settings.ToolSettings
import com.troy.orcaplayer.ui.tools.ToolActivity
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import android.view.HapticFeedbackConstants
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.GlassPanel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.core.model.ControlSlot
import com.troy.orcaplayer.core.model.PlayerControl
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.core.settings.AudioVideoSettings as AV
import com.troy.orcaplayer.player.engine.AudioOutputMode
import com.troy.orcaplayer.player.engine.DecoderMode
import com.troy.orcaplayer.player.engine.SliderConfig
import com.troy.orcaplayer.player.engine.SliderDrag
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the screen needs from its activity. */
interface PlayerHost {
    fun close()
    fun enterPip()
    val canPip: Boolean
    fun pickSubtitle()
    /** "auto", "landscape", "portrait" or "system", for this session. */
    fun lockOrientation(mode: String)
    val orientationLock: String
    val isPhone: Boolean
    fun rotate()
    /** Opens the floating window over other apps and leaves the full-screen player (20.5). */
    fun floatingWindow() = Unit
    val canFloat: Boolean get() = false
    /** True while the video plays on a Cast device (20.7). */
    val casting: Boolean get() = false
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel, host: PlayerHost, levels: SliderLevels, isPip: Boolean) {
    val c = Troy.colors
    val downloads: com.troy.orcaplayer.ui.downloads.DownloadsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val castState by vm.castState.collectAsStateWithLifecycle()
    val castProblem by vm.castProblem.collectAsStateWithLifecycle()
    val casting = castState != null
    val player by vm.player.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val embedded by vm.embeddedCues.collectAsStateWithLifecycle()
    val external by vm.external.collectAsStateWithLifecycle()
    val delayMs by vm.subtitleDelayMs.collectAsStateWithLifecycle()
    val subSpeed by vm.subtitleSpeed.collectAsStateWithLifecycle()
    val subsOff by vm.subtitlesOff.collectAsStateWithLifecycle()
    val pill by vm.resumePill.collectAsStateWithLifecycle()
    val onlineNotice by vm.onlineNotice.collectAsStateWithLifecycle()
    val onlineResults by vm.onlineResults.collectAsStateWithLifecycle()
    val decoder by vm.decoderMode.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val fx by vm.sound.fx.collectAsStateWithLifecycle()
    val audioStore by vm.sound.store.collectAsStateWithLifecycle()
    val fxOrigin by vm.sound.origin.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var boostWarnAt by remember { mutableLongStateOf(0L) }
    val toolsLayout by vm.toolsLayout.collectAsStateWithLifecycle()
    val abLoop by vm.extras.abLoop.collectAsStateWithLifecycle()
    val abStart by vm.extras.abStart.collectAsStateWithLifecycle()
    val sleep by vm.extras.sleep.collectAsStateWithLifecycle()
    val night by vm.extras.night.collectAsStateWithLifecycle()
    val hasVideoPrefs by vm.extras.hasVideoPrefs.collectAsStateWithLifecycle()
    val applied by vm.applied.collectAsStateWithLifecycle()
    val skipOffer by vm.binge.skip.collectAsStateWithLifecycle()
    val nextOffer by vm.binge.next.collectAsStateWithLifecycle()
    val marking by vm.binge.marking.collectAsStateWithLifecycle()
    val limitReached by vm.binge.limitReached.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
    val filter by vm.picture.filter.collectAsStateWithLifecycle()
    val filterStore by vm.picture.store.collectAsStateWithLifecycle()
    val filterPerVideo by vm.picture.perVideo.collectAsStateWithLifecycle()
    val filtersActive by vm.picture.active.collectAsStateWithLifecycle()
    val audioDevice by vm.picture.audioDevice.collectAsStateWithLifecycle()
    val audioDelay by vm.picture.audioDelayMs.collectAsStateWithLifecycle()
    val kids = profile?.isKids == true
    var parentGate by remember { mutableStateOf(false) }
    var askOverlay by remember { mutableStateOf(false) }
    val surfaceRef = remember { VideoSurfaceRef() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reminderFor by remember { mutableStateOf<ToolId?>(null) }
    var sleepLeft by remember { mutableStateOf<Long?>(null) }
    val rtlNow = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Volume boost on the slider: up to the maximum while extra volume is on.
    val extraVolume = settings[AV.ExtraVolume]
    val maxBoost = vm.sound.maxBoost(settings)
    LaunchedEffect(extraVolume, maxBoost, fx.boostPercent) {
        levels.boostMax = if (extraVolume) maxBoost else 100
        levels.showBoost(if (extraVolume) fx.boostPercent else 100)
    }
    LaunchedEffect(levels) {
        levels.onBoost = { b ->
            vm.sound.setBoost(b)
            if (vm.sound.shouldWarnBoost(b)) boostWarnAt = System.currentTimeMillis()
        }
    }

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by rememberSaveable { mutableStateOf(PlayerPanel.NONE) }
    var aspect by rememberSaveable { mutableStateOf(AspectMode.FIT) }
    var aspectToastAt by remember { mutableLongStateOf(0L) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var skipFeedback by remember { mutableStateOf<Pair<TapZone, Int>?>(null) }
    var skipAt by remember { mutableLongStateOf(0L) }
    var boosting by remember { mutableStateOf(false) }
    var remaining by rememberSaveable { mutableStateOf(settings[PlayerSettings.ShowRemaining]) }
    var unlockShownAt by remember { mutableLongStateOf(0L) }
    // Kids profiles start locked, so a tap can't leave the video (18).
    var kidsLocked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(kids, settings[com.troy.orcaplayer.core.settings.BrowsingSettings.KidsLockPlayer]) {
        if (kids && settings[com.troy.orcaplayer.core.settings.BrowsingSettings.KidsLockPlayer] && !kidsLocked) {
            kidsLocked = true
            vm.locked.value = true
            unlockShownAt = System.currentTimeMillis()
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Per-video values: applied once each time a video starts.
    LaunchedEffect(applied.first) {
        val prefs = applied.second
        prefs.aspect?.let { a -> AspectMode.entries.firstOrNull { it.name == a }?.let { aspect = it } }
        zoom = prefs.zoom ?: 1f
        pan = Offset.Zero
        prefs.brightness?.let { levels.setBrightnessPercent(it) }
    }
    // Brightness is remembered for the video once the finger has settled.
    LaunchedEffect(levels) {
        snapshotFlow { levels.brightness }.drop(1).debounce(1500).collect { vm.rememberBrightness(it) }
    }
    // Night mode also turns the compressor on or off when its setting changes.
    LaunchedEffect(night, settings[ToolSettings.NightCompressor]) {
        vm.sound.setNightCompressor(night && settings[ToolSettings.NightCompressor])
    }
    TickEffect(sleep != null) { sleepLeft = vm.sleepRemaining() }

    fun launchTool(tool: ToolId) {
        val item = vm.currentItem() ?: return
        val saves = tool != ToolId.MEDIA_INFO && tool != ToolId.SUBTITLE_EDITOR
        if (saves) {
            when (vm.saveDecision()) {
                SaveDecision.ALLOWED -> Unit
                SaveDecision.NEEDS_REMINDER -> { reminderFor = tool; return }
                SaveDecision.DENIED_DRM -> { Toast.makeText(context, R.string.save_denied_drm, Toast.LENGTH_LONG).show(); return }
                SaveDecision.DENIED_BY_SOURCE -> { Toast.makeText(context, R.string.save_denied_source, Toast.LENGTH_LONG).show(); return }
                SaveDecision.DENIED_BY_SETTINGS -> { Toast.makeText(context, R.string.save_denied_settings, Toast.LENGTH_LONG).show(); return }
            }
        }
        if (tool == ToolId.DOWNLOAD) {
            // Downloads the link that is playing now; local files are already on the device (16.1).
            when {
                !(item.uri.startsWith("http://", true) || item.uri.startsWith("https://", true)) ->
                    Toast.makeText(context, R.string.dl_already_local, Toast.LENGTH_SHORT).show()
                downloads.check(item.source, drm = false, downloadable = true) == com.troy.orcaplayer.ui.downloads.DownloadCheck.KIDS ->
                    Toast.makeText(context, R.string.dl_kids_blocked, Toast.LENGTH_LONG).show()
                else -> {
                    downloads.downloadItem(item, kind = null)
                    Toast.makeText(context, R.string.dl_queued, Toast.LENGTH_SHORT).show()
                }
            }
            panel = PlayerPanel.NONE
            return
        }
        if (tool == ToolId.SUBTITLE_EDITOR && external == null) {
            Toast.makeText(context, R.string.sub_editor_needs_file, Toast.LENGTH_LONG).show()
            return
        }
        vm.pause()
        panel = PlayerPanel.NONE
        context.startActivity(
            ToolActivity.intent(
                context, tool, Uri.parse(item.uri), item.title, item.headers, vm.currentPosition(),
                external?.uri?.let(Uri::parse),
            ),
        )
    }

    fun takeScreenshot() {
        val view = surfaceRef.view ?: return
        val subtitleLines = if (settings[ToolSettings.ScreenshotSubtitles]) {
            when {
                external != null -> external!!.timeline.at(ui.positionMs, delayMs, subSpeed).map { vm.textFor(it.text) }
                subsOff -> emptyList()
                else -> embedded.mapNotNull { it.text?.toString()?.let(vm::textFor) }
            }
        } else emptyList()
        val png = settings[ToolSettings.ScreenshotFormat] == "png"
        val title = ui.title
        val pos = ui.positionMs
        scope.launch {
            val bmp = Screenshot.capture(view, ui.videoWidth.takeIf { it > 0 } ?: view.width, ui.videoHeight.takeIf { it > 0 } ?: view.height)
            if (bmp == null) {
                Toast.makeText(context, R.string.shot_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            Screenshot.burnSubtitles(bmp, subtitleLines, rtlNow)
            val ok = runCatching { Screenshot.save(context, bmp, title, pos, png) }.isSuccess
            bmp.recycle()
            Toast.makeText(context, if (ok) R.string.shot_saved else R.string.shot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val skipSeconds = settings[PlayerSettings.SkipSeconds].toIntOrNull() ?: 10
    val view = LocalView.current
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun touch() {
        lastInteraction = System.currentTimeMillis()
    }

    // Clock for fading indicators.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    // Auto-hide the controls while playing.
    LaunchedEffect(controlsVisible, lastInteraction, ui.playing, panel) {
        if (controlsVisible && ui.playing && panel == PlayerPanel.NONE) {
            delay(3500)
            controlsVisible = false
        }
    }

    val sliderPrefs = SliderPrefs(
        display = settings[PlayerSettings.SliderDisplay],
        swap = settings[PlayerSettings.SliderSwap],
        config = SliderConfig(
            acceleration = when (settings[PlayerSettings.SliderAcceleration]) { "low" -> 0.6f; "high" -> 1.6f; else -> 1f },
            fastSpeedDp = when (settings[PlayerSettings.SliderFastMax]) { "low" -> 2000f; "high" -> 900f; else -> 1400f },
            linear = settings[PlayerSettings.SliderMode] == "linear",
        ),
        brightnessStep = settings[PlayerSettings.SliderStep].toFloatOrNull() ?: 1f,
        haptics = settings[PlayerSettings.SliderHaptics],
    )
    val seekMsPerDp = when (settings[PlayerSettings.SeekSensitivity]) { "low" -> 60f; "high" -> 400f; else -> 150f }
    val longPressSpeed = settings[PlayerSettings.LongPressSpeed].toFloatOrNull() ?: 2f

    val state = rememberUpdatedState(
        GestureDeps(ui, sliderPrefs, seekMsPerDp, longPressSpeed, skipSeconds, controlsVisible, aspect),
    )
    val handler = remember(vm, levels) {
        object : GestureHandler {
            var seekBase = 0L
            var drag: SliderDrag? = null
            var kind: SliderKind? = null
            var speedBefore = 1f

            override fun onTap() {
                if (panel != PlayerPanel.NONE) {
                    panel = PlayerPanel.NONE
                    return
                }
                controlsVisible = !controlsVisible
                touch()
            }

            override fun onDoubleTapStart() {
                if (panel == PlayerPanel.NONE) controlsVisible = !controlsVisible
            }

            override fun onMultiTap(zone: TapZone, streak: Int) {
                val s = state.value.skipSeconds * 1000L
                when (zone) {
                    TapZone.START -> vm.seekBy(-s)
                    TapZone.END -> vm.seekBy(s)
                    TapZone.CENTER -> if (streak == 1) vm.togglePlay()
                }
                if (zone != TapZone.CENTER) {
                    skipFeedback = zone to state.value.skipSeconds * streak
                    skipAt = System.currentTimeMillis()
                }
            }

            override fun onLongPressStart() {
                speedBefore = state.value.ui.speed
                boosting = true
                vm.setSpeed(state.value.longPressSpeed)
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onLongPressEnd() {
                boosting = false
                vm.setSpeed(speedBefore)
            }

            override fun onSeekStart() {
                seekBase = state.value.ui.positionMs
                seekPreview = seekBase
            }

            override fun onSeekDrag(totalDp: Float) {
                val d = state.value.ui.durationMs
                val target = seekBase + (totalDp * state.value.seekMsPerDp).toLong()
                seekPreview = if (d > 0) target.coerceIn(0, d) else target.coerceAtLeast(0)
            }

            override fun onSeekEnd() {
                seekPreview?.let { vm.seekTo(it) }
                seekPreview = null
            }

            override fun onVerticalStart(leftHalf: Boolean) {
                val p = state.value.sliders
                val k = if (leftHalf != p.swap) SliderKind.BRIGHTNESS else SliderKind.VOLUME
                kind = k
                levels.activeKind = k
                drag = when (k) {
                    SliderKind.BRIGHTNESS -> SliderDrag(levels.brightness, SliderLevels.MIN_BRIGHTNESS, 100f, p.brightnessStep, p.config)
                    SliderKind.VOLUME -> {
                        levels.syncVolume()
                        SliderDrag(levels.combinedIndex.toFloat(), 0f, levels.combinedMax.toFloat(), 1f, p.config.copy(slowStepDp = 14f))
                    }
                }
            }

            override fun onVerticalMove(leftHalf: Boolean, upDp: Float, dtMs: Long) {
                val d = drag ?: return
                val v = d.move(upDp, dtMs)
                when (kind) {
                    SliderKind.BRIGHTNESS -> if (v != levels.brightness) levels.setBrightnessPercent(v)
                    SliderKind.VOLUME -> if (v.roundToInt() != levels.combinedIndex) levels.setCombinedIndex(v.roundToInt())
                    null -> Unit
                }
                if (d.tick && state.value.sliders.haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }

            override fun onVerticalEnd(leftHalf: Boolean) {
                drag = null
                kind = null
                levels.activeKind = null
            }

            override fun onPinch(zoom: Float, pan: Offset) {
                val z = (zoomState() * zoom).coerceIn(0.5f, 4f)
                setZoom(z, pan)
            }

            override fun onPinchEnd() {
                if (zoomState() < 0.9f) {
                    // Pinch in: back to fit. Pinch out from fit past 1.15: fill.
                    aspect = AspectMode.FIT
                    setZoom(1f, null)
                } else if (zoomState() < 1f) {
                    setZoom(1f, null)
                } else if (state.value.aspect == AspectMode.FIT && zoomState() in 1.15f..1.35f) {
                    aspect = AspectMode.FILL
                    setZoom(1f, null)
                }
                aspectToastAt = System.currentTimeMillis()
            }

            fun zoomState() = zoom

            fun setZoom(z: Float, delta: Offset?) {
                zoom = z
                pan = if (delta == null || z <= 1f) Offset.Zero else pan + delta
            }
        }
    }

    val systemGestures = WindowInsets.systemGestures
    val exclusion = floatArrayOf(
        systemGestures.getLeft(density, dir).toFloat(),
        systemGestures.getTop(density).toFloat(),
        systemGestures.getRight(density, dir).toFloat(),
        systemGestures.getBottom(density).toFloat(),
    )
    val gestureConfig = GestureConfig(
        doubleTap = settings[PlayerSettings.DoubleTap],
        swipeSeek = settings[PlayerSettings.SwipeSeek],
        swipeSliders = settings[PlayerSettings.SwipeSliders],
        pinch = settings[PlayerSettings.PinchZoom],
        longPress = settings[PlayerSettings.LongPress],
        exclusion = exclusion,
        enabled = !locked && !isPip,
    )
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || locked) return@onPreviewKeyEvent false
                touch()
                when (e.key) {
                    Key.Spacebar, Key.MediaPlayPause, Key.K -> { vm.togglePlay(); true }
                    Key.MediaPlay -> { vm.player.value.play(); true }
                    Key.MediaPause -> { vm.pause(); true }
                    Key.MediaFastForward, Key.L -> { vm.seekBy(skipSeconds * 1000L); true }
                    Key.MediaRewind, Key.J -> { vm.seekBy(-skipSeconds * 1000L); true }
                    Key.MediaNext -> { vm.next(); true }
                    Key.MediaPrevious -> { vm.previous(); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (!controlsVisible) { controlsVisible = true; vm.togglePlay(); true } else false
                    Key.DirectionLeft -> if (!controlsVisible) { vm.seekBy(-skipSeconds * 1000L); true } else false
                    Key.DirectionRight -> if (!controlsVisible) { vm.seekBy(skipSeconds * 1000L); true } else false
                    Key.DirectionUp, Key.DirectionDown, Key.Menu -> if (!controlsVisible) { controlsVisible = true; true } else false
                    Key.Back, Key.Escape -> if (panel != PlayerPanel.NONE) { panel = PlayerPanel.NONE; true } else false
                    else -> false
                }
            },
    ) {
        // Video
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().playerGestures(gestureConfig, handler), contentAlignment = Alignment.Center) {
            val cw = maxWidth.value
            val ch = maxHeight.value
            val a = aspect.ratio ?: ui.aspect
            val (w, h) = when (aspect) {
                AspectMode.STRETCH -> cw to ch
                AspectMode.FILL -> if (cw / ch > a) cw to cw / a else ch * a to ch
                AspectMode.ORIGINAL -> with(density) {
                    if (ui.videoWidth > 0) ui.videoWidth.toDp().value to ui.videoHeight.toDp().value else cw to cw / a
                }
                else -> if (cw / ch > a) ch * a to ch else cw to cw / a
            }
            Box(
                Modifier
                    .requiredSize((w * zoom).dp, (h * zoom).dp)
                    .offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) },
            ) {
                VideoSurface(player, surfaceRef, Modifier.fillMaxSize())
            }
        }
        if (audioOnly && !isPip) {
            AudioOnlyView(
                title = ui.title,
                subtitle = vm.currentItem()?.subtitle,
                poster = vm.currentItem()?.poster,
                index = ui.index,
                count = ui.count,
                onQueue = { panel = PlayerPanel.QUEUE },
                onExit = { vm.setAudioOnly(false) },
            )
        }
        if (levels.dimAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = levels.dimAlpha)))
        if (night) {
            val dim = (settings[ToolSettings.NightDim].toIntOrNull() ?: 35) / 100f
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            // The warm filter uses the theme's warm signal colour at a low strength.
            if (settings[ToolSettings.NightWarm]) Box(Modifier.fillMaxSize().background(c.warn.copy(alpha = 0.08f)))
        }

        // Subtitles
        val drawn = when {
            external != null -> external!!.timeline.at(ui.positionMs, delayMs, subSpeed).map { DrawnCue(vm.textFor(it.text), it.italic, it.bold, top = it.alignment in 7..9) }
            subsOff -> emptyList()
            else -> embedded.toDrawn().map { it.copy(text = it.text?.let(vm::textFor)) }
        }
        SubtitleLayer(drawn, subtitleStyle(settings), controlsVisible && !isPip)

        if (isPip) return@Box

        // Buffering
        if (ui.buffering) {
            CircularProgressIndicator(color = c.accent, modifier = Modifier.align(Alignment.Center).size(56.dp))
        }

        // Skip feedback
        val feedback = skipFeedback
        if (feedback != null && now - skipAt < 700) {
            val align = if (feedback.first == TapZone.START) Alignment.CenterStart else Alignment.CenterEnd
            Text(
                (if (feedback.first == TapZone.START) "−" else "+") + formatNumber(feedback.second) + " " + stringResource(R.string.player_seconds_short),
                style = Troy.type.headline,
                color = c.text,
                modifier = Modifier.align(align).padding(horizontal = 56.dp).glass(TroyShapes.Pill).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        seekPreview?.let { target ->
            val delta = target - ui.positionMs
            Text(
                timecode(target) + "  (" + (if (delta >= 0) "+" else "−") + timecode(abs(delta)) + ")",
                style = Troy.type.headline,
                color = c.text,
                modifier = Modifier.align(Alignment.Center).glass(TroyShapes.Panel).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        if (boosting) {
            Text(
                stringResource(R.string.player_boost, formatNumber(state.value.longPressSpeed)),
                style = Troy.type.label,
                color = c.onAccent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).background(c.accent, TroyShapes.Pill).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        if (now - aspectToastAt < 1200) {
            Text(
                aspectLabel(aspect),
                style = Troy.type.title,
                color = c.text,
                modifier = Modifier.align(Alignment.Center).glass(TroyShapes.Panel).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        if (now - boostWarnAt < 5000) {
            TroyAlert(
                stringResource(R.string.player_boost_warning),
                AlertKind.WARN,
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 56.dp, start = 24.dp, end = 24.dp)
                    .widthIn(max = 480.dp).glass(TroyShapes.Panel).padding(12.dp),
            )
        }
        if (settings[AV.InfoOverlay]) {
            VideoInfoOverlay(
                stats,
                bufferMs = (ui.bufferedMs - ui.positionMs).coerceAtLeast(0),
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 64.dp, start = 16.dp),
            )
        }

        // Brightness and volume bars
        val sliderVisible: (SliderKind, Long) -> Boolean = { k, shownAt ->
            when (sliderPrefs.display) {
                "hidden" -> false
                "always" -> controlsVisible || levels.activeKind == k || now - shownAt < 1200
                else -> levels.activeKind == k || now - shownAt < 1200
            }
        }
        val brightnessStart = !sliderPrefs.swap
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
                LevelBar(
                    SliderKind.BRIGHTNESS,
                    levels.brightness,
                    !locked && sliderVisible(SliderKind.BRIGHTNESS, levels.brightnessShownAt),
                    stringResource(R.string.player_brightness),
                    { levels.setBrightnessPercent(it) },
                    Modifier.align(if (brightnessStart) Alignment.CenterStart else Alignment.CenterEnd),
                )
                LevelBar(
                    SliderKind.VOLUME,
                    levels.volumePercent,
                    !locked && sliderVisible(SliderKind.VOLUME, levels.volumeShownAt),
                    stringResource(R.string.player_volume),
                    { levels.setVolumePercent(it) },
                    Modifier.align(if (brightnessStart) Alignment.CenterEnd else Alignment.CenterStart),
                    maxPercent = levels.boostMax.toFloat(),
                    boostLabel = stringResource(R.string.player_boost_label),
                )
            }
        }

        // Controls
        AnimatedVisibility(
            controlsVisible && !locked,
            enter = fadeIn(fadeSpec()),
            exit = fadeOut(fadeSpec()),
        ) {
            ControlsOverlay(
                vm = vm,
                host = host,
                ui = ui,
                // Kids profiles have no Tools panel (18).
                layout = layout.controls.filter { it.visible && !(kids && it.control == com.troy.orcaplayer.core.model.PlayerControl.TOOLS) },
                skipSeconds = skipSeconds,
                remaining = remaining,
                onToggleRemaining = { remaining = !remaining; touch() },
                // The controls hide while a panel is open; the scrim dims the video.
                onPanel = { panel = it; controlsVisible = false; touch() },
                onAspect = {
                    aspect = aspect.next()
                    zoom = 1f
                    pan = Offset.Zero
                    vm.rememberAspect(aspect.name, 1f)
                    aspectToastAt = System.currentTimeMillis()
                    touch()
                },
                onLock = { vm.locked.value = true; unlockShownAt = System.currentTimeMillis() },
                onPreview = { seekPreview = it; touch() },
                onTouch = { touch() },
            )
        }

        // Resume pill
        pill?.let { p ->
            Row(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 96.dp)
                    .glass(TroyShapes.Pill).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (p.ask) {
                    Text(stringResource(R.string.player_continue_from, timecode(p.positionMs)), style = Troy.type.body, color = c.text)
                    TroyButton(stringResource(R.string.player_continue), vm::resumeFromPill, kind = ButtonKind.PRIMARY)
                } else {
                    Text(stringResource(R.string.player_resumed_at, timecode(p.positionMs)), style = Troy.type.body, color = c.text)
                    TroyButton(stringResource(R.string.player_start_over), vm::startOver, kind = ButtonKind.GHOST)
                }
            }
        }

        // Skip intro, recap and credits; Next Episode with its countdown (17.6).
        if (!locked && !isPip && panel == PlayerPanel.NONE && pill == null) {
            BingeButtons(
                skip = skipOffer,
                next = nextOffer,
                marking = marking,
                onSkip = { player.let(vm.binge::skip) },
                onNext = vm::next,
                onDismissNext = vm.binge::dismissNext,
                onEndMark = {
                    if (!vm.binge.endMark(vm.currentPosition())) Toast.makeText(context, R.string.mark_too_short, Toast.LENGTH_SHORT).show()
                    else Toast.makeText(context, R.string.mark_saved, Toast.LENGTH_SHORT).show()
                },
                onCancelMark = vm.binge::cancelMark,
            )
        }

        // Online subtitle notice (9.6): what was fetched, with a Change button.
        if (pill == null) onlineNotice?.let { n ->
            LaunchedEffect(n) {
                kotlinx.coroutines.delay(8_000)
                vm.dismissOnlineNotice()
            }
            Row(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 96.dp)
                    .glass(TroyShapes.Pill).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.online_subs_loaded, listOfNotNull(n.language?.uppercase(), n.source.sourceName).joinToString(" · ")),
                    style = Troy.type.body, color = c.text, maxLines = 1,
                )
                TroyButton(stringResource(R.string.online_subs_change), { vm.dismissOnlineNotice(); panel = PlayerPanel.ONLINE_SUBTITLES }, kind = ButtonKind.GHOST)
            }
        }

        // Casting: the phone is the remote (20.7).
        castState?.let { cs ->
            Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.Center) {
                com.troy.orcaplayer.cast.CastRemote(
                    state = cs,
                    problem = castProblem,
                    hasPrevious = ui.count > 1,
                    hasNext = ui.hasNext,
                    cast = vm.cast,
                    onPrevious = vm::castPrevious,
                    onNext = vm::castNext,
                )
            }
        }

        // Error
        ui.error?.let { code ->
            GlassPanel(Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = c.warn, modifier = Modifier.size(36.dp))
                    Text(stringResource(R.string.player_error_title), style = Troy.type.title, color = c.text)
                    Text(stringResource(R.string.player_error_message), style = Troy.type.bodySmall, color = c.text2)
                    Text(code, style = Troy.type.mono, color = c.text3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyButton(stringResource(R.string.player_retry), vm::retry, kind = ButtonKind.PRIMARY)
                        val other = if (decoder == DecoderMode.SW) DecoderMode.HW_PLUS else DecoderMode.SW
                        TroyButton(
                            stringResource(if (other == DecoderMode.SW) R.string.player_try_software else R.string.player_try_hardware),
                            { vm.setDecoder(other) },
                        )
                    }
                    TroyButton(stringResource(R.string.vlc_open), { vm.openInVlc() }, kind = ButtonKind.GHOST)
                }
            }
        }

        // Panels
        if (panel != PlayerPanel.NONE) {
            Box(
                Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.35f))
                    .pointerInput(Unit) { detectTapGestures { panel = PlayerPanel.NONE } },
            )
            val edge = when {
                !wide -> SheetEdge.BOTTOM
                panel == PlayerPanel.TOOLS && settings[ToolSettings.PanelSide] == "start" -> SheetEdge.START
                else -> SheetEdge.END
            }
            val panelModifier = if (wide) {
                Modifier.align(if (edge == SheetEdge.START) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().widthIn(
                    max = when (panel) {
                        PlayerPanel.TRACKS, PlayerPanel.SUBTITLES, PlayerPanel.ONLINE_SUBTITLES -> 640.dp
                        PlayerPanel.AUDIO -> 460.dp
                        else -> 380.dp
                    },
                )
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)
            }
            GlassPanel(panelModifier.sheetDrag(edge) { panel = PlayerPanel.NONE }.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).pointerInput(Unit) { detectTapGestures { } }) {
                when (panel) {
                    PlayerPanel.SPEED -> SpeedPanel(ui.speed, vm::setSpeed)
                    PlayerPanel.TRACKS, PlayerPanel.SUBTITLES -> TracksPanel(
                        tracks = tracks,
                        external = external,
                        subtitlesOff = subsOff,
                        delayMs = delayMs,
                        subtitleSpeed = subSpeed,
                        wide = wide,
                        onSelect = vm::selectTrack,
                        onAutoQuality = vm::autoVideoQuality,
                        onSubtitlesOff = vm::subtitlesOff,
                        onPickFile = host::pickSubtitle,
                        onSearchOnline = { panel = PlayerPanel.ONLINE_SUBTITLES },
                        onDelay = vm::setSubtitleDelay,
                        onSubtitleSpeed = vm::setSubtitleSpeed,
                        onEncoding = vm::reloadWithEncoding,
                    )
                    PlayerPanel.TOOLS -> ToolsPanel(
                        ToolsPanelState(
                            tools = toolsLayout.panel,
                            decoder = decoder,
                            orientationLock = host.orientationLock,
                            showOrientation = host.isPhone,
                            backgroundPlay = settings[PlayerSettings.BackgroundPlay],
                            canPip = host.canPip,
                            boostPercent = if (extraVolume) fx.boostPercent else null,
                            quietRecording = settings[AV.QuietRecording],
                            infoOverlay = settings[AV.InfoOverlay],
                            abStart = abStart,
                            abLoop = abLoop,
                            sleepRemainingMs = if (sleep?.endAt != null || sleep?.atEndOfVideo == true) sleepLeft else null,
                            sleepAtEnd = sleep?.atEndOfVideo == true,
                            night = night,
                            hasVideoPrefs = hasVideoPrefs,
                            canSaveFolder = vm.extras.canSaveFolder,
                            rememberPerVideo = settings[ToolSettings.RememberPerVideo],
                            canSave = vm.saveDecision().let { it == SaveDecision.ALLOWED || it == SaveDecision.NEEDS_REMINDER },
                            canMark = vm.binge.canMark,
                            hasMarks = vm.binge.hasMarks,
                            audioOnly = audioOnly,
                            canFloat = host.canFloat,
                        ),
                        object : ToolsPanelActions {
                            override fun openTool(tool: ToolId) = launchTool(tool)
                            override fun startMark(kind: com.troy.orcaplayer.core.model.SkipKind) {
                                vm.binge.startMark(kind, vm.currentPosition())
                                panel = PlayerPanel.NONE
                                Toast.makeText(context, R.string.mark_started, Toast.LENGTH_SHORT).show()
                            }
                            override fun clearMarks() {
                                vm.binge.clearMarks()
                                Toast.makeText(context, R.string.skip_marks_cleared, Toast.LENGTH_SHORT).show()
                            }
                            override fun screenshot() { panel = PlayerPanel.NONE; takeScreenshot() }
                            override fun equalizer() { panel = PlayerPanel.AUDIO }
                            override fun markA() = vm.markA()
                            override fun markB() {
                                if (!vm.markB()) Toast.makeText(context, R.string.ab_b_before_a, Toast.LENGTH_SHORT).show()
                            }
                            override fun clearAb() = vm.extras.clearAb()
                            override fun sleep(minutes: Int) { vm.startSleep(minutes) }
                            override fun sleepAtEnd() { vm.sleepAtEnd() }
                            override fun cancelSleep() { vm.cancelSleep() }
                            override fun night(on: Boolean) = vm.extras.setNight(on)
                            override fun resetBoost() = vm.sound.setBoost(100)
                            override fun quietRecording(on: Boolean) = vm.setSetting(AV.QuietRecording, on)
                            override fun saveForFolder() {
                                vm.extras.saveForFolder()
                                Toast.makeText(context, R.string.per_video_folder_done, Toast.LENGTH_SHORT).show()
                            }
                            override fun resetVideo() = vm.extras.resetVideo()
                            override fun decoder(mode: DecoderMode) = vm.setDecoder(mode)
                            override fun orientation(lock: String) = host.lockOrientation(lock)
                            override fun backgroundPlay(on: Boolean) = vm.setBackgroundPlay(on)
                            override fun infoOverlay(on: Boolean) = vm.setSetting(AV.InfoOverlay, on)
                            override fun pip() { panel = PlayerPanel.NONE; host.enterPip() }
                            override fun audioOnly(on: Boolean) { vm.setAudioOnly(on); if (on) panel = PlayerPanel.NONE }
                            override fun filters() { panel = PlayerPanel.FILTERS }
                            override fun audioDelay() { panel = PlayerPanel.AUDIO_DELAY }
                            override fun floatingWindow() {
                                panel = PlayerPanel.NONE
                                if (com.troy.orcaplayer.ui.mini.FloatingPlayerService.canDraw(context)) host.floatingWindow() else askOverlay = true
                            }
                        },
                    )
                    PlayerPanel.AUDIO -> EqualizerPanel(
                        fx = fx,
                        custom = audioStore.custom,
                        ctx = EqContext(
                            advanced = settings[AV.EqMode] == "advanced",
                            extraVolume = extraVolume,
                            maxBoost = maxBoost,
                            passthroughActive = stats.audioOutput == AudioOutputMode.PASSTHROUGH,
                            showMeter = settings[AV.LevelMeter],
                            virtualizerBlocked = vm.sound.virtualizerBlocked,
                            origin = fxOrigin,
                            canSaveFolder = vm.sound.canSaveFolder,
                        ),
                        level = { vm.engine.outputLevel },
                        sound = vm.sound,
                        onAdvanced = { vm.setSetting(AV.EqMode, if (it) "advanced" else "simple") },
                    )
                    PlayerPanel.QUEUE -> QueuePanel(
                        titles = vm.engine.queue.map { it.title },
                        current = ui.index,
                        repeatMode = ui.repeatMode,
                        shuffle = ui.shuffle,
                        onPlay = vm::playAt,
                        onRepeat = vm::cycleRepeat,
                        onShuffle = vm::toggleShuffle,
                    )
                    PlayerPanel.ONLINE_SUBTITLES -> OnlineSubtitlesPanel(
                        state = onlineResults,
                        preferred = settings[PlayerSettings.SubtitleLanguage].let { if (it == "off" || it == "auto") java.util.Locale.getDefault().language else it },
                        onSearch = vm::searchOnlineSubtitles,
                        onPick = { vm.pickOnline(it); panel = PlayerPanel.NONE },
                    )
                    PlayerPanel.FILTERS -> FiltersPanel(
                        filter = filter,
                        store = filterStore,
                        perVideo = filterPerVideo,
                        active = filtersActive,
                        casting = casting,
                        a = object : FilterActions {
                            override fun set(f: com.troy.orcaplayer.core.model.VideoFilter) = vm.picture.set(f)
                            override fun reset() = vm.picture.reset()
                            override fun perVideo(on: Boolean) = vm.picture.setPerVideo(on)
                            override fun skipHdr(on: Boolean) { vm.picture.setSkipHdr(on) }
                            override fun applyPreset(p: com.troy.orcaplayer.core.model.FilterPreset) = vm.picture.applyPreset(p)
                            override fun savePreset(name: String) { vm.picture.savePreset(name) }
                            override fun deletePreset(name: String) { vm.picture.deletePreset(name) }
                        },
                    )
                    PlayerPanel.AUDIO_DELAY -> AudioDelayPanel(audioDevice, audioDelay, casting, vm.picture::setAudioDelay)
                    PlayerPanel.NONE -> Unit
                }
            }
        }

        // The one-time saving reminder (3.7), before the first save from a link or an add-on.
        reminderFor?.let { tool ->
            Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { reminderFor = null } })
            GlassPanel(Modifier.align(Alignment.Center).widthIn(max = 460.dp).padding(24.dp).pointerInput(Unit) { detectTapGestures { } }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.save_reminder_title), style = Troy.type.title, color = c.text)
                    Text(stringResource(R.string.save_reminder_body), style = Troy.type.body, color = c.text2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyButton(stringResource(R.string.save_reminder_accept), {
                            vm.acceptSaveReminder()
                            reminderFor = null
                            scope.launch {
                                // Wait for the setting to land, then open the tool.
                                kotlinx.coroutines.delay(300)
                                launchTool(tool)
                            }
                        }, kind = ButtonKind.PRIMARY)
                        TroyButton(stringResource(R.string.save_reminder_cancel), { reminderFor = null }, kind = ButtonKind.GHOST)
                    }
                }
            }
        }

        // Screen lock: everything is blocked except a small unlock button that shows on touch.
        if (locked) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { unlockShownAt = System.currentTimeMillis() }
                },
            )
            if (now - unlockShownAt < 2500) {
                val source = remember { MutableInteractionSource() }
                Row(
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 32.dp)
                        .glass(TroyShapes.Pill)
                        .then(
                            if (kids) {
                                // Children can't unlock with a tap: it takes a long press.
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { unlockShownAt = System.currentTimeMillis() },
                                        onLongPress = { vm.locked.value = false; controlsVisible = true; touch() },
                                    )
                                }
                            } else {
                                Modifier.clickable(interactionSource = source, indication = null) { vm.locked.value = false; controlsVisible = true; touch() }
                            },
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, tint = c.text)
                    Text(stringResource(if (kids) R.string.player_unlock_hold else R.string.player_unlock), style = Troy.type.button, color = c.text)
                }
            }
        }

        // The Kids daily limit is used up (18).
        if (limitReached) {
            WatchLimitScreen(onClose = host::close, onParent = { parentGate = true })
        }
        if (parentGate) {
            val profilesVm: com.troy.orcaplayer.ui.profiles.ProfilesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            com.troy.orcaplayer.ui.profiles.KidsExitGate(
                profilesVm,
                onDismiss = { parentGate = false },
                onUnlocked = { parentGate = false; vm.binge.overrideLimit(); vm.togglePlay() },
            )
        }
        // The floating window needs "display over other apps": say why before asking (20.5).
        if (askOverlay) {
            com.troy.orcaplayer.ui.common.ConfirmDialog(
                title = stringResource(R.string.floating_permission_title),
                message = stringResource(R.string.floating_permission_message),
                confirm = stringResource(R.string.floating_permission_open),
                onConfirm = {
                    askOverlay = false
                    runCatching { context.startActivity(com.troy.orcaplayer.ui.mini.FloatingPlayerService.permissionIntent(context)) }
                },
                onDismiss = { askOverlay = false },
            )
        }
    }
}

/** Values the gesture handler reads at the moment of each gesture. */
private data class GestureDeps(
    val ui: PlaybackUi,
    val sliders: SliderPrefs,
    val seekMsPerDp: Float,
    val longPressSpeed: Float,
    val skipSeconds: Int,
    val controlsVisible: Boolean,
    val aspect: AspectMode,
)

@Composable
private fun ControlsOverlay(
    vm: PlayerViewModel,
    host: PlayerHost,
    ui: PlaybackUi,
    layout: List<com.troy.orcaplayer.core.model.PlayerControlConfig>,
    skipSeconds: Int,
    remaining: Boolean,
    onToggleRemaining: () -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    onAspect: () -> Unit,
    onLock: () -> Unit,
    onPreview: (Long?) -> Unit,
    onTouch: () -> Unit,
) {
    val c = Troy.colors
    val mirror = Troy.state.mirrorTimeline
    val settingsNow by vm.settings.collectAsStateWithLifecycle()
    val inSlot = { slot: ControlSlot -> layout.filter { it.slot == slot } }

    @Composable
    fun Control(cfg: com.troy.orcaplayer.core.model.PlayerControlConfig) {
        if (cfg.control == PlayerControl.CAST) {
            if (vm.cast.available && settingsNow[com.troy.orcaplayer.core.settings.ExtrasSettings.CastEnabled]) {
                com.troy.orcaplayer.cast.CastButton()
            }
            return
        }
        val spec = controlSpec(cfg.control, ui.playWhenReady && !ui.ended, skipSeconds) ?: return
        val enabled = when (cfg.control) {
            PlayerControl.NEXT -> ui.hasNext
            PlayerControl.PREVIOUS -> ui.count > 0
            PlayerControl.PIP -> host.canPip
            PlayerControl.ROTATE -> host.isPhone
            else -> true
        }
        if (!enabled && (cfg.control == PlayerControl.PIP || cfg.control == PlayerControl.ROTATE)) return
        val action: () -> Unit = {
            onTouch()
            when (cfg.control) {
                PlayerControl.BACK -> host.close()
                PlayerControl.TRACKS -> onPanel(PlayerPanel.TRACKS)
                PlayerControl.SUBTITLES -> onPanel(PlayerPanel.SUBTITLES)
                PlayerControl.TOOLS -> onPanel(PlayerPanel.TOOLS)
                PlayerControl.QUEUE -> onPanel(PlayerPanel.QUEUE)
                PlayerControl.SPEED -> onPanel(PlayerPanel.SPEED)
                PlayerControl.ASPECT -> onAspect()
                PlayerControl.LOCK -> onLock()
                PlayerControl.ROTATE -> host.rotate()
                PlayerControl.PIP -> host.enterPip()
                PlayerControl.PLAY_PAUSE -> vm.togglePlay()
                PlayerControl.PREVIOUS -> vm.previous()
                PlayerControl.NEXT -> if (ui.hasNext) vm.next()
                PlayerControl.REWIND -> vm.seekBy(-skipSeconds * 1000L)
                PlayerControl.FORWARD -> vm.seekBy(skipSeconds * 1000L)
                else -> Unit
            }
        }
        // Long press on the subtitle button opens quick delay and style options, MX Player style.
        val long: (() -> Unit)? = if (cfg.control == PlayerControl.SUBTITLES) ({ onPanel(PlayerPanel.SUBTITLES) }) else null
        OverlayButton(spec.icon, spec.label, action, cfg.size, long)
    }

    Box(Modifier.fillMaxSize()) {
        // Scrims keep controls readable over bright video.
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.3f).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(c.scrim, Color.Transparent))))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.35f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, c.scrim))))

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp)) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                inSlot(ControlSlot.TOP_START).forEach { cfg ->
                    if (cfg.control == PlayerControl.TITLE) {
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                ui.title,
                                style = Troy.type.title.copy(textDirection = TextDirection.Content),
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (ui.count > 1) {
                                Text(formatNumber(ui.index + 1) + " / " + formatNumber(ui.count), style = Troy.type.bodySmall, color = c.text2)
                            }
                        }
                    } else {
                        Control(cfg)
                    }
                }
                if (inSlot(ControlSlot.TOP_START).none { it.control == PlayerControl.TITLE }) Spacer(Modifier.weight(1f))
                if (ui.insecure) TroyAlert(stringResource(R.string.player_not_secure), AlertKind.WARN, Modifier.padding(horizontal = 8.dp))
                if (ui.count > 1 && layout.none { it.control == PlayerControl.QUEUE }) {
                    Control(com.troy.orcaplayer.core.model.PlayerControlConfig(PlayerControl.QUEUE, ControlSlot.TOP_END))
                }
                inSlot(ControlSlot.TOP_END).forEach { Control(it) }
            }
            Spacer(Modifier.weight(1f))
            // Centre transport: never mirrored, as media controls aren't.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    inSlot(ControlSlot.CENTER).forEach { Control(it) }
                }
            }
            Spacer(Modifier.weight(1f))
            // Seek bar and bottom row
            val timelineDir = if (mirror) LocalLayoutDirection.current else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides timelineDir) {
                Column {
                    if (layout.any { it.control == PlayerControl.SEEK_BAR }) {
                        val ab by vm.extras.abLoop.collectAsStateWithLifecycle()
                        val abStart by vm.extras.abStart.collectAsStateWithLifecycle()
                        SeekBar(
                            ui.positionMs, ui.durationMs, ui.bufferedMs, onPreview, { vm.seekTo(it); onTouch() }, Modifier.padding(horizontal = 8.dp),
                            markA = ab?.aMs ?: abStart, markB = ab?.bMs,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        inSlot(ControlSlot.BOTTOM_START).forEach { cfg ->
                            if (cfg.control == PlayerControl.TIME) TimeLabel(ui.positionMs, ui.durationMs, remaining, onToggleRemaining) else Control(cfg)
                        }
                        Spacer(Modifier.weight(1f))
                        inSlot(ControlSlot.BOTTOM_CENTER).filter { it.control != PlayerControl.SEEK_BAR }.forEach { Control(it) }
                        Spacer(Modifier.weight(1f))
                        inSlot(ControlSlot.BOTTOM_END).forEach { Control(it) }
                    }
                }
            }
        }
    }
}
