package com.troy.orcaplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.GlassPanel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.model.SkipKind

@Composable
private fun skipLabel(kind: SkipKind): String = stringResource(
    when (kind) {
        SkipKind.INTRO -> R.string.skip_intro
        SkipKind.RECAP -> R.string.skip_recap
        SkipKind.CREDITS -> R.string.skip_credits
    },
)

/** Skip and Next Episode buttons, bottom end, above the controls (17.6). */
@Composable
fun BoxScope.BingeButtons(
    skip: SkipOffer?,
    next: NextOffer?,
    marking: MarkInProgress?,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onDismissNext: () -> Unit,
    onEndMark: () -> Unit,
    onCancelMark: () -> Unit,
) {
    val c = com.troy.orcaplayer.core.design.theme.Troy.colors
    Column(
        Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 24.dp, bottom = 120.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        marking?.let { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TroyButton(stringResource(R.string.cancel_mark), onCancelMark, kind = ButtonKind.GHOST)
                TroyButton(
                    stringResource(
                        when (m.kind) {
                            SkipKind.INTRO -> R.string.mark_intro_end
                            SkipKind.RECAP -> R.string.mark_recap_end
                            SkipKind.CREDITS -> R.string.mark_credits_end
                        },
                    ),
                    onEndMark, kind = ButtonKind.PRIMARY,
                )
            }
        }
        if (skip != null && next == null) {
            TroyButton(skipLabel(skip.kind), onSkip, icon = Icons.AutoMirrored.Filled.ArrowForward)
        }
        next?.let { n ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (n.secondsLeft != null) TroyButton(stringResource(R.string.next_cancel), onDismissNext, kind = ButtonKind.GHOST)
                Column(horizontalAlignment = Alignment.End) {
                    TroyButton(
                        if (n.secondsLeft != null) stringResource(R.string.next_episode_in, n.secondsLeft) else stringResource(R.string.next_episode),
                        onNext, kind = ButtonKind.PRIMARY, icon = Icons.Filled.SkipNext,
                    )
                    if (n.label.isNotEmpty()) {
                        Text(
                            n.label, style = com.troy.orcaplayer.core.design.theme.Troy.type.label, color = c.text2,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp).padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The Kids daily limit is used up: playback stops until tomorrow, or until a parent unlocks (18). */
@Composable
fun WatchLimitScreen(onClose: () -> Unit, onParent: () -> Unit) {
    val t = com.troy.orcaplayer.core.design.theme.Troy
    Box(
        Modifier.fillMaxSize().background(t.colors.scrim).pointerInput(Unit) { },
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(Modifier.widthIn(max = 420.dp).padding(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = t.colors.accent, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.limit_title), style = t.type.title, color = t.colors.text, textAlign = TextAlign.Center)
                Text(stringResource(R.string.limit_message), style = t.type.body, color = t.colors.text2, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroyButton(stringResource(R.string.limit_close), onClose, kind = ButtonKind.PRIMARY)
                    TroyButton(stringResource(R.string.limit_parent), onParent, kind = ButtonKind.GHOST)
                }
            }
        }
    }
}
