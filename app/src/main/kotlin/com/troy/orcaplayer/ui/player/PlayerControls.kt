package com.troy.orcaplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.pressScale
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.Digits
import com.troy.orcaplayer.core.model.ControlSize
import com.troy.orcaplayer.core.model.PlayerControl
import java.util.Locale

@Composable
fun timecode(ms: Long): String =
    Digits.timecode(ms, LocalConfiguration.current.locales[0] ?: Locale.getDefault(), Troy.state.arabicDigits)

/** A round control on the video overlay. Big play button for LARGE. */
@Composable
fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, size: ControlSize = ControlSize.MEDIUM, onLongClick: (() -> Unit)? = null, loading: Boolean = false) {
    val c = Troy.colors
    val source = remember { MutableInteractionSource() }
    val box: Dp = when (size) {
        ControlSize.SMALL -> 40.dp
        ControlSize.MEDIUM -> 48.dp
        ControlSize.LARGE -> 72.dp
    }
    val iconSize: Dp = when (size) {
        ControlSize.SMALL -> 20.dp
        ControlSize.MEDIUM -> 26.dp
        ControlSize.LARGE -> 44.dp
    }
    val bg = if (size == ControlSize.LARGE) c.accent.copy(alpha = 0.9f) else androidx.compose.ui.graphics.Color.Transparent
    val tint = if (size == ControlSize.LARGE) c.onAccent else c.text
    Box(
        Modifier
            .size(box)
            .pressScale(source)
            .troyFocusRing(box / 2)
            .clip(TroyShapes.Pill)
            .background(bg)
            .then(
                if (onLongClick != null) {
                    Modifier
                        .semantics { contentDescription = label }
                        .pointerInput(onClick, onLongClick) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() }) }
                        .focusable(interactionSource = source)
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { onClick(); true } else false
                        }
                } else {
                    Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
                        .semantics { contentDescription = label }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                color = tint,
                strokeWidth = 3.dp,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                modifier = Modifier.size(iconSize),
            )
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/** Icon and label for every control the Phase 2 player can show. Null means "not in this version". */
data class ControlSpec(val icon: ImageVector, val label: String)

@Composable
fun controlSpec(control: PlayerControl, playing: Boolean, skipSeconds: Int): ControlSpec? = when (control) {
    PlayerControl.BACK -> ControlSpec(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.player_back))
    PlayerControl.TRACKS -> ControlSpec(Icons.Filled.Audiotrack, stringResource(R.string.player_tracks))
    PlayerControl.SUBTITLES -> ControlSpec(Icons.Filled.Subtitles, stringResource(R.string.player_subtitles))
    PlayerControl.SPEED -> ControlSpec(Icons.Filled.Speed, stringResource(R.string.player_speed))
    PlayerControl.ASPECT -> ControlSpec(Icons.Filled.AspectRatio, stringResource(R.string.player_aspect))
    PlayerControl.LOCK -> ControlSpec(Icons.Filled.Lock, stringResource(R.string.player_lock))
    PlayerControl.ROTATE -> ControlSpec(Icons.Filled.ScreenRotation, stringResource(R.string.player_rotate))
    PlayerControl.PIP -> ControlSpec(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pip))
    PlayerControl.TOOLS -> ControlSpec(Icons.Filled.MoreVert, stringResource(R.string.player_tools))
    PlayerControl.QUEUE -> ControlSpec(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.player_queue))
    PlayerControl.PLAY_PAUSE -> if (playing) ControlSpec(Icons.Filled.Pause, stringResource(R.string.player_pause))
        else ControlSpec(Icons.Filled.PlayArrow, stringResource(R.string.player_play))
    PlayerControl.PREVIOUS -> ControlSpec(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous))
    PlayerControl.NEXT -> ControlSpec(Icons.Filled.SkipNext, stringResource(R.string.player_next))
    PlayerControl.REWIND -> ControlSpec(if (skipSeconds == 10) Icons.Filled.Replay10 else Icons.Filled.Replay, stringResource(R.string.player_rewind, skipSeconds))
    PlayerControl.FORWARD -> ControlSpec(Icons.Filled.Forward10, stringResource(R.string.player_forward, skipSeconds))
    // Title, seek bar and time are drawn separately; the rest arrive in later phases.
    PlayerControl.TITLE, PlayerControl.SEEK_BAR, PlayerControl.TIME,
    PlayerControl.EQUALIZER, PlayerControl.SCREENSHOT, PlayerControl.CAST -> null
}

/**
 * Seek bar with the buffered range. Drag or tap to seek; with a D-pad, left and right move 10 seconds.
 * While dragging, [onPreview] reports the time under the finger.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onPreview: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** A-B repeat points, drawn as marks on the track. */
    markA: Long? = null,
    markB: Long? = null,
) {
    val c = Troy.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val label = stringResource(R.string.player_seek)
    val enabled = durationMs > 0
    val fraction = dragFraction ?: if (enabled) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (enabled) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { contentDescription = label }
            .focusable(enabled, source)
            .onKeyEvent { e ->
                if (!enabled || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { onSeek(positionMs + 10_000); true }
                    Key.DirectionLeft -> { onSeek(positionMs - 10_000); true }
                    else -> false
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { pos -> onSeek((pos.x / size.width * durationMs).toLong()) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        dragFraction = (pos.x / size.width).coerceIn(0f, 1f)
                        onPreview((dragFraction!! * durationMs).toLong())
                    },
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                        onPreview(null)
                    },
                    onDragCancel = {
                        dragFraction = null
                        onPreview(null)
                    },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onPreview((dragFraction!! * durationMs).toLong())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val track = c.controlFill
        val buf = c.text3
        val accent = c.accent
        val thumbR = if (focused || dragFraction != null) 9.dp else 6.dp
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            widthPx = size.width
            val h = 4.dp.toPx()
            val y = size.height / 2 - h / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(track, Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(buf, Offset(0f, y), Size(size.width * buffered, h), r)
            drawRoundRect(accent, Offset(0f, y), Size(size.width * fraction, h), r)
            if (enabled && markA != null && markB != null) {
                val ax = size.width * (markA.toFloat() / durationMs).coerceIn(0f, 1f)
                val bx = size.width * (markB.toFloat() / durationMs).coerceIn(0f, 1f)
                drawRect(accent.copy(alpha = 0.35f), Offset(ax, y - h), Size((bx - ax).coerceAtLeast(1f), h * 3))
            }
            listOfNotNull(markA, markB).forEach { m ->
                if (enabled) {
                    val x = size.width * (m.toFloat() / durationMs).coerceIn(0f, 1f)
                    drawRect(c.text, Offset(x - 1.dp.toPx(), y - h), Size(2.dp.toPx(), h * 3))
                }
            }
            drawCircle(accent, thumbR.toPx(), Offset(size.width * fraction, size.height / 2))
        }
    }
}

/** Elapsed / total, or remaining. Tap to switch. */
@Composable
fun TimeLabel(positionMs: Long, durationMs: Long, remaining: Boolean, onToggle: () -> Unit) {
    val t = Troy.type
    val c = Troy.colors
    val text = when {
        durationMs <= 0 -> timecode(positionMs)
        remaining -> "-" + timecode(durationMs - positionMs) + " / " + timecode(durationMs)
        else -> timecode(positionMs) + " / " + timecode(durationMs)
    }
    Text(
        text,
        style = t.mono,
        color = c.text,
        modifier = Modifier
            .clip(TroyShapes.Control)
            .troyFocusRing()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

@Composable
fun ControlRow(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
fun ControlColumn(content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
}
