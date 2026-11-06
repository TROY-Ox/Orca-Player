package com.troy.orcaplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.player.engine.DecoderMode
import java.util.Locale
import com.troy.orcaplayer.core.settings.R as SR

enum class PlayerPanel { NONE, SPEED, TRACKS, SUBTITLES, TOOLS, QUEUE, AUDIO }

enum class AspectMode(val ratio: Float?) {
    FIT(null), FILL(null), STRETCH(null), ORIGINAL(null), R16_9(16f / 9f), R4_3(4f / 3f), R21_9(21f / 9f);

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

@Composable
fun aspectLabel(mode: AspectMode): String = stringResource(
    when (mode) {
        AspectMode.FIT -> R.string.aspect_fit
        AspectMode.FILL -> R.string.aspect_fill
        AspectMode.STRETCH -> R.string.aspect_stretch
        AspectMode.ORIGINAL -> R.string.aspect_original
        AspectMode.R16_9 -> R.string.aspect_16_9
        AspectMode.R4_3 -> R.string.aspect_4_3
        AspectMode.R21_9 -> R.string.aspect_21_9
    },
)

@Composable
private fun PanelColumn(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Troy.type.title, color = Troy.colors.text)
        content()
    }
}

/** A selectable row with a check mark, used for tracks and queue items. */
@Composable
private fun PickRow(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Troy.colors
    Row(
        Modifier
            .fillMaxWidth()
            .troyFocusRing()
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text,
            style = Troy.type.body.copy(textDirection = TextDirection.Content),
            color = if (enabled) c.text else c.text3,
            modifier = Modifier.weight(1f),
        )
    }
}

private val speedPresets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedPanel(speed: Float, onSpeed: (Float) -> Unit) {
    val c = Troy.colors
    PanelColumn(stringResource(R.string.player_speed)) {
        Text(formatNumber(speed) + "×", style = Troy.type.headline, color = c.accent)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            speedPresets.forEach { s -> TroyChip(formatNumber(s) + "×", selected = kotlin.math.abs(s - speed) < 0.01f, onClick = { onSpeed(s) }) }
        }
        Slider(
            value = speed,
            onValueChange = { onSpeed((Math.round(it * 20f) / 20f)) },
            valueRange = 0.25f..4f,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.controlFill),
        )
        Text(stringResource(R.string.player_speed_pitch), style = Troy.type.bodySmall, color = c.text2)
    }
}

private fun codecName(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "AC-3"
    MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
    MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 Atmos"
    MimeTypes.AUDIO_AC4 -> "AC-4"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_RAW -> "PCM"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_VTT -> "VTT"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.APPLICATION_TTML -> "TTML"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    MimeTypes.APPLICATION_TX3G -> "TX3G"
    MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "CC"
    else -> mime?.substringAfter('/')?.uppercase(Locale.ROOT)
}

/** "Arabic · AAC 2.0" or "English · E-AC-3 5.1 Atmos". */
fun trackLabel(f: Format, fallback: String): String {
    val language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
    val name = listOfNotNull(f.label?.takeIf { it.isNotBlank() && it != language }, language).joinToString(" ")
    val channels = when (f.channelCount) {
        Format.NO_VALUE -> null
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${f.channelCount}ch"
    }
    val codec = listOfNotNull(codecName(f.sampleMimeType ?: f.codecs), channels).joinToString(" ").ifBlank { null }
    val video = if (f.height != Format.NO_VALUE && f.height > 0) "${f.height}p" else null
    return listOfNotNull(name.ifBlank { null }, video, codec).joinToString(" · ").ifBlank { fallback }
}

private data class TrackChoice(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean, val supported: Boolean)

private fun choices(tracks: Tracks, type: Int, fallback: (Int) -> String): List<TrackChoice> {
    var n = 0
    return tracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).map { i ->
            n++
            TrackChoice(g, i, trackLabel(g.getTrackFormat(i), fallback(n)), g.isTrackSelected(i), g.isTrackSupported(i))
        }
    }
}

