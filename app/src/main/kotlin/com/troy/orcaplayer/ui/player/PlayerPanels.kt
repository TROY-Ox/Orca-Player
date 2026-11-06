package com.troy.orcaplayer.ui.player

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.Search
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

enum class PlayerPanel { NONE, SPEED, TRACKS, SUBTITLES, TOOLS, QUEUE, AUDIO, ONLINE_SUBTITLES, FILTERS, AUDIO_DELAY }

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
internal fun PanelColumn(title: String, content: @Composable () -> Unit) {
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
    onSearchOnline: () -> Unit,
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
            TroyButton(stringResource(R.string.online_subs_search), onSearchOnline, icon = Icons.Filled.Search, modifier = Modifier.padding(top = 6.dp))

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

private val onlineLanguages = listOf("ar", "en", "fr", "es", "de", "tr", "pt", "it", "ru", "hi", "ja", "ko", "zh", "fa", "ur", "id")

/** Manual online subtitle search (9.6): stream subtitles, subtitle addons and OpenSubtitles, with match quality. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnlineSubtitlesPanel(state: OnlineSearch, preferred: String?, onSearch: (List<String>) -> Unit, onPick: (com.troy.orcaplayer.data.OnlineSubtitle) -> Unit) {
    val c = Troy.colors
    var langs by remember { mutableStateOf(state.languages.ifEmpty { listOfNotNull(preferred) }) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (state.results.isEmpty() && !state.loading) onSearch(langs) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.online_subs_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            onlineLanguages.forEach { l ->
                com.troy.orcaplayer.core.design.component.TroyChip(l.uppercase(), l in langs, {
                    langs = if (l in langs) langs - l else langs + l
                    onSearch(langs)
                })
            }
        }
        when {
            state.loading -> Text(stringResource(R.string.loading), style = Troy.type.bodySmall, color = c.text2)
            state.results.isEmpty() -> Text(stringResource(R.string.online_subs_none), style = Troy.type.bodySmall, color = c.text2)
        }
        if (state.keyMissing) Text(stringResource(R.string.online_subs_no_key), style = Troy.type.bodySmall, color = c.text3)
        state.results.forEach { r ->
            val quality = when {
                r.match >= 90 -> stringResource(R.string.online_match_exact)
                r.match >= 60 -> stringResource(R.string.online_match_good)
                else -> stringResource(R.string.online_match_title)
            }
            PickRow(
                listOfNotNull(r.language?.uppercase(), r.label, quality, r.source.sourceName).joinToString(" · "),
                selected = false,
            ) { onPick(r) }
        }
    }
}
