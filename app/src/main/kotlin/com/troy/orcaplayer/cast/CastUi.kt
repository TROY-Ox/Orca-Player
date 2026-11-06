package com.troy.orcaplayer.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.GlassPanel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.ui.player.timecode

/** The system Cast button: it lists Chromecast and Google TV devices on the network. */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ctx).also { b ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, b) }
                b.contentDescription = ctx.getString(R.string.cast_button)
            }
        },
        modifier = modifier.size(48.dp),
    )
}

/** The phone as a remote while the TV plays (20.7). */
@Composable
fun CastRemote(
    state: CastState,
    problem: CastProblem?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    cast: CastController,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Troy.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    GlassPanel(modifier.widthIn(max = 520.dp).padding(16.dp)) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Cast, null, tint = c.accent)
                Text(stringResource(R.string.cast_playing_on, state.device), style = Troy.type.label, color = c.text2)
            }
            Text(state.title, style = Troy.type.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (problem) {
                CastProblem.FORMAT -> TroyAlert(stringResource(R.string.cast_format), AlertKind.WARN)
                CastProblem.NO_NETWORK -> TroyAlert(stringResource(R.string.cast_no_network), AlertKind.WARN)
                CastProblem.LOAD_FAILED -> TroyAlert(stringResource(R.string.cast_failed), AlertKind.DANGER)
                null -> Unit
            }
            val duration = state.durationMs
            val shown = dragging?.let { (it * duration).toLong() } ?: state.positionMs
            Column(Modifier.fillMaxWidth()) {
                Slider(
                    value = dragging ?: if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { cast.seekTo((it * duration).toLong()) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.controlFill),
                )
                Row {
                    Text(timecode(shown), style = Troy.type.mono, color = c.text)
                    Box(Modifier.weight(1f))
                    Text(timecode(duration), style = Troy.type.mono, color = c.text2)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasPrevious) TroyIconButton(Icons.Filled.SkipPrevious, stringResource(R.string.cast_previous), onPrevious)
                TroyIconButton(Icons.Filled.Replay10, stringResource(R.string.vlc_back_10), { cast.seekBy(-10_000) })
                if (state.buffering || state.loading) {
                    CircularProgressIndicator(Modifier.size(40.dp), color = c.accent, strokeWidth = 3.dp)
                } else {
                    TroyIconButton(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (state.playing) R.string.vlc_pause else R.string.vlc_play),
                        cast::toggle,
                        modifier = Modifier.size(64.dp),
                    )
                }
                TroyIconButton(Icons.Filled.Forward10, stringResource(R.string.vlc_forward_10), { cast.seekBy(10_000) })
                if (hasNext) TroyIconButton(Icons.Filled.SkipNext, stringResource(R.string.cast_next), onNext)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.cast_volume), tint = c.text2)
                Slider(
                    value = state.volume.toFloat(),
                    onValueChange = { cast.setVolume(it.toDouble()) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.controlFill),
                )
            }
            if (state.subtitles.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroyChip(stringResource(R.string.cast_subtitles_off), state.activeSubtitle == null, { cast.setSubtitle(null) })
                    state.subtitles.take(4).forEach { (id, name) -> TroyChip(name, state.activeSubtitle == id, { cast.setSubtitle(id) }) }
                }
            }
            Text(stringResource(R.string.cast_unavailable_features), style = Troy.type.bodySmall, color = c.text3)
            TroyButton(stringResource(R.string.cast_stop), cast::stop, kind = ButtonKind.GHOST)
        }
    }
}
