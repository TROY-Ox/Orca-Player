package com.troy.orcaplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.downloads.DownloadsViewModel
import com.troy.orcaplayer.ui.player.PlayerLaunch

/** Finished downloads on Home, newest first; tap plays, the header opens Downloads (16.7). */
@Composable
fun DownloadsRow(onOpenDownloads: () -> Unit, vm: DownloadsViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val all by vm.items.collectAsStateWithLifecycle()
    val profile = vm.profileId
    val done = remember(all, profile) {
        all.filter { it.status == DownloadStatus.COMPLETED && it.profileId == profile }.sortedByDescending { maxOf(it.completedAt, it.createdAt) }.take(20)
    }
    if (done.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().troyFocusRing().clickable(role = Role.Button, onClick = onOpenDownloads).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.downloads_title), style = Troy.type.title, color = c.text, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.home_see_all_downloads), style = Troy.type.bodySmall, color = c.accent)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(done, key = { it.id }) { item ->
                Column(
                    Modifier.width(112.dp).troyFocusRing().clickable(role = Role.Button) {
                        vm.playSeries(item)?.let { PlayerLaunch.play(context, it) }
                    },
                ) {
                    Box(Modifier.size(112.dp, 164.dp).clip(TroyShapes.Control).background(c.controlFill), contentAlignment = Alignment.Center) {
                        if (item.meta.poster != null) WebImage(item.meta.poster, null, Modifier.fillMaxSize())
                        else Icon(Icons.Filled.Movie, contentDescription = null, tint = c.text3)
                    }
                    Text(
                        item.meta.title,
                        style = Troy.type.bodySmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    val sub = item.meta.season?.let { s -> item.meta.episode?.let { e -> "S%02dE%02d".format(s, e) } }
                    if (sub != null) Text(sub, style = Troy.type.bodySmall, color = c.text2, maxLines = 1)
                }
            }
        }
    }
}
