package com.troy.orcaplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.ui.common.WebImage

/**
 * Audio-only mode (20.3): artwork and titles in place of the picture. The player's own controls,
 * queue, shuffle, repeat and sleep timer stay on top; the notification and lock screen control it
 * while the screen is off.
 */
@Composable
fun AudioOnlyView(
    title: String,
    subtitle: String?,
    poster: String?,
    index: Int,
    count: Int,
    onQueue: () -> Unit,
    onExit: () -> Unit,
) {
    val c = Troy.colors
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val art = Modifier.fillMaxWidth(0.6f).aspectRatio(1f).clip(RoundedCornerShape(18.dp))
            if (poster != null) {
                WebImage(poster, null, art)
            } else {
                Box(art.background(c.controlFill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Headphones, contentDescription = null, tint = c.accent, modifier = Modifier.size(72.dp))
                }
            }
            Text(title, style = Troy.type.title, color = c.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = Troy.type.body, color = c.text2, textAlign = TextAlign.Center, maxLines = 1)
            if (count > 1) Text(stringResource(R.string.audio_only_position, formatNumber(index + 1), formatNumber(count)), style = Troy.type.label, color = c.text3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyButton(stringResource(R.string.player_queue), onQueue, kind = ButtonKind.SECONDARY, icon = Icons.AutoMirrored.Filled.QueueMusic)
                TroyButton(stringResource(R.string.audio_only_exit), onExit, kind = ButtonKind.GHOST, icon = Icons.Filled.Videocam)
            }
        }
    }
}
