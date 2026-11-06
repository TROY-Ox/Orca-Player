package com.troy.orcaplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.core.model.AudioFx
import com.troy.orcaplayer.core.model.EqPreset
import com.troy.orcaplayer.player.audio.AudioChain
import com.troy.orcaplayer.player.audio.Presets
import kotlin.math.roundToInt
import com.troy.orcaplayer.core.settings.R as SR

/** Translated name of a preset: built-in ones by ID, the user's own by the name they gave. */
@Composable
fun presetName(p: EqPreset): String = if (!p.builtIn) p.name else stringResource(
    when (p.id) {
        Presets.BASS_BOOST -> R.string.preset_bass_boost
        Presets.TREBLE_BOOST -> R.string.preset_treble_boost
        Presets.VOCAL -> R.string.preset_vocal
        Presets.MOVIE -> R.string.preset_movie
        Presets.LATE_NIGHT -> R.string.preset_late_night
        else -> R.string.preset_flat
    },
)

@Composable
private fun freqLabel(hz: Double): String = if (hz >= 1000) formatNumber((hz / 1000).roundToInt()) + "k" else formatNumber(hz.roundToInt())

@Composable
private fun db(v: Float): String = (if (v > 0f) "+" else "") + formatNumber((v * 10).roundToInt() / 10f) + " dB"

/** State the equalizer needs besides the sound controller. */
data class EqContext(
    val advanced: Boolean,
    val extraVolume: Boolean,
    val maxBoost: Int,
    val passthroughActive: Boolean,
    val showMeter: Boolean,
    val virtualizerBlocked: Boolean,
    val origin: FxOrigin,
    val canSaveFolder: Boolean,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerPanel(
    fx: AudioFx,
    custom: List<EqPreset>,
    ctx: EqContext,
    level: () -> Float,
    sound: SoundController,
    onAdvanced: (Boolean) -> Unit,
) {
    val c = Troy.colors
    val t = Troy.type
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.eq_title), style = t.title, color = c.text, modifier = Modifier.weight(1f))
            TroyToggle(fx.enabled, { on -> sound.update { it.copy(enabled = on) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TroyChip(stringResource(SR.string.eq_mode_simple), !ctx.advanced, { onAdvanced(false) })
            TroyChip(stringResource(SR.string.eq_mode_advanced), ctx.advanced, { onAdvanced(true) })
        }
        if (ctx.passthroughActive) TroyAlert(stringResource(R.string.eq_passthrough), AlertKind.INFO)
        when (ctx.origin) {
            FxOrigin.VIDEO -> TroyAlert(stringResource(R.string.eq_origin_video), AlertKind.INFO)
            FxOrigin.FOLDER -> TroyAlert(stringResource(R.string.eq_origin_folder), AlertKind.INFO)
            FxOrigin.GLOBAL -> Unit
        }
        if (ctx.showMeter) LevelMeter(level)

        SectionLabel(stringResource(R.string.eq_presets))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (Presets.builtIn + custom).forEach { p ->
                TroyChip(presetName(p), fx.preset == p.id, { sound.applyPreset(p) })
            }
            if (fx.preset == Presets.CUSTOM) TroyChip(stringResource(R.string.eq_custom), true, {})
        }

        // Extra volume: the same control as the boost on the volume slider.
        SectionLabel(stringResource(R.string.eq_boost))
        if (ctx.extraVolume) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = fx.boostPercent.toFloat().coerceIn(100f, ctx.maxBoost.toFloat()),
                    onValueChange = { sound.setBoost(((it / 5f).roundToInt() * 5)) },
                    valueRange = 100f..ctx.maxBoost.toFloat(),
                    colors = boostSliderColors(),
                    modifier = Modifier.weight(1f),
                )
                Text(formatNumber(fx.boostPercent) + "%", style = t.label, color = c.text, modifier = Modifier.width(48.dp))
            }
            if (fx.boostPercent != 100) TroyButton(stringResource(R.string.eq_reset_boost), { sound.setBoost(100) }, kind = ButtonKind.GHOST)
        } else {
            Text(stringResource(R.string.eq_boost_off_hint), style = t.bodySmall, color = c.text2)
        }

        if (ctx.advanced) {
            SectionLabel(stringResource(R.string.eq_bands))
            // Frequencies run low to high from left to right in every language, like a chart axis.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AudioChain.BAND_FREQS.forEachIndexed { i, hz ->
                        BandSlider(fx.bands.getOrElse(i) { 0f }, freqLabel(hz), { sound.setBand(i, it) }, Modifier.weight(1f))
                    }
                }
            }
            EffectSlider(stringResource(R.string.eq_preamp), fx.preampDb, -12f..12f, db(fx.preampDb)) { v -> sound.update { it.copy(preampDb = v, preset = Presets.CUSTOM) } }
            EffectSlider(stringResource(R.string.eq_bass), fx.bassDb, 0f..12f, db(fx.bassDb)) { v -> sound.update { it.copy(bassDb = v, preset = Presets.CUSTOM) } }
            EffectSlider(
                stringResource(R.string.eq_virtualizer), fx.virtualizer, 0f..1f, formatNumber((fx.virtualizer * 100).roundToInt()) + "%",
                enabled = !ctx.virtualizerBlocked,
            ) { v -> sound.update { it.copy(virtualizer = v, preset = Presets.CUSTOM) } }
            if (ctx.virtualizerBlocked) Text(stringResource(R.string.eq_virtualizer_blocked), style = t.bodySmall, color = c.text3)
            EffectSlider(stringResource(R.string.eq_loudness), fx.loudness, 0f..1f, formatNumber((fx.loudness * 100).roundToInt()) + "%") { v ->
                sound.update { it.copy(loudness = v, preset = Presets.CUSTOM) }
            }
            EffectSlider(stringResource(R.string.eq_compressor), fx.compressor, 0f..1f, formatNumber((fx.compressor * 100).roundToInt()) + "%") { v ->
                sound.update { it.copy(compressor = v, preset = Presets.CUSTOM) }
            }
        }

        // Presets and saving
        if (naming) {
            TroyTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.eq_preset_name), onImeAction = {
                if (name.isNotBlank()) { sound.savePreset(name); naming = false; name = "" }
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyButton(stringResource(R.string.eq_save), { if (name.isNotBlank()) { sound.savePreset(name); naming = false; name = "" } }, kind = ButtonKind.PRIMARY, enabled = name.isNotBlank())
                TroyButton(stringResource(R.string.eq_cancel), { naming = false }, kind = ButtonKind.GHOST)
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyButton(stringResource(R.string.eq_save_preset), { naming = true })
                custom.firstOrNull { it.id == fx.preset }?.let { p ->
                    TroyButton(stringResource(R.string.eq_delete_preset), { sound.deletePreset(p.id) }, kind = ButtonKind.GHOST)
                }
                TroyButton(stringResource(R.string.eq_for_video), sound::saveForVideo, kind = ButtonKind.GHOST)
                if (ctx.canSaveFolder) TroyButton(stringResource(R.string.eq_for_folder), sound::saveForFolder, kind = ButtonKind.GHOST)
                if (ctx.origin != FxOrigin.GLOBAL) TroyButton(stringResource(R.string.eq_use_global), sound::useGlobal, kind = ButtonKind.GHOST)
                TroyButton(stringResource(R.string.eq_reset), sound::reset, kind = ButtonKind.GHOST)
            }
        }
    }
}

