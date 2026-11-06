package com.troy.orcaplayer.ui.player

import android.content.ContentResolver
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.motion.TroySprings
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.player.engine.SliderConfig

enum class SliderKind { BRIGHTNESS, VOLUME }

/**
 * Brightness and volume levels for the player. Brightness is the window's own brightness in percent
 * (-50 to 100; below 0 adds a dim layer). Volume follows the system media volume steps.
 */
class SliderLevels(
    private val audio: AudioManager,
    resolver: ContentResolver,
    private val applyWindowBrightness: (Float) -> Unit,
) {
    val maxVolume: Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    var volume by mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        private set

    var brightness by mutableFloatStateOf(
        runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) * 100f / 255f }.getOrDefault(50f),
    )
        private set

    /** Last time each slider changed; the bars stay visible for a moment after. */
    var volumeShownAt by mutableLongStateOf(0L)
    var brightnessShownAt by mutableLongStateOf(0L)

    /** True while a finger is on a slider. */
    var activeKind: SliderKind? by androidx.compose.runtime.mutableStateOf(null)

    val volumePercent: Float get() = volume * 100f / maxVolume

    /** The extra dim layer for brightness below 0%. */
    val dimAlpha: Float get() = if (brightness < 0f) (-brightness / 50f * 0.7f).coerceIn(0f, 0.7f) else 0f

    fun syncVolume() {
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun setVolumeIndex(index: Int) {
        val v = index.coerceIn(0, maxVolume)
        volume = v
        volumeShownAt = System.currentTimeMillis()
        // Flag 0: no system volume panel; the player shows its own slider.
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    }

    fun volumeStep(direction: Int) = setVolumeIndex(volume + direction)

    fun setBrightnessPercent(percent: Float) {
        brightness = percent.coerceIn(MIN_BRIGHTNESS, 100f)
        brightnessShownAt = System.currentTimeMillis()
        applyWindowBrightness((brightness / 100f).coerceIn(0.01f, 1f))
    }

    companion object {
        const val MIN_BRIGHTNESS = -50f
    }
}

/** Slider settings from the Settings screen. */
data class SliderPrefs(
    val display: String = "touch",
    val swap: Boolean = false,
    val config: SliderConfig = SliderConfig(),
    val brightnessStep: Float = 1f,
    val haptics: Boolean = true,
)

/** The MX Player-style vertical glass bar with an icon and a percentage. Drag or tap it to set a level. */
@Composable
fun LevelBar(
    kind: SliderKind,
    percent: Float,
    visible: Boolean,
    label: String,
    onSet: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Troy.colors
    val t = Troy.type
    val motion = Troy.motion
    // Damping 1.0: the bar eases into place with no overshoot.
    val shown by animateFloatAsState(percent, motion.spec(TroySprings.Move), label = "level")
    AnimatedVisibility(visible, modifier, enter = fadeIn(motion.spec<Float>()), exit = fadeOut(motion.spec<Float>())) {
        Column(
            Modifier.glass(TroyShapes.Panel).padding(vertical = 12.dp, horizontal = 8.dp).semantics { contentDescription = label },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(formatNumber(percent.toInt()) + "%", style = t.label, color = c.text)
            val barHeight = 160.dp
            Box(
                Modifier
                    .width(8.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.controlFill)
                    .pointerInput(kind) {
                        detectTapGestures { pos -> onSet((1f - pos.y / size.height) * 100f) }
                    }
                    .pointerInput(kind) {
                        detectVerticalDragGestures { change, _ ->
                            onSet((1f - change.position.y / size.height) * 100f)
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((shown.coerceIn(0f, 100f) / 100f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(c.accent),
                )
            }
            val icon = when (kind) {
                SliderKind.BRIGHTNESS -> if (percent < 30f) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessMedium
                SliderKind.VOLUME -> if (percent <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
            }
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
        }
    }
}
