@file:kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.troy.orcaplayer.ui.tools

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.PlayerSurface
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.tools.ClipJob
import com.troy.orcaplayer.tools.ClipMode
import com.troy.orcaplayer.tools.Keyframes
import com.troy.orcaplayer.tools.ToolMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Handle { START, END }

/** Builds a small preview player for a tool, honouring the link's HTTP headers. */
@OptIn(UnstableApi::class)
@Composable
fun rememberPreviewPlayer(target: ToolTarget): ExoPlayer {
    val context = LocalContext.current
    val player = remember {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(target.headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build().apply {
                setMediaItem(MediaItem.fromUri(target.uri))
                prepare()
                if (target.positionMs > 0) seekTo(target.positionMs)
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * Clip cutter (12.1): preview with a loop over the range, two handles on a timeline, exact typed
 * times, nudges by one frame or one second, and three cut modes.
 */
@OptIn(UnstableApi::class)
@Composable
fun ClipCutterScreen(target: ToolTarget, onBack: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    val player = rememberPreviewPlayer(target)
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(target.positionMs) }
    var playing by remember { mutableStateOf(false) }
    var start by rememberSaveable { mutableLongStateOf(target.positionMs) }
    var end by rememberSaveable { mutableLongStateOf(target.positionMs + 30_000) }
    var active by rememberSaveable { mutableStateOf(Handle.START) }
    var mode by rememberSaveable { mutableStateOf(ClipMode.SMART) }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var loop by rememberSaveable { mutableStateOf(true) }
    var fastStart by remember { mutableStateOf<Long?>(null) }
    var jobId by rememberSaveable { mutableStateOf<String?>(null) }
    val startJob = rememberJobStarter { jobId = it.id }

    // Poll the preview: position, duration, and loop the range while playing.
    LaunchedEffect(player) {
        while (true) {
            val d = player.duration
            if (d > 0 && duration != d) {
                duration = d
                if (end > d || end <= start) end = minOf(d, start + 30_000)
                if (start >= d) start = 0
            }
            position = player.currentPosition
            playing = player.isPlaying
            if (loop && playing && end > start && position >= end) player.seekTo(start)
            delay(100)
        }
    }
    // The real start of a Fast cut is the keyframe at or before the chosen start.
    LaunchedEffect(mode, start) {
        fastStart = if (mode == ClipMode.FAST) {
            delay(300)
            withContext(Dispatchers.IO) { Keyframes.previousSync(context, target.uri, start, target.headers) }
        } else null
    }

    val frameMs = (player.videoFormat?.frameRate?.takeIf { it > 0 }?.let { 1000f / it } ?: (1000f / 30f)).toLong().coerceAtLeast(1)
    fun setHandle(h: Handle, ms: Long) {
        val v = ms.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        if (h == Handle.START) start = minOf(v, end - frameMs).coerceAtLeast(0) else end = maxOf(v, start + frameMs)
        player.seekTo(if (h == Handle.START) start else end)
    }

    ToolScaffold(stringResource(R.string.tool_clip_cutter), onBack) {
        ToolCard {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
                PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
            }
            Text(target.title, style = Troy.type.bodySmall, color = c.text2, maxLines = 1)
            // Media timelines stay left to right in Arabic, like the player's own seek bar.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                RangeBar(duration, start, end, position, active,
                    onSelect = { active = it },
                    onDrag = { h, ms -> setHandle(h, ms) },
                    onSeek = { player.seekTo(it) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(ToolMath.formatTime(position), style = Troy.type.mono, color = c.text2)
                    TroyIconButton(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (playing) R.string.player_pause else R.string.player_play),
                        onClick = {
                            if (player.isPlaying) player.pause() else {
                                if (player.currentPosition !in start until end) player.seekTo(start)
                                player.play()
                            }
                        },
                    )
                    Text(ToolMath.formatTime(end - start), style = Troy.type.mono, color = c.accent)
                }
            }
            ChoiceRow {
                TroyButton(stringResource(R.string.clip_set_start), { setHandle(Handle.START, player.currentPosition); active = Handle.START })
                TroyButton(stringResource(R.string.clip_set_end), { setHandle(Handle.END, player.currentPosition); active = Handle.END })
                TroyChip(stringResource(R.string.clip_loop), loop, { loop = !loop })
            }
        }
        ToolCard {
            SectionLabel(stringResource(R.string.clip_range))
            TimeField(stringResource(R.string.clip_start), start, active == Handle.START, { active = Handle.START }) { setHandle(Handle.START, it) }
            TimeField(stringResource(R.string.clip_end), end, active == Handle.END, { active = Handle.END }) { setHandle(Handle.END, it) }
            Text(stringResource(R.string.clip_nudge, stringResource(if (active == Handle.START) R.string.clip_start else R.string.clip_end)), style = Troy.type.bodySmall, color = c.text3)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ChoiceRow {
                    val cur = if (active == Handle.START) start else end
                    TroyButton(stringResource(R.string.clip_minus_second), { setHandle(active, cur - 1000) }, kind = ButtonKind.GHOST)
                    TroyButton(stringResource(R.string.clip_minus_frame), { setHandle(active, cur - frameMs) }, kind = ButtonKind.GHOST)
                    TroyButton(stringResource(R.string.clip_plus_frame), { setHandle(active, cur + frameMs) }, kind = ButtonKind.GHOST)
                    TroyButton(stringResource(R.string.clip_plus_second), { setHandle(active, cur + 1000) }, kind = ButtonKind.GHOST)
                }
            }
        }
        ToolCard {
            SectionLabel(stringResource(R.string.clip_mode))
            ChoiceRow {
                TroyChip(stringResource(R.string.clip_mode_fast), mode == ClipMode.FAST, { mode = ClipMode.FAST })
                TroyChip(stringResource(R.string.clip_mode_smart), mode == ClipMode.SMART, { mode = ClipMode.SMART })
                TroyChip(stringResource(R.string.clip_mode_precise), mode == ClipMode.PRECISE, { mode = ClipMode.PRECISE })
            }
            Text(
                stringResource(
                    when (mode) {
                        ClipMode.FAST -> R.string.clip_mode_fast_info
                        ClipMode.SMART -> R.string.clip_mode_smart_info
                        ClipMode.PRECISE -> R.string.clip_mode_precise_info
                    },
                ),
                style = Troy.type.bodySmall, color = c.text2,
            )
            val fs = fastStart
            if (mode == ClipMode.FAST && fs != null && fs != start) {
                TroyAlert(stringResource(R.string.clip_fast_real_start, ToolMath.formatTime(fs)), AlertKind.INFO, Modifier.fillMaxWidth())
            }
            SectionLabel(stringResource(R.string.clip_output))
            ChoiceRow {
                TroyChip(stringResource(R.string.clip_out_video), !audioOnly, { audioOnly = false })
                TroyChip(stringResource(R.string.clip_out_audio), audioOnly, { audioOnly = true })
            }
            Text(stringResource(R.string.clip_out_more_later), style = Troy.type.bodySmall, color = c.text3)
            TroyButton(
                stringResource(R.string.clip_save),
                onClick = {
                    startJob(ClipJob(target.uri.toString(), target.title, start, end, mode, audioOnly, target.headers))
                },
                kind = ButtonKind.PRIMARY,
                enabled = end > start,
            )
        }
        JobProgressCard(jobId)
    }
}

