package com.troy.orcaplayer.ui.player

import android.content.ContentResolver
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import kotlin.math.roundToInt

enum class SliderKind { BRIGHTNESS, VOLUME }

/** Fades for overlays: the default spring, or no animation with reduce motion. */
@Composable
fun fadeSpec(): FiniteAnimationSpec<Float> {
    val motion = Troy.motion
    return if (motion.reduceMotion) snap() else motion.springSpec()
}

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

    /** Highest volume in percent: 100, or the maximum boost while extra volume is on. */
    var boostMax by mutableIntStateOf(100)

    /** Software boost in percent (100 = none), the part of the slider above 100%. */
    var boost by mutableIntStateOf(100)
        private set

    /** Called when the slider moves the boost; the player hands it to the audio chain. */
    var onBoost: (Int) -> Unit = {}

    fun showBoost(percent: Int) {
        boost = percent.coerceIn(100, boostMax.coerceAtLeast(100))
    }

    /** Volume steps of the whole slider: the system steps, then 5% steps of boost. */
    val combinedMax: Int get() = maxVolume + ((boostMax - 100) / 5).coerceAtLeast(0)

    val combinedIndex: Int get() = if (boost > 100 && volume >= maxVolume) maxVolume + (boost - 100) / 5 else volume

    val volumePercent: Float get() = if (boost > 100) boost.toFloat() else volume * 100f / maxVolume

    fun setCombinedIndex(index: Int) {
        val i = index.coerceIn(0, combinedMax)
        if (i > maxVolume) {
            if (volume != maxVolume) setVolumeIndex(maxVolume)
            val b = 100 + (i - maxVolume) * 5
            if (b != boost) {
                boost = b
                onBoost(b)
            }
            volumeShownAt = System.currentTimeMillis()
        } else {
            if (boost != 100) {
                boost = 100
                onBoost(100)
            }
            setVolumeIndex(i)
        }
    }

    /** Sets the slider from a percentage of 0..[boostMax]. */
    fun setVolumePercent(percent: Float) {
        if (percent > 100f && boostMax > 100) setCombinedIndex(maxVolume + ((percent - 100f) / 5f).roundToInt())
        else setCombinedIndex((percent.coerceAtMost(100f) / 100f * maxVolume).roundToInt())
    }

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

    fun volumeStep(direction: Int) = setCombinedIndex(combinedIndex + direction)

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
    maxPercent: Float = 100f,
    boostLabel: String? = null,
) {
    val c = Troy.colors
    val t = Troy.type
    val motion = Troy.motion
    // Damping 1.0: the bar eases into place with no overshoot.
    val shown by animateFloatAsState(percent, motion.spec(TroySprings.Move), label = "level")
    AnimatedVisibility(visible, modifier, enter = fadeIn(fadeSpec()), exit = fadeOut(fadeSpec())) {
        Column(
            Modifier.glass(TroyShapes.Panel).padding(vertical = 12.dp, horizontal = 8.dp).semantics { contentDescription = label },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val boostTint = boostColor()
            if (percent > 100f && boostLabel != null) Text(boostLabel, style = t.label, color = boostTint)
            Text(formatNumber(percent.toInt()) + "%", style = t.label, color = if (percent > 100f) boostTint else c.text)
            val barHeight = 160.dp
            Box(
                Modifier
                    .width(8.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.controlFill)
                    .pointerInput(kind) {
                        detectTapGestures { pos -> onSet((1f - pos.y / size.height) * maxPercent) }
                    }
                    .pointerInput(kind) {
                        detectVerticalDragGestures { change, _ ->
                            onSet((1f - change.position.y / size.height) * maxPercent)
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Up to 100% in the accent colour; the boost above it in the boost colour.
                val top = maxPercent.coerceAtLeast(100f)
                val base = shown.coerceIn(0f, 100f) / top
                val over = (shown.coerceIn(100f, top) - 100f) / top
                if (over > 0f) Box(Modifier.fillMaxWidth().fillMaxHeight(base + over).clip(RoundedCornerShape(4.dp)).background(boostTint))
                if (base > 0f) Box(Modifier.fillMaxWidth().fillMaxHeight(base).clip(RoundedCornerShape(4.dp)).background(c.accent))
                if (top > 100f) {
                    // A mark at 100%.
                    Box(Modifier.fillMaxWidth().fillMaxHeight(100f / top), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(c.text))
                    }
                }
            }
            val icon = when (kind) {
                SliderKind.BRIGHTNESS -> if (percent < 30f) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessMedium
                SliderKind.VOLUME -> if (percent <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
            }
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
        }
    }
}
