package com.troy.orcaplayer.ui.live

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.data.LiveChannel
import com.troy.orcaplayer.data.LiveTvRepository
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.shell.MainTopBar

/** The Live TV tab: channels from the user's own M3U/IPTV playlists, grouped and searchable. */
@Composable
fun LiveTvScreen(
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    vm: LiveTvViewModel = hiltViewModel(),
) {
    val c = Troy.colors
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }

    val play: (LiveChannel) -> Unit = { ch -> vm.play(ch)?.let { PlayerLaunch.play(context, it) } }

    Column(Modifier.fillMaxSize()) {
        MainTopBar(stringResource(R.string.tab_live), onOpenSettings, onOpenProfiles) {
            if (ui.playlists.isNotEmpty()) {
                TroyIconButton(Icons.Outlined.Refresh, stringResource(R.string.live_refresh), vm::refreshAll)
                TroyIconButton(Icons.Outlined.Tune, stringResource(R.string.live_manage), { managing = true })
            }
            TroyIconButton(Icons.Outlined.Add, stringResource(R.string.live_add), { adding = true })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = c.accent, trackColor = c.controlFill)
        message?.let { m ->
            Text(
                stringResource(R.string.live_problem, m),
                style = Troy.type.bodySmall,
                color = c.danger,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).clickable { vm.dismissMessage() },
            )
        }

        if (ui.playlists.isEmpty()) {
            LiveEmpty(onAdd = { adding = true }, onSuggestion = { s -> vm.add(s.name, s.url) }, busy = busy)
        } else {
            TroyTextField(
                filter.query, { q -> vm.filter.value = filter.copy(query = q) },
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                placeholder = stringResource(R.string.live_search_hint, formatNumber(ui.total)),
                imeAction = ImeAction.Search,
                leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = c.text3) },
            )
            if (ui.playlists.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { TroyChip(stringResource(R.string.live_all_lists), filter.playlistId == null, { vm.filter.value = filter.copy(playlistId = null, group = null) }) }
                    items(ui.playlists, key = { it.id }) { p ->
                        TroyChip(p.name, filter.playlistId == p.id, { vm.filter.value = filter.copy(playlistId = p.id, group = null) })
                    }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { TroyChip(stringResource(R.string.filter_all), filter.group == null, { vm.filter.value = filter.copy(group = null) }) }
                item { TroyChip(stringResource(R.string.live_favorites), filter.group == GROUP_FAVORITES, { vm.filter.value = filter.copy(group = GROUP_FAVORITES) }) }
                item { TroyChip(stringResource(R.string.live_recent), filter.group == GROUP_RECENT, { vm.filter.value = filter.copy(group = GROUP_RECENT) }) }
                items(ui.groups, key = { "g$it" }) { g -> TroyChip(g, filter.group == g, { vm.filter.value = filter.copy(group = g) }) }
            }
            if (ui.channels.isEmpty()) {
                Text(
                    stringResource(if (filter.group == GROUP_FAVORITES) R.string.live_no_favorites else R.string.live_no_channels),
                    style = Troy.type.body, color = c.text2, modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            ) {
                items(ui.channels, key = { it.playlistId + "|" + it.url }) { ch ->
                    ChannelRow(ch, ch.url in ui.favorites, onPlay = { play(ch) }, onFavorite = { vm.toggleFavorite(ch) })
                }
            }
        }
    }

    if (adding) AddPlaylistDialog(onDismiss = { adding = false }, onAdd = { name, url, ua -> adding = false; vm.add(name, url, ua) })
    if (managing) ManageDialog(vm, onDismiss = { managing = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(ch: LiveChannel, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit) {
    val c = Troy.colors
    Row(
        Modifier
            .fillMaxWidth()
            .troyFocusRing()
            .clip(TroyShapes.Control)
            .combinedClickable(role = Role.Button, onClick = onPlay, onLongClick = onFavorite)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 40.dp).clip(TroyShapes.Chip).background(c.controlFill), contentAlignment = Alignment.Center) {
            if (ch.entry.logo.isNullOrBlank()) {
                Icon(Icons.Outlined.LiveTv, contentDescription = null, tint = c.text3, modifier = Modifier.size(22.dp))
            } else {
                WebImage(ch.entry.logo, null, Modifier.fillMaxSize().padding(4.dp), scale = ContentScale.Fit)
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ch.group?.let { Text(it, style = Troy.type.bodySmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        TroyIconButton(
            if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            stringResource(if (favorite) R.string.live_unfavorite else R.string.live_favorite),
            onFavorite,
            tint = if (favorite) c.warn else c.text3,
        )
    }
}

@Composable
private fun LiveEmpty(onAdd: () -> Unit, onSuggestion: (com.troy.orcaplayer.data.SuggestedPlaylist) -> Unit, busy: Boolean) {
    val c = Troy.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                EmptyState(
                    Icons.Outlined.LiveTv,
                    stringResource(R.string.live_empty_title),
                    stringResource(R.string.live_empty_message),
                    actionLabel = stringResource(R.string.live_add),
                    onAction = onAdd,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.live_suggestions))
                Text(stringResource(R.string.live_suggestions_note), style = Troy.type.bodySmall, color = c.text3)
                LiveTvRepository.Suggestions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().troyFocusRing().clip(TroyShapes.Control).background(c.controlFill)
                            .clickable(enabled = !busy, role = Role.Button) { onSuggestion(s) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.LiveTv, contentDescription = null, tint = c.accent)
                        Spacer(Modifier.size(12.dp))
                        Text(s.name, style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.live_add), tint = c.text2)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPlaylistDialog(onDismiss: () -> Unit, onAdd: (String, String, String?) -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var ua by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            url = uri.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.live_add_title), style = Troy.type.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.live_add_message), style = Troy.type.bodySmall, color = c.text2)
                TroyTextField(url, { url = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.live_url_hint), keyboardType = KeyboardType.Uri)
                TroyTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.live_name_hint))
                TroyTextField(ua, { ua = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.live_ua_hint))
                TroyButton(stringResource(R.string.live_pick_file), { pick.launch(arrayOf("*/*")) }, icon = Icons.Outlined.FolderOpen, kind = ButtonKind.GHOST)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url, ua) }, enabled = url.isNotBlank()) { Text(stringResource(R.string.live_add), color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        containerColor = Troy.colors.glassSolid,
    )
}

@Composable
private fun ManageDialog(vm: LiveTvViewModel, onDismiss: () -> Unit) {
    val c = Troy.colors
    val data by vm.data.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.live_manage), style = Troy.type.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                data.playlists.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.live_channel_count, formatNumber(p.channelCount)),
                                style = Troy.type.bodySmall, color = c.text3,
                            )
                        }
                        TroyIconButton(Icons.Outlined.Delete, stringResource(R.string.live_remove), { vm.remove(p.id) }, tint = c.danger)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.live_done), color = c.accent) } },
        containerColor = Troy.colors.glassSolid,
    )
}
