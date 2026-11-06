package com.troy.orcaplayer.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.troy.orcaplayer.AppViewModel
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.theme.OrcaTheme
import com.troy.orcaplayer.core.settings.AudioVideoSettings
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.player.engine.DisplayModeInfo
import com.troy.orcaplayer.player.engine.FrameRateMatch
import com.troy.orcaplayer.player.engine.ToneMapMethod
import com.troy.orcaplayer.player.engine.VideoCaps
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.player.engine.PlaybackService
import com.troy.orcaplayer.system.SystemSignals
import com.troy.orcaplayer.themeState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The player screen. It handles rotation, folding and size changes itself (configChanges), so playback
 * never restarts; the player lives in [PlaybackService]. Also the "Open with" target for other apps.
 */
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity(), PlayerHost {
    private val vm: PlayerViewModel by viewModels()
    private val appVm: AppViewModel by viewModels()
    private var controller: ListenableFuture<MediaController>? = null
    private val pip = MutableStateFlow(false)
    private lateinit var levels: SliderLevels
    private var sessionOrientation: String? = null

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadSubtitle(uri)
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(EXTRA_PIP_ACTION)) {
                PIP_PLAY_PAUSE -> vm.togglePlay()
                PIP_REWIND -> vm.seekBy(-skipMs())
                PIP_FORWARD -> vm.seekBy(skipMs())
            }
        }
    }

    private val lockBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The full player takes over from the mini player and the floating window.
        com.troy.orcaplayer.ui.mini.MiniPlayerState.hide()
        com.troy.orcaplayer.ui.mini.FloatingPlayerService.stop(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        appVm.updateSignals(SystemSignals.read(this))
        levels = SliderLevels(getSystemService(AudioManager::class.java), contentResolver) { value ->
            window.attributes = window.attributes.apply { screenBrightness = value }
        }
        levels.onBoost = { vm.sound.setBoost(it) }
        onBackPressedDispatcher.addCallback(this, lockBack)
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Binding a controller starts the service, which shows the media notification.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()

        if (redirectAddonLink(intent)) return
        vm.start(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    vm.events.collect { e ->
                        when (e) {
                            PlayerEvent.Finish -> finish()
                            is PlayerEvent.OpenInVlc -> {
                                com.troy.orcaplayer.ui.vlc.VlcPlayerActivity.start(this@PlayerActivity, e.item, e.positionMs)
                                finish()
                            }
                            is PlayerEvent.Message -> Toast.makeText(
                                this@PlayerActivity,
                                when (e.text) {
                                    PlayerViewModel.MSG_NO_CUES -> R.string.player_no_cues
                                    PlayerViewModel.MSG_DECODED_EFFECTS -> R.string.player_decoded_effects
                                    PlayerViewModel.MSG_DECODED_SPEED -> R.string.player_decoded_speed
                                    PlayerViewModel.MSG_DV5 -> R.string.player_dv5_warning
                                    PlayerViewModel.MSG_FALLBACK -> R.string.player_fallback
                                    else -> R.string.player_load_failed
                                },
                                if (e.text == PlayerViewModel.MSG_DV5) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                launch {
                    // Video quality: tone mapping and frame-rate matching follow Settings and the video.
                    combine(vm.settings, vm.stats.map { it.video?.frameRate ?: -1f }.distinctUntilChanged()) { s, fps -> s to fps }
                        .collect { (s, fps) -> applyVideoSettings(s, fps) }
                }
                launch {
                    combine(vm.ui, vm.settings, vm.locked) { ui, s, locked -> Triple(ui, s, locked) }.collect { (ui, s, locked) ->
                        lockBack.isEnabled = locked && s[PlayerSettings.LockBlocksKeys]
                        if (ui.playing && !vm.audioOnly.value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        applyOrientation(ui, s)
                        updatePip(ui, s)
                    }
                }
            }
        }

        setContent {
            val values by appVm.settings.collectAsStateWithLifecycle()
            val signals by appVm.signals.collectAsStateWithLifecycle()
            val inPip by pip.collectAsStateWithLifecycle()
            val isArabic = LocalConfiguration.current.locales[0]?.language == "ar"
            OrcaTheme(themeState(values ?: SettingsValues.Defaults, signals, isArabic)) {
                PlayerScreen(vm, this, levels, inPip)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (redirectAddonLink(intent)) return
        setIntent(intent)
        vm.start(intent)
    }

    /** A Stremio manifest link shared or opened here goes to the Addon Manager's install preview (15.1). */
    private fun redirectAddonLink(intent: Intent): Boolean {
        // A link shared as text is offered as Play or Download in the app (16.1).
        if (intent.action == Intent.ACTION_SEND && !intent.hasExtra(Intent.EXTRA_STREAM)) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { com.troy.orcaplayer.sources.api.MediaLinks.firstLink(it) }
            if (shared != null && com.troy.orcaplayer.sources.api.MediaLinks.kindOf(shared) != com.troy.orcaplayer.sources.api.MediaLinks.Kind.STREMIO_MANIFEST &&
                (shared.startsWith("http://", true) || shared.startsWith("https://", true))
            ) {
                startActivity(
                    Intent(this, com.troy.orcaplayer.MainActivity::class.java)
                        .putExtra(com.troy.orcaplayer.MainActivity.EXTRA_SHARED_LINK, shared)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                finish()
                return true
            }
        }
        val link = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { com.troy.orcaplayer.sources.api.MediaLinks.firstLink(it) ?: it.trim() }
        if (link == null || com.troy.orcaplayer.sources.api.MediaLinks.kindOf(link) != com.troy.orcaplayer.sources.api.MediaLinks.Kind.STREMIO_MANIFEST) return false
        startActivity(
            Intent(this, com.troy.orcaplayer.MainActivity::class.java)
                .putExtra(com.troy.orcaplayer.MainActivity.EXTRA_INSTALL_ADDON, link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
        return true
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            levels.syncVolume()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay

    private var modeBeforeMatch: Int? = null

    private fun applyVideoSettings(s: SettingsValues, fps: Float) {
        val d = currentDisplay()
        val frameRate = s[AudioVideoSettings.FrameRateMatch]
        vm.engine.setFrameRateStrategy(seamlessOnly = frameRate != "off")
        vm.engine.setToneMapping(
            ToneMapMethod.fromKey(s[AudioVideoSettings.ToneMapping]),
            (s[AudioVideoSettings.ToneBrightness].toIntOrNull() ?: 0) / 100f,
            VideoCaps.displayIsHdr(d),
        )
        // "Always" may switch the display mode, as TVs do; respect the TV's own "match content frame rate".
        val allowed = frameRate == "always" && fps > 0f && d != null && systemAllowsModeSwitch()
        val target = if (allowed) {
            val modes = d!!.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
            val cur = d.mode.let { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
            FrameRateMatch.pick(fps, modes, cur)?.id
        } else {
            null
        }
        val attrs = window.attributes
        if (target != null && attrs.preferredDisplayModeId != target) {
            if (modeBeforeMatch == null) modeBeforeMatch = attrs.preferredDisplayModeId
            window.attributes = attrs.apply { preferredDisplayModeId = target }
        } else if (target == null && modeBeforeMatch != null && (frameRate != "always" || fps <= 0f)) {
            window.attributes = attrs.apply { preferredDisplayModeId = modeBeforeMatch ?: 0 }
            modeBeforeMatch = null
        }
    }

    private fun systemAllowsModeSwitch(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val dm = getSystemService(DisplayManager::class.java) ?: return true
        return dm.matchContentFrameRateUserPreference != DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER
    }

    private fun skipMs() = (vm.settings.value[PlayerSettings.SkipSeconds].toIntOrNull() ?: 10) * 1000L

    // Orientation (phones only: tablets, foldables and TVs follow the screen)

    override val isPhone: Boolean
        get() = resources.configuration.smallestScreenWidthDp < 600 && !appVm.signals.value.isTv

    override val orientationLock: String
        get() = sessionOrientation ?: vm.settings.value[PlayerSettings.PlayerOrientation]

    override fun lockOrientation(mode: String) {
        sessionOrientation = mode
        applyOrientation(vm.ui.value, vm.settings.value)
    }

    override fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        lockOrientation(if (landscape) "portrait" else "landscape")
    }

    private fun applyOrientation(ui: PlaybackUi, s: SettingsValues) {
        if (!isPhone) return
        val mode = sessionOrientation ?: s[PlayerSettings.PlayerOrientation]
        val wanted = when (mode) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "system" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> when {
                ui.videoWidth <= 0 -> requestedOrientation
                ui.videoWidth >= ui.videoHeight -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    // Picture-in-picture

    override val canPip: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipAllowed(ui: PlaybackUi, s: SettingsValues): Boolean = canPip && ui.count > 0 && when (s[PlayerSettings.Pip]) {
        "always" -> true
        "playing" -> ui.playWhenReady && !ui.ended
        else -> false
    }

    private fun pipParams(ui: PlaybackUi, auto: Boolean): PictureInPictureParams {
        val ratio = ui.aspect.coerceIn(1f / 2.39f, 2.39f)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(pipActions(ui.playWhenReady && !ui.ended))
        val content = window.decorView
        builder.setSourceRectHint(Rect(0, 0, content.width, content.height))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(auto)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun pipActions(playing: Boolean): List<RemoteAction> {
        fun action(id: String, icon: Int, title: Int, code: Int): RemoteAction {
            val intent = Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, id)
            val pi = PendingIntent.getBroadcast(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteAction(Icon.createWithResource(this, icon), getString(title), getString(title), pi)
        }
        return listOf(
            action(PIP_REWIND, R.drawable.ic_pip_rewind, R.string.player_rewind_short, 1),
            if (playing) action(PIP_PLAY_PAUSE, R.drawable.ic_pip_pause, R.string.player_pause, 2)
            else action(PIP_PLAY_PAUSE, R.drawable.ic_pip_play, R.string.player_play, 2),
            action(PIP_FORWARD, R.drawable.ic_pip_forward, R.string.player_forward_short, 3),
        )
    }

    private fun updatePip(ui: PlaybackUi, s: SettingsValues) {
        if (!canPip) return
        runCatching { setPictureInPictureParams(pipParams(ui, pipAllowed(ui, s))) }
    }

    override fun enterPip() {
        if (!canPip) return
        runCatching { enterPictureInPictureMode(pipParams(vm.ui.value, pipAllowed(vm.ui.value, vm.settings.value))) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters automatically (setAutoEnterEnabled).
        if (Build.VERSION.SDK_INT < 31 && pipAllowed(vm.ui.value, vm.settings.value)) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.value = isInPictureInPictureMode
        // Closing the PiP window stops the activity without resuming it.
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED && !backgroundPlay()) vm.pause()
    }

    // Lifecycle

    /** Audio-only mode, the mini player and the floating window keep playing after the screen goes. */
    private fun backgroundPlay() = vm.settings.value[PlayerSettings.BackgroundPlay] || vm.audioOnly.value || minimized || floating

    /** Set when leaving shrinks the video into the in-app mini player (20.5). */
    private var minimized = false
    private var floating = false

    private fun miniAllowed(): Boolean {
        val v = vm.settings.value
        return vm.options.inApp && v[com.troy.orcaplayer.core.settings.ExtrasSettings.MiniPlayer] &&
            vm.engine.queue.isNotEmpty() && vm.profile.value?.isKids != true && !vm.ui.value.ended && !vm.binge.limitReached.value
    }

    override val canFloat: Boolean
        get() = !appVm.signals.value.isTv && vm.settings.value[com.troy.orcaplayer.core.settings.ExtrasSettings.FloatingWindow] && vm.profile.value?.isKids != true

    override fun floatingWindow() {
        if (!com.troy.orcaplayer.ui.mini.FloatingPlayerService.canDraw(this)) return
        floating = true
        vm.saveNow()
        com.troy.orcaplayer.ui.mini.FloatingPlayerService.start(this)
        super.finish()
    }

    override fun onStop() {
        super.onStop()
        vm.saveNow()
        if (!isInPictureInPictureMode && !backgroundPlay()) vm.pause()
    }

    override fun finish() {
        if (vm.options.returnResult) setResult(RESULT_OK, vm.finalResult())
        if (!floating && miniAllowed()) {
            minimized = true
            vm.saveNow()
            com.troy.orcaplayer.ui.mini.MiniPlayerState.show()
        }
        super.finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        if (isFinishing && !backgroundPlay()) vm.stopPlayback()
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    override fun close() = finish()

    override fun pickSubtitle() {
        runCatching { pickSubtitle.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream", "*/*")) }
    }

    // Hardware volume keys move the player's own slider, without the system volume panel.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val blocked = vm.locked.value && vm.settings.value[PlayerSettings.LockBlocksKeys]
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!blocked) levels.volumeStep(+1)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!blocked) levels.volumeStep(-1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val ACTION_PIP = "com.troy.orcaplayer.action.PIP"
        const val EXTRA_PIP_ACTION = "action"
        const val PIP_PLAY_PAUSE = "play_pause"
        const val PIP_REWIND = "rewind"
        const val PIP_FORWARD = "forward"
    }
}
