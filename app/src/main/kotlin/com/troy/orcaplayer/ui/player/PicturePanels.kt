package com.troy.orcaplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.core.model.FilterPreset
import com.troy.orcaplayer.core.model.FilterStore
import com.troy.orcaplayer.core.model.VideoFilter
import com.troy.orcaplayer.player.engine.AudioDelayController
import com.troy.orcaplayer.player.engine.AudioDevice
import kotlin.math.roundToInt

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, step: Float, onChange: (Float) -> Unit) {
    val c = Troy.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
            Text(text, style = Troy.type.label, color = c.text2)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.controlFill),
        )
    }
}

private fun signedPercent(v: Float): String {
    val p = (v * 100).roundToInt()
    return (if (p > 0) "+" else "") + formatNumber(p) + "%"
}

/** Audio delay for the output in use, saved per device (10.6). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioDelayPanel(device: AudioDevice, delayMs: Int, casting: Boolean, onDelay: (Int) -> Unit) {
    val c = Troy.colors
    PanelColumn(stringResource(R.string.tool_audio_delay)) {
        val name = when {
            device.name.isNotBlank() -> device.name
            device.key == "speaker" -> stringResource(R.string.audio_device_speaker)
            else -> stringResource(R.string.audio_device_unknown)
        }
        Text(stringResource(R.string.audio_delay_device, name), style = Troy.type.bodySmall, color = c.text2)
        if (casting) TroyAlert(stringResource(R.string.cast_feature_unavailable), AlertKind.INFO)
        ValueSlider(
            stringResource(R.string.audio_delay_label), delayMs.toFloat(),
            AudioDelayController.MIN_MS.toFloat()..AudioDelayController.MAX_MS.toFloat(),
            stringResource(R.string.audio_delay_ms, (if (delayMs > 0) "+" else "") + formatNumber(delayMs)), 10f,
        ) { onDelay(it.roundToInt()) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TroyChip("−50", false, { onDelay(delayMs - 50) })
            TroyChip("−10", false, { onDelay(delayMs - 10) })
            TroyChip("+10", false, { onDelay(delayMs + 10) })
            TroyChip("+50", false, { onDelay(delayMs + 50) })
            if (delayMs != 0) TroyChip(stringResource(R.string.audio_delay_reset), false, { onDelay(0) })
        }
        Text(stringResource(R.string.audio_delay_hint), style = Troy.type.bodySmall, color = c.text3)
    }
}

/** What the filters panel can do. */
interface FilterActions {
    fun set(f: VideoFilter)
    fun reset()
    fun perVideo(on: Boolean)
    fun skipHdr(on: Boolean)
    fun applyPreset(p: FilterPreset)
    fun savePreset(name: String)
    fun deletePreset(name: String)
}

@Composable
fun presetName(p: FilterPreset): String = when (p.name) {
    "@vivid" -> stringResource(R.string.filter_preset_vivid)
    "@cinema" -> stringResource(R.string.filter_preset_cinema)
    "@bright" -> stringResource(R.string.filter_preset_bright)
    "@anime" -> stringResource(R.string.filter_preset_anime)
    else -> p.name
}

/** Brightness, contrast, saturation, gamma, hue and sharpening, with presets (20.6). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FiltersPanel(filter: VideoFilter, store: FilterStore, perVideo: Boolean, active: Boolean, casting: Boolean, a: FilterActions) {
    val c = Troy.colors
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    PanelColumn(stringResource(R.string.tool_filters)) {
        if (casting) TroyAlert(stringResource(R.string.cast_feature_unavailable), AlertKind.INFO)
        if (!filter.isNeutral && !active) TroyAlert(stringResource(R.string.filters_off_hdr), AlertKind.INFO)
        SectionLabel(stringResource(R.string.filter_presets))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TroyChip(stringResource(R.string.filter_none), filter.isNeutral, a::reset)
            store.presets.forEach { p -> TroyChip(presetName(p), p.filter == filter && !filter.isNeutral, { a.applyPreset(p) }) }
        }
        ValueSlider(stringResource(R.string.filter_brightness), filter.brightness, -1f..1f, signedPercent(filter.brightness), 0.05f) { a.set(filter.copy(brightness = it)) }
        ValueSlider(stringResource(R.string.filter_contrast), filter.contrast, -1f..1f, signedPercent(filter.contrast), 0.05f) { a.set(filter.copy(contrast = it)) }
        ValueSlider(stringResource(R.string.filter_saturation), filter.saturation, -1f..1f, signedPercent(filter.saturation), 0.05f) { a.set(filter.copy(saturation = it)) }
        ValueSlider(stringResource(R.string.filter_gamma), filter.gamma, -1f..1f, signedPercent(filter.gamma), 0.05f) { a.set(filter.copy(gamma = it)) }
        ValueSlider(stringResource(R.string.filter_hue), filter.hue, -180f..180f, formatNumber(filter.hue.roundToInt()) + "°", 5f) { a.set(filter.copy(hue = it)) }
        ValueSlider(stringResource(R.string.filter_sharpness), filter.sharpness, 0f..1f, formatNumber((filter.sharpness * 100).roundToInt()) + "%", 0.05f) { a.set(filter.copy(sharpness = it)) }
        Text(stringResource(R.string.filter_sharpness_hint), style = Troy.type.bodySmall, color = c.text3)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.filter_per_video), style = Troy.type.body, color = c.text)
                Text(stringResource(R.string.filter_per_video_summary), style = Troy.type.bodySmall, color = c.text3)
            }
            TroyToggle(perVideo, a::perVideo)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.filter_skip_hdr), style = Troy.type.body, color = c.text)
                Text(stringResource(R.string.filter_skip_hdr_summary), style = Troy.type.bodySmall, color = c.text3)
            }
            TroyToggle(store.skipHdr, a::skipHdr)
        }
        if (naming) {
            TroyTextField(name, { name = it }, placeholder = stringResource(R.string.filter_preset_name))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyButton(stringResource(R.string.filter_preset_save), { a.savePreset(name); naming = false; name = "" })
                TroyButton(stringResource(R.string.dialog_cancel), { naming = false }, kind = ButtonKind.GHOST)
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!filter.isNeutral) TroyButton(stringResource(R.string.filter_preset_new), { naming = true }, kind = ButtonKind.SECONDARY)
                store.presets.firstOrNull { it.filter == filter && !it.name.startsWith("@") }?.let { p ->
                    TroyButton(stringResource(R.string.filter_preset_delete), { a.deletePreset(p.name) }, kind = ButtonKind.GHOST)
                }
                if (!filter.isNeutral) TroyButton(stringResource(R.string.filter_reset), a::reset, kind = ButtonKind.GHOST)
            }
        }
    }
}