/**
 * One panel for audio, subtitles and video quality (MX Player track menu plus the Netflix audio and
 * subtitles panel). Subtitle delay, frame-rate fix and text encoding live in the subtitle part.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracksPanel(
    tracks: Tracks,
    external: ExternalSubtitle?,
    subtitlesOff: Boolean,
    delayMs: Long,
    subtitleSpeed: Double,
    wide: Boolean,
    onSelect: (Tracks.Group, Int) -> Unit,
    onAutoQuality: () -> Unit,
    onSubtitlesOff: () -> Unit,
    onPickFile: () -> Unit,
    onDelay: (Long) -> Unit,
    onSubtitleSpeed: (Double) -> Unit,
    onEncoding: (String) -> Unit,
) {
    val c = Troy.colors
    val trackN = stringResource(R.string.player_track_n)
    val unsupported = stringResource(R.string.player_track_unsupported)
    val audio = choices(tracks, C.TRACK_TYPE_AUDIO) { trackN.format(it) }
    val text = choices(tracks, C.TRACK_TYPE_TEXT) { trackN.format(it) }
    val video = choices(tracks, C.TRACK_TYPE_VIDEO) { trackN.format(it) }

    val audioPart: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel(stringResource(R.string.player_audio))
            if (audio.isEmpty()) Text(stringResource(R.string.player_no_tracks), style = Troy.type.bodySmall, color = c.text2)
            audio.forEach { t ->
                PickRow(if (t.supported) t.label else "${t.label} ($unsupported)", t.selected, t.supported) { onSelect(t.group, t.index) }
            }
            if (video.size > 1) {
                SectionLabel(stringResource(R.string.player_quality), Modifier.padding(top = 12.dp))
                PickRow(stringResource(R.string.quality_auto), selected = false, onClick = onAutoQuality)
                video.forEach { t -> PickRow(t.label, t.selected, t.supported) { onSelect(t.group, t.index) } }
            }
        }
    }
    val subtitlePart: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel(stringResource(R.string.player_subtitles))
            PickRow(stringResource(R.string.player_subtitles_off), selected = subtitlesOff && external == null, onClick = onSubtitlesOff)
            text.forEach { t ->
                PickRow(t.label, external == null && !subtitlesOff && t.selected, t.supported) { onSelect(t.group, t.index) }
            }
            if (external != null) PickRow(external.name, selected = true) {}
            TroyButton(stringResource(R.string.player_load_subtitle), onPickFile, icon = Icons.Filled.FileOpen, modifier = Modifier.padding(top = 6.dp))

            SectionLabel(stringResource(R.string.player_subtitle_delay), Modifier.padding(top = 12.dp))
            Text(
                (if (delayMs > 0) "+" else "") + formatNumber(delayMs / 1000.0) + " " + stringResource(R.string.player_seconds_short),
                style = Troy.type.title,
                color = c.accent,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyChip("−1", false, { onDelay(delayMs - 1000) })
                TroyChip("−0.1", false, { onDelay(delayMs - 100) })
                TroyChip("+0.1", false, { onDelay(delayMs + 100) })
                TroyChip("+1", false, { onDelay(delayMs + 1000) })
                TroyChip(stringResource(R.string.player_reset), false, { onDelay(0) })
            }
            Text(stringResource(R.string.player_delay_hint), style = Troy.type.bodySmall, color = c.text2)

            if (external != null) {
                SectionLabel(stringResource(R.string.player_frame_rate), Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroyChip(stringResource(R.string.player_frame_rate_off), subtitleSpeed == 1.0, { onSubtitleSpeed(1.0) })
                    TroyChip("23.976 → 25", subtitleSpeed == FPS_23_TO_25, { onSubtitleSpeed(FPS_23_TO_25) })
                    TroyChip("25 → 23.976", subtitleSpeed == FPS_25_TO_23, { onSubtitleSpeed(FPS_25_TO_23) })
                }
                SectionLabel(stringResource(R.string.player_encoding), Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto", "UTF-8", "windows-1256", "ISO-8859-6", "UTF-16", "windows-1252").forEach { e ->
                        TroyChip(if (e == "auto") stringResource(SR.string.encoding_auto) else e, external.encoding.equals(e, true), { onEncoding(e) })
                    }
                }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) { audioPart() }
            Column(Modifier.weight(1f)) { subtitlePart() }
        }
    } else {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            audioPart()
            subtitlePart()
        }
    }
}

const val FPS_23_TO_25 = 25.0 / 23.976
const val FPS_25_TO_23 = 23.976 / 25.0

/** Decoder switcher, orientation lock and background play. More tools arrive in Phase 4. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsPanel(
    decoder: DecoderMode,
    onDecoder: (DecoderMode) -> Unit,
    orientationLock: String,
    onOrientationLock: (String) -> Unit,
    showOrientation: Boolean,
    backgroundPlay: Boolean,
    onBackgroundPlay: (Boolean) -> Unit,
    onPip: (() -> Unit)?,
    onEqualizer: () -> Unit,
    boostPercent: Int?,
    onResetBoost: () -> Unit,
    quietRecording: Boolean,
    onQuietRecording: (Boolean) -> Unit,
    infoOverlay: Boolean,
    onInfoOverlay: (Boolean) -> Unit,
) {
    val c = Troy.colors
    PanelColumn(stringResource(R.string.player_tools)) {
        TroyButton(stringResource(R.string.eq_title), onEqualizer, kind = ButtonKind.SECONDARY)
        if (boostPercent != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.eq_boost) + ": " + formatNumber(boostPercent) + "%",
                    style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f),
                )
                if (boostPercent != 100) TroyButton(stringResource(R.string.eq_reset_boost), onResetBoost, kind = ButtonKind.GHOST)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(SR.string.setting_quiet_recording), style = Troy.type.body, color = c.text)
                Text(stringResource(R.string.player_experimental), style = Troy.type.bodySmall, color = c.text3)
            }
            TroyToggle(quietRecording, onQuietRecording)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(SR.string.setting_info_overlay), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
            TroyToggle(infoOverlay, onInfoOverlay)
        }
        SectionLabel(stringResource(SR.string.setting_decoder))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TroyChip("HW+", decoder == DecoderMode.HW_PLUS, { onDecoder(DecoderMode.HW_PLUS) })
            TroyChip("HW", decoder == DecoderMode.HW, { onDecoder(DecoderMode.HW) })
            TroyChip("SW", decoder == DecoderMode.SW, { onDecoder(DecoderMode.SW) })
        }
        Text(stringResource(SR.string.setting_decoder_summary), style = Troy.type.bodySmall, color = c.text2)
        if (showOrientation) {
            SectionLabel(stringResource(R.string.player_orientation_lock))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyChip(stringResource(SR.string.orientation_video), orientationLock == "auto", { onOrientationLock("auto") })
                TroyChip(stringResource(SR.string.orientation_landscape), orientationLock == "landscape", { onOrientationLock("landscape") })
                TroyChip(stringResource(SR.string.orientation_portrait), orientationLock == "portrait", { onOrientationLock("portrait") })
                TroyChip(stringResource(SR.string.orientation_system), orientationLock == "system", { onOrientationLock("system") })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(SR.string.setting_background), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
            TroyToggle(backgroundPlay, onBackgroundPlay)
        }
        if (onPip != null) TroyButton(stringResource(R.string.player_pip), onPip, kind = ButtonKind.SECONDARY)
        Text(stringResource(R.string.player_more_tools_later), style = Troy.type.bodySmall, color = c.text3)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueuePanel(titles: List<String>, current: Int, repeatMode: Int, shuffle: Boolean, onPlay: (Int) -> Unit, onRepeat: () -> Unit, onShuffle: () -> Unit) {
    PanelColumn(stringResource(R.string.player_queue)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val repeatLabel = stringResource(
                when (repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> R.string.player_repeat_all
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
                    else -> R.string.player_repeat_off
                },
            )
            TroyChip(repeatLabel, repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF, onRepeat)
            TroyChip(stringResource(R.string.player_shuffle), shuffle, onShuffle)
        }
        titles.forEachIndexed { i, t -> PickRow(t, i == current) { onPlay(i) } }
    }
}

/** Shape used for side and bottom panels. */
val PanelShape = TroyShapes.Panel