@Composable
private fun TimeField(label: String, valueMs: Long, selected: Boolean, onSelect: () -> Unit, onSet: (Long) -> Unit) {
    val c = Troy.colors
    var text by remember(valueMs) { mutableStateOf(ToolMath.formatTime(valueMs)) }
    var bad by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TroyChip(label, selected, onSelect)
        TroyTextField(
            value = text,
            onValueChange = { text = it; bad = false },
            modifier = Modifier.weight(1f),
            placeholder = "0:00:00.000",
            onImeAction = {
                val ms = ToolMath.parseTime(text)
                if (ms == null) bad = true else onSet(ms)
            },
        )
    }
    if (bad) Text(stringResource(R.string.clip_bad_time), style = Troy.type.bodySmall, color = c.danger)
}

/** Timeline with the kept range, both handles and the playhead. Drag a handle; tap to seek. */
@Composable
private fun RangeBar(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    active: Handle,
    onSelect: (Handle) -> Unit,
    onDrag: (Handle, Long) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val c = Troy.colors
    val density = LocalDensity.current
    val dur = durationMs.coerceAtLeast(1)
    val latestStart by rememberUpdatedState(startMs)
    val latestEnd by rememberUpdatedState(endMs)
    val latestDur by rememberUpdatedState(dur)
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        fun toMs(x: Float): Long = (x / widthPx * latestDur).toLong().coerceIn(0, latestDur)
        fun xOf(ms: Long): Float = ms.toFloat() / dur * widthPx
        Box(
            Modifier.fillMaxSize()
                .pointerInput(widthPx) {
                    var dragging = Handle.START
                    detectDragGestures(
                        onDragStart = { o ->
                            val ds = kotlin.math.abs(o.x - latestStart.toFloat() / latestDur * widthPx)
                            val de = kotlin.math.abs(o.x - latestEnd.toFloat() / latestDur * widthPx)
                            dragging = if (ds <= de) Handle.START else Handle.END
                            onSelect(dragging)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            onDrag(dragging, toMs(change.position.x))
                        },
                    )
                }
                .pointerInput(widthPx) { detectTapGestures { onSeek(toMs(it.x)) } },
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.hair))
            val sx = xOf(startMs)
            val ex = xOf(endMs)
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset { IntOffset(sx.toInt(), 0) }
                    .width(with(density) { (ex - sx).coerceAtLeast(1f).toDp() }).height(6.dp)
                    .background(c.accent.copy(alpha = 0.55f)),
            )
            HandleMark(sx, active == Handle.START)
            HandleMark(ex, active == Handle.END)
            Box(
                Modifier.offset { IntOffset(xOf(positionMs).toInt() - 1, 0) }.width(2.dp).fillMaxHeight()
                    .background(c.text),
            )
        }
    }
}

@Composable
private fun HandleMark(x: Float, selected: Boolean) {
    val c = Troy.colors
    Box(
        Modifier.offset { IntOffset(x.toInt() - 6.dp.roundToPx(), 0) }
            .width(12.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp))
            .background(if (selected) c.accent else c.text2),
    )
}
