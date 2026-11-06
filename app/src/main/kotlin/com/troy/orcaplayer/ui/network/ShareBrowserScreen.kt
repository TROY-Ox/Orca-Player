package com.troy.orcaplayer.ui.network

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.ErrorState
import com.troy.orcaplayer.core.design.component.LoadingList
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.sources.network.ShareEntry
import com.troy.orcaplayer.sources.network.ShareMedia
import com.troy.orcaplayer.ui.common.ConfirmDialog
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.shell.BackTopBar

/** Folders and media files on one share. Tap plays the folder as a queue; long-press offers Copy to device. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShareBrowserScreen(id: String, onBack: () -> Unit, vm: NetworkViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val state by vm.browse.collectAsStateWithLifecycle()
    val copy by vm.copy.collectAsStateWithLifecycle()
    var copying by remember { mutableStateOf<ShareEntry?>(null) }

    LaunchedEffect(id) { vm.open(id) }
    LaunchedEffect(Unit) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    BackHandler { if (!vm.up()) onBack() }

    val folder = state.path.trim('/').substringAfterLast('/').takeIf { it.isNotEmpty() && state.path != "0" }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(folder ?: state.connection?.name.orEmpty(), onBack = { if (!vm.up()) onBack() }) {
            TroyIconButton(Icons.Filled.Refresh, stringResource(R.string.network_refresh), { vm.refresh() })
        }
        copy?.let { p ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.network_copying, p.name), style = Troy.type.bodySmall, color = c.text2, maxLines = 1)
                if (p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.controlFill)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.controlFill)
            }
        }
        val error = state.error
        when {
            error != null && state.entries.isEmpty() -> ErrorState(
                title = stringResource(R.string.network_error),
                message = error,
                retryLabel = stringResource(R.string.network_retry),
                onRetry = { vm.refresh() },
                modifier = Modifier.weight(1f),
            )
            state.loading && state.entries.isEmpty() -> LoadingList(modifier = Modifier.weight(1f))
            state.entries.isEmpty() -> EmptyState(
                icon = Icons.Filled.FolderOff,
                title = stringResource(R.string.network_folder_empty),
                message = stringResource(R.string.network_folder_empty_message),
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.controlFill) }
                items(state.entries, key = { it.path + "|" + it.name }) { e ->
                    Row(
                        Modifier.fillMaxWidth().troyFocusRing()
                            .combinedClickable(
                                role = Role.Button,
                                onClick = {
                                    if (e.isFolder) vm.openFolder(e)
                                    else vm.request(e)?.let { PlayerLaunch.play(context, it) }
                                },
                                onLongClick = { if (!e.isFolder) copying = e },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            when {
                                e.isFolder -> Icons.Filled.Folder
                                ShareMedia.isAudio(e) -> Icons.Filled.MusicNote
                                else -> Icons.Filled.Movie
                            },
                            contentDescription = null,
                            tint = if (e.isFolder) c.accent else c.text3,
                            modifier = Modifier.size(28.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = Troy.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!e.isFolder && e.size > 0) {
                                Text(Formatter.formatShortFileSize(context, e.size), style = Troy.type.bodySmall, color = c.text2)
                            }
                        }
                        if (!e.isFolder) TroyIconButton(Icons.Filled.Download, stringResource(R.string.network_copy), { copying = e }, tint = c.text2)
                    }
                }
            }
        }
    }
    copying?.let { e ->
        ConfirmDialog(
            title = stringResource(R.string.network_copy_title),
            message = stringResource(R.string.network_copy_message, e.name),
            confirm = stringResource(R.string.network_copy),
            onConfirm = { copying = null; vm.copyToDevice(e) },
            onDismiss = { copying = null },
        )
    }
}
