package com.troy.orcaplayer.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.motion.SpringMath
import com.troy.orcaplayer.core.design.motion.TroySprings
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.core.model.AbLoop
import com.troy.orcaplayer.core.model.ToolId
import com.troy.orcaplayer.player.engine.DecoderMode
import com.troy.orcaplayer.ui.tools.icon
import com.troy.orcaplayer.ui.tools.nameRes
import kotlinx.coroutines.launch
import com.troy.orcaplayer.core.settings.R as SR

/** Where a player sheet sits: along a side edge (landscape) or the bottom edge (portrait). */
enum class SheetEdge { START, END, BOTTOM }

/**
 * Makes a panel draggable toward its edge: it follows the finger with a spring, rubber-bands when
 * pulled the other way, and a flick or a drag past half its size closes it.
 */
@Composable
fun Modifier.sheetDrag(edge: SheetEdge, onDismiss: () -> Unit): Modifier {
    val motion = Troy.motion
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val size = remember { floatArrayOf(1f) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Positive means "toward the edge" (closing). Physical screen direction depends on edge and RTL.
    val sign = when (edge) {
        SheetEdge.BOTTOM -> 1f
        SheetEdge.END -> if (rtl) -1f else 1f
        SheetEdge.START -> if (rtl) 1f else -1f
    }
    val raw = remember { floatArrayOf(0f) }
    val state = rememberDraggableState { delta ->
        raw[0] += delta * sign
        val r = raw[0]
        val shown = if (r < 0f) -SpringMath.rubber(-r, size[0]) else r
        scope.launch { offset.snapTo(shown) }
    }
    return this
        .onSizeChanged { size[0] = (if (edge == SheetEdge.BOTTOM) it.height else it.width).toFloat().coerceAtLeast(1f) }
        .graphicsLayer {
            val o = offset.value * sign
            if (edge == SheetEdge.BOTTOM) translationY = o else translationX = o
        }
        .draggable(
            state = state,
            orientation = if (edge == SheetEdge.BOTTOM) Orientation.Vertical else Orientation.Horizontal,
            onDragStarted = { raw[0] = offset.value.coerceAtLeast(0f) },
            onDragStopped = { velocity ->
                val v = velocity * sign
                val projected = SpringMath.project(offset.value, v)
                if (projected > size[0] / 2f) {
                    if (!motion.reduceMotion) offset.animateTo(size[0], motion.springSpec(TroySprings.Sheet), v)
                    onDismiss()
                    offset.snapTo(0f)
                } else {
                    offset.animateTo(0f, motion.springSpec(TroySprings.Sheet), v)
                }
            },
        )
}

/** Values shown in the Tools panel. */
data class ToolsPanelState(
    val tools: List<ToolId>,
    val decoder: DecoderMode,
    val orientationLock: String,
    val showOrientation: Boolean,
    val backgroundPlay: Boolean,
    val canPip: Boolean,
    val boostPercent: Int?,
    val quietRecording: Boolean,
    val infoOverlay: Boolean,
    val abStart: Long?,
    val abLoop: AbLoop?,
    val sleepRemainingMs: Long?,
    val sleepAtEnd: Boolean,
    val night: Boolean,
    val hasVideoPrefs: Boolean,
    val canSaveFolder: Boolean,
    val rememberPerVideo: Boolean,
    val canSave: Boolean,
)

/** What the Tools panel can do. */
interface ToolsPanelActions {
    fun openTool(tool: ToolId)
    fun screenshot()
    fun equalizer()
    fun markA()
    fun markB()
    fun clearAb()
    fun sleep(minutes: Int)
    fun sleepAtEnd()
    fun cancelSleep()
    fun night(on: Boolean)
    fun resetBoost()
    fun quietRecording(on: Boolean)
    fun saveForFolder()
    fun resetVideo()
    fun decoder(mode: DecoderMode)
    fun orientation(lock: String)
    fun backgroundPlay(on: Boolean)
    fun infoOverlay(on: Boolean)
    fun pip()
}

/** The player's Tools panel: the tools in the user's order (Settings, Tools), then player options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsPanel(s: ToolsPanelState, a: ToolsPanelActions) {
    val c = Troy.colors
    PanelColumn(stringResource(R.string.player_tools)) {
        s.tools.filter { it.inPanel }.forEach { tool ->
            when (tool) {
                ToolId.CLIP_CUTTER, ToolId.SUBTITLE_EDITOR, ToolId.AUDIO_EXTRACTOR, ToolId.CONVERTER, ToolId.COMPRESSOR, ToolId.MEDIA_INFO ->
                    ToolRow(tool, enabled = tool == ToolId.MEDIA_INFO || tool == ToolId.SUBTITLE_EDITOR || s.canSave) { a.openTool(tool) }
                ToolId.SCREENSHOT -> ToolRow(tool, enabled = true, onClick = a::screenshot)
                ToolId.EQUALIZER -> ToolRow(tool, enabled = true, onClick = a::equalizer)
                ToolId.DOWNLOAD -> ToolRow(tool, enabled = false, summary = stringResource(R.string.tool_download_later)) {}
                ToolId.AB_REPEAT -> {
                    SectionLabel(stringResource(R.string.tool_ab_repeat))
                    val status = when {
                        s.abLoop != null -> stringResource(R.string.ab_active, timecode(s.abLoop.aMs), timecode(s.abLoop.bMs))
                        s.abStart != null -> stringResource(R.string.ab_waiting, timecode(s.abStart))
                        else -> stringResource(R.string.ab_hint)
                    }
                    Text(status, style = Troy.type.bodySmall, color = if (s.abLoop != null) c.accent else c.text2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyButton(stringResource(R.string.ab_set_a), a::markA)
                        TroyButton(stringResource(R.string.ab_set_b), a::markB)
                        if (s.abLoop != null || s.abStart != null) TroyButton(stringResource(R.string.ab_clear), a::clearAb, kind = ButtonKind.GHOST)
                    }
                }
                ToolId.SLEEP_TIMER -> {
                    SectionLabel(stringResource(R.string.tool_sleep_timer))
                    if (s.sleepRemainingMs != null) {
                        Text(stringResource(R.string.sleep_remaining, timecode(s.sleepRemainingMs)), style = Troy.type.bodySmall, color = c.accent)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 45, 60, 90).forEach { m ->
                            TroyChip(stringResource(R.string.sleep_minutes, formatNumber(m)), false, { a.sleep(m) })
                        }
                        TroyChip(stringResource(R.string.sleep_end_of_video), s.sleepAtEnd, a::sleepAtEnd)
                        if (s.sleepRemainingMs != null || s.sleepAtEnd) TroyChip(stringResource(R.string.sleep_off), false, a::cancelSleep)
                    }
                }
                ToolId.NIGHT_MODE -> ToggleRow(stringResource(R.string.tool_night_mode), stringResource(R.string.tool_night_mode_summary), s.night, a::night)
                ToolId.VOLUME_BOOST -> if (s.boostPercent != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.eq_boost) + ": " + formatNumber(s.boostPercent) + "%",
                            style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f),
                        )
                        if (s.boostPercent != 100) TroyButton(stringResource(R.string.eq_reset_boost), a::resetBoost, kind = ButtonKind.GHOST)
                    }
                }
                ToolId.QUIET_MODE -> ToggleRow(stringResource(SR.string.setting_quiet_recording), stringResource(R.string.player_experimental), s.quietRecording, a::quietRecording)
                ToolId.PER_VIDEO -> {
                    SectionLabel(stringResource(R.string.tool_per_video))
                    Text(
                        stringResource(
                            when {
                                !s.rememberPerVideo -> R.string.per_video_off
                                s.hasVideoPrefs -> R.string.per_video_saved
                                else -> R.string.per_video_none
                            },
                        ),
                        style = Troy.type.bodySmall, color = c.text2,
                    )
                    if (s.rememberPerVideo) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.canSaveFolder) TroyButton(stringResource(R.string.per_video_folder), a::saveForFolder)
                            if (s.hasVideoPrefs) TroyButton(stringResource(R.string.per_video_reset), a::resetVideo, kind = ButtonKind.GHOST)
                        }
                    }
                }
                ToolId.DECODER_CHECK, ToolId.URL_PLAYER -> Unit
            }
            Unit
        }

        SectionLabel(stringResource(SR.string.setting_decoder))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TroyChip("HW+", s.decoder == DecoderMode.HW_PLUS, { a.decoder(DecoderMode.HW_PLUS) })
            TroyChip("HW", s.decoder == DecoderMode.HW, { a.decoder(DecoderMode.HW) })
            TroyChip("SW", s.decoder == DecoderMode.SW, { a.decoder(DecoderMode.SW) })
        }
        Text(stringResource(SR.string.setting_decoder_summary), style = Troy.type.bodySmall, color = c.text2)
        if (s.showOrientation) {
            SectionLabel(stringResource(R.string.player_orientation_lock))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyChip(stringResource(SR.string.orientation_video), s.orientationLock == "auto", { a.orientation("auto") })
                TroyChip(stringResource(SR.string.orientation_landscape), s.orientationLock == "landscape", { a.orientation("landscape") })
                TroyChip(stringResource(SR.string.orientation_portrait), s.orientationLock == "portrait", { a.orientation("portrait") })
                TroyChip(stringResource(SR.string.orientation_system), s.orientationLock == "system", { a.orientation("system") })
            }
        }
        ToggleRow(stringResource(SR.string.setting_info_overlay), null, s.infoOverlay, a::infoOverlay)
        ToggleRow(stringResource(SR.string.setting_background), null, s.backgroundPlay, a::backgroundPlay)
        if (s.canPip) TroyButton(stringResource(R.string.player_pip), a::pip, kind = ButtonKind.SECONDARY)
    }
}

@Composable
private fun ToolRow(tool: ToolId, enabled: Boolean, summary: String? = null, onClick: () -> Unit) {
    val c = Troy.colors
    Row(
        Modifier.fillMaxWidth().troyFocusRing()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(tool.icon(), contentDescription = null, tint = if (enabled) c.accent else c.text3, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(tool.nameRes()), style = Troy.type.body, color = if (enabled) c.text else c.text3)
            val sub = summary ?: if (!enabled) stringResource(R.string.tool_save_blocked) else null
            if (sub != null) Text(sub, style = Troy.type.bodySmall, color = c.text3)
        }
    }
}

@Composable
private fun ToggleRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Troy.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Troy.type.body, color = c.text)
            if (summary != null) Text(summary, style = Troy.type.bodySmall, color = c.text3)
        }
        TroyToggle(checked, onChange)
    }
}

/** Keeps a value fresh while a panel shows a countdown. */
@Composable
fun TickEffect(active: Boolean, onTick: () -> Unit) {
    LaunchedEffect(active) {
        while (active) {
            onTick()
            kotlinx.coroutines.delay(500)
        }
    }
}
