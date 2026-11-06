package com.troy.orcaplayer.ui.mini

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.data.ResumePositionEntity
import com.troy.orcaplayer.core.data.ResumeRepository
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.player.engine.PlaybackEngine
import com.troy.orcaplayer.player.engine.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the in-app mini player is showing (20.5). The full-screen player sets it when it shrinks. */
object MiniPlayerState {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun show() { _visible.value = true }
    fun hide() { _visible.value = false }
}

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    val engine: PlaybackEngine,
    private val resume: ResumeRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val player = engine.playerFlow
    val audioOnly = engine.audioOnly

    /** Saves the position so the video resumes later, as the full player does. */
    fun save() {
        val p = engine.player
        val item = engine.itemAt(p.currentMediaItemIndex) ?: return
        if (p.isCurrentMediaItemLive) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (p.playbackState == Player.STATE_ENDED) duration else p.currentPosition.coerceIn(0, duration)
        val profileId = profiles.currentProfileId.value
        io.launch {
            runCatching {
                resume.save(
                    ResumePositionEntity(
                        profileId = profileId,
                        mediaKey = item.key.id,
                        path = item.uri,
                        title = item.title,
                        positionMs = if (position >= duration * 0.9) duration else position,
                        durationMs = duration,
                        updatedAt = System.currentTimeMillis(),
                        sourceKind = item.source.kind.name,
                        titleType = item.titleType,
                        titleId = item.episode?.titleId ?: (item.key as? com.troy.orcaplayer.core.model.MediaKey.SourceContent)?.titleId,
                        episodeId = item.episode?.episodeId?.takeIf { it.isNotEmpty() },
                        subtitle = item.subtitle,
                        poster = item.poster,
                        hidden = false,
                    ),
                )
            }
        }
    }

    fun close() {
        save()
        engine.stop()
        MiniPlayerState.hide()
    }

    fun toggle() {
        val p = engine.player
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
            p.play()
        }
    }
}

/** Reopens the full-screen player on what is playing now. */
fun openFullPlayer(context: Context) {
    MiniPlayerState.hide()
    context.startActivity(Intent(PlaybackService.ACTION_OPEN_PLAYER).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
}

/**
 * The mini player docked above the navigation bar (20.5): video, title, play and pause, close.
 * Tap it to return to full screen.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayerBar(vm: MiniPlayerViewModel, modifier: Modifier = Modifier) {
    val visible by MiniPlayerState.visible.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Troy.colors
    var playing by remember { mutableStateOf(player.isPlaying) }
    var title by remember { mutableStateOf(player.mediaMetadata.title?.toString().orEmpty()) }
    var progress by remember { mutableFloatStateOf(0f) }
    var empty by remember { mutableStateOf(player.mediaItemCount == 0) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playing = p.isPlaying
                title = p.mediaMetadata.title?.toString().orEmpty()
                empty = p.mediaItemCount == 0
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player, visible) {
        while (visible) {
            val d = player.duration
            progress = if (d > 0 && d != C.TIME_UNSET) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    // Playback stopped elsewhere (notification, another app): nothing to show.
    LaunchedEffect(empty) { if (empty && visible) MiniPlayerState.hide() }
    AnimatedVisibility(visible && !empty, modifier, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).glass(TroyShapes.Control)
                .troyFocusRing()
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.mini_open)) { openFullPlayer(context) },
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(TroyShapes.Chip).background(c.bg), contentAlignment = Alignment.Center) {
                    if (audioOnly) {
                        Icon(Icons.Filled.Headphones, contentDescription = null, tint = c.accent)
                    } else {
                        PlayerSurface(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f))
                    }
                }
                Text(title, style = Troy.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TroyIconButton(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.player_pause else R.string.player_play),
                    vm::toggle,
                )
                TroyIconButton(Icons.Filled.Close, stringResource(R.string.mini_close), vm::close)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 10.dp),
                color = c.accent,
                trackColor = c.controlFill,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
    }
}