/** Boost colour: the theme's warn colour, or danger when the accent is yellow (section 8). */
@Composable
fun boostColor(): androidx.compose.ui.graphics.Color {
    val c = Troy.colors
    val yellowAccent = kotlin.math.abs(c.accent.red - c.warn.red) < 0.12f &&
        kotlin.math.abs(c.accent.green - c.warn.green) < 0.12f && kotlin.math.abs(c.accent.blue - c.warn.blue) < 0.2f
    return if (yellowAccent) c.danger else c.warn
}

@Composable
private fun boostSliderColors() = SliderDefaults.colors(
    thumbColor = boostColor(), activeTrackColor = boostColor(), inactiveTrackColor = Troy.colors.controlFill,
)

@Composable
private fun EffectSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val c = Troy.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Troy.type.body, color = if (enabled) c.text else c.text3, modifier = Modifier.weight(1f))
            Text(valueText, style = Troy.type.label, color = c.text2)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange((it * 10).roundToInt() / 10f) },
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.controlFill),
        )
    }
}

/** One vertical EQ band, -12 to +12 dB. Drag, tap, or use the D-pad up and down keys (0.5 dB steps). */
@Composable
private fun BandSlider(value: Float, label: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = Troy.colors
    val desc = stringResource(R.string.eq_band, label)
    val state = db(value)
    fun fromY(y: Float, h: Int): Float = (((0.5f - y / h) * 24f) * 2).roundToInt() / 2f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatNumber(value.roundToInt()), style = Troy.type.label, color = c.text2)
        Box(
            Modifier
                .width(22.dp)
                .height(140.dp)
                .troyFocusRing(8.dp)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionUp -> { onChange((value + 0.5f).coerceAtMost(12f)); true }
                        Key.DirectionDown -> { onChange((value - 0.5f).coerceAtLeast(-12f)); true }
                        else -> false
                    }
                }
                .semantics {
                    contentDescription = desc
                    stateDescription = state
                    progressBarRangeInfo = ProgressBarRangeInfo(value, -12f..12f, steps = 47)
                    setProgress { v -> onChange(((v * 2).roundToInt() / 2f).coerceIn(-12f, 12f)); true }
                }
                .pointerInput(Unit) { detectTapGestures { p -> onChange(fromY(p.y, size.height).coerceIn(-12f, 12f)) } }
                .pointerInput(Unit) { detectVerticalDragGestures { ch, _ -> onChange(fromY(ch.position.y, size.height).coerceIn(-12f, 12f)) } },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(6.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.controlFill))
            // Fill from the 0 dB line toward the value.
            val frac = (kotlin.math.abs(value) / 24f).coerceIn(0f, 0.5f)
            Column(Modifier.width(6.dp).fillMaxHeight()) {
                val top = if (value > 0) 0.5f - frac else 0.5f
                if (top > 0f) Box(Modifier.weight(top))
                if (frac > 0f) Box(Modifier.weight(frac).fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(c.accent))
                val bottom = 1f - top - frac
                if (bottom > 0f) Box(Modifier.weight(bottom))
            }
            Box(Modifier.width(14.dp).height(2.dp).background(c.text3))
        }
        Text(label, style = Troy.type.label, color = c.text3)
    }
}

/** A small live output level meter in the accent colour, read from the audio chain. */
@Composable
private fun LevelMeter(level: () -> Float) {
    val c = Troy.colors
    var shown by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            val db = AudioChain.gainToDb(level()).coerceIn(-60f, 0f)
            shown = (db + 60f) / 60f
            kotlinx.coroutines.delay(50)
        }
    }
    val label = stringResource(R.string.eq_level)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.controlFill).semantics { contentDescription = label },
        ) {
            Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.accent))
        }
    }
    Spacer4()
}

@Composable
private fun Spacer4() = Box(Modifier.padding(top = 2.dp))
