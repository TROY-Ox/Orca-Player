package com.troy.orcaplayer.ui.links

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.data.SavedLink
import com.troy.orcaplayer.sources.api.PlaylistEntry
import com.troy.orcaplayer.ui.addons.clipboardText
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.shell.BackTopBar

private data class SaveDraft(val input: LinkInput, val name: String, val folderId: String?, val existingId: String?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UrlPlayerScreen(onBack: () -> Unit, onInstallAddon: (String) -> Unit, initialUrl: String? = null, vm: UrlPlayerViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val data by vm.data.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var url by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var more by rememberSaveable { mutableStateOf(false) }
    var referer by rememberSaveable { mutableStateOf("") }
    var agent by rememberSaveable { mutableStateOf("") }
    var headers by rememberSaveable { mutableStateOf("") }
    var subtitle by rememberSaveable { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<SaveDraft?>(null) }
    var playlist by remember { mutableStateOf<UrlEvent.PlaylistFound?>(null) }
    var folderDialog by remember { mutableStateOf<Pair<String?, String>?>(null) } // id (null = new), name
    var openFolders by rememberSaveable { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var exportFolder by remember { mutableStateOf<String?>(null) }

    fun input() = LinkInput(url, null, referer, agent, headers, subtitle)
    val downloadsVm: com.troy.orcaplayer.ui.downloads.DownloadsViewModel = hiltViewModel()
    val guard = com.troy.orcaplayer.ui.downloads.rememberDownloadGuard(downloadsVm)
    var toDownload by remember { mutableStateOf<com.troy.orcaplayer.core.model.PlayItem?>(null) }
    toDownload?.let { item ->
        com.troy.orcaplayer.ui.downloads.DownloadOptionsDialog(
            vm = downloadsVm, name = item.title, url = item.uri, headers = item.headers,
            subtitles = { item.subtitles }, preferredLanguage = null,
            onDismiss = { toDownload = null },
            onConfirm = { choice ->
                downloadsVm.downloadItem(item.copy(subtitles = choice.subtitles), choice.kind, choice.tracks, choice.startNow)
                android.widget.Toast.makeText(context, R.string.dl_queued, android.widget.Toast.LENGTH_SHORT).show()
                toDownload = null
            },
        )
    }

    LaunchedEffect(event) {
        when (val e = event) {
            is UrlEvent.Play -> context.startActivity(PlayerLaunch.intent(context, e.request, e.options))
            is UrlEvent.Install -> onInstallAddon(e.url)
            is UrlEvent.PlaylistFound -> playlist = e
            UrlEvent.Bad -> bad = true
            null -> Unit
        }
        if (event != null) vm.consume()
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Playlist"
        val n = vm.importText(name, text)
        Toast.makeText(context, context.resources.getQuantityString(R.plurals.links_imported, n, n), Toast.LENGTH_SHORT).show()
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportM3u(exportFolder).toByteArray()) } }
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.url_player_title), onBack) {
            androidx.compose.foundation.layout.Box {
                TroyIconButton(Icons.Outlined.MoreVert, stringResource(R.string.more_options), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.links_import_m3u), color = c.text) }, onClick = {
                        menu = false
                        importer.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "text/*", "*/*"))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.links_export_m3u), color = c.text) }, onClick = {
                        menu = false
                        exportFolder = null
                        exporter.launch("orca-links.m3u")
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.links_new_folder), color = c.text) }, onClick = { menu = false; folderDialog = null to "" })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "form") {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TroyTextField(url, { url = it; bad = false }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.url_hint), imeAction = ImeAction.Go, onImeAction = { vm.play(input()) })
                    if (bad) Text(stringResource(R.string.url_bad), style = Troy.type.bodySmall, color = c.danger)
                    TroyChip(stringResource(if (more) R.string.links_fewer_options else R.string.links_more_options), more, { more = !more })
                    if (more) {
                        TroyTextField(referer, { referer = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.url_referer))
                        TroyTextField(agent, { agent = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.url_agent))
                        TroyTextField(headers, { headers = it }, Modifier.fillMaxWidth().heightIn(min = 72.dp), placeholder = stringResource(R.string.links_headers_hint), singleLine = false)
                        TroyTextField(subtitle, { subtitle = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.links_subtitle_hint))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyButton(stringResource(R.string.paste), { clipboardText(context)?.let { url = it.trim(); bad = false } }, icon = Icons.Outlined.ContentPaste)
                        TroyButton(stringResource(R.string.links_save), { draft = SaveDraft(input(), "", null, null) }, enabled = url.isNotBlank())
                        TroyButton(stringResource(R.string.links_queue_add), { vm.enqueue(input()); url = "" }, enabled = url.isNotBlank())
                        TroyButton(stringResource(R.string.dl_download), {
                            val item = vm.downloadable(input())
                            if (item == null) bad = true else guard(item.source, drm = false, downloadable = true) { toDownload = item }
                        }, enabled = url.isNotBlank() && !busy)
                        TroyButton(stringResource(R.string.player_play), { vm.play(input()) }, kind = ButtonKind.PRIMARY, enabled = url.isNotBlank() && !busy)
                    }
                    if (busy) Text(stringResource(R.string.links_checking), style = Troy.type.bodySmall, color = c.text2)
                }
            }
            if (queue.isNotEmpty()) {
                item(key = "queue") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.links_queue, queue.size), Modifier.weight(1f))
                        TroyButton(stringResource(R.string.clear), vm::clearQueue, kind = ButtonKind.GHOST)
                        TroyButton(stringResource(R.string.links_play_all), vm::playQueue, kind = ButtonKind.GHOST)
                    }
                }
                itemsIndexed(queue, key = { i, q -> "q$i" + q.url }) { i, q ->
                    LinkRow(q.name ?: q.url, q.url, Icons.Outlined.Link, onClick = {}) {
                        TroyIconButton(Icons.Outlined.Close, stringResource(R.string.remove), { vm.removeFromQueue(i) })
                    }
                }
            }
            item(key = "savedLabel") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.links_saved), Modifier.weight(1f))
                    TroyIconButton(Icons.Outlined.CreateNewFolder, stringResource(R.string.links_new_folder), { folderDialog = null to "" })
                }
            }
            if (data.saved.isEmpty() && data.folders.isEmpty()) {
                item(key = "savedEmpty") { Text(stringResource(R.string.links_saved_empty), style = Troy.type.bodySmall, color = c.text3) }
            }
            data.folders.forEachIndexed { fi, f ->
                item(key = "f" + f.id) {
                    val open = f.id in openFolders
                    val count = data.saved.count { it.folderId == f.id }
                    LinkRow(f.name, stringResource(R.string.links_folder_count, count), Icons.Outlined.Folder, onClick = {
                        openFolders = if (open) openFolders - f.id else openFolders + f.id
                    }) {
                        FolderMenu(
                            canUp = fi > 0, canDown = fi < data.folders.lastIndex,
                            onUp = { vm.moveFolder(f.id, -1) }, onDown = { vm.moveFolder(f.id, 1) },
                            onRename = { folderDialog = f.id to f.name },
                            onExport = { exportFolder = f.id; exporter.launch("${f.name}.m3u") },
                            onPlayAll = { data.saved.filter { it.folderId == f.id }.takeIf { it.isNotEmpty() }?.let { vm.playEntries(it.map { l -> PlaylistEntry(l.url, l.name, headers = l.headers) }, 0) } },
                            onDelete = { vm.deleteFolder(f.id) },
                        )
                    }
                }
                if (f.id in openFolders) {
                    val inFolder = data.saved.filter { it.folderId == f.id }
                    itemsIndexed(inFolder, key = { _, l -> "l" + l.id }) { i, l ->
                        SavedRow(l, i > 0, i < inFolder.lastIndex, Modifier.padding(start = 24.dp), vm, onEdit = { draft = SaveDraft(l.toInput(), l.name, l.folderId, l.id) })
                    }
                }
            }
            val loose = data.saved.filter { it.folderId == null || data.folders.none { f -> f.id == it.folderId } }
            itemsIndexed(loose, key = { _, l -> "l" + l.id }) { i, l ->
                SavedRow(l, i > 0, i < loose.lastIndex, Modifier, vm, onEdit = { draft = SaveDraft(l.toInput(), l.name, l.folderId, l.id) })
            }
            if (data.history.isNotEmpty()) {
                item(key = "histLabel") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.links_history), Modifier.weight(1f))
                        TroyButton(stringResource(R.string.clear), vm::clearHistory, kind = ButtonKind.GHOST)
                    }
                }
                items(data.history, key = { "h" + it.url }) { h ->
                    LinkRow(h.title ?: h.url, h.url, Icons.Outlined.History, onClick = { vm.playHistory(h) }) {
                        TroyIconButton(Icons.Outlined.Close, stringResource(R.string.remove), { vm.removeHistory(h.url) })
                    }
                }
            }
        }
    }

    draft?.let { d ->
        var name by remember(d) { mutableStateOf(d.name) }
        var folder by remember(d) { mutableStateOf(d.folderId) }
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text(stringResource(R.string.links_save), style = Troy.type.title, color = c.text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TroyTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.links_name_hint))
                    Text(d.input.url, style = Troy.type.mono, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SectionLabel(stringResource(R.string.links_folder))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyChip(stringResource(R.string.links_no_folder), folder == null, { folder = null })
                        data.folders.forEach { f -> TroyChip(f.name, folder == f.id, { folder = f.id }) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.save(d.input, name, folder, d.existingId); draft = null }) { Text(stringResource(R.string.links_save), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
    }

    folderDialog?.let { (id, initial) ->
        var name by remember(id) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { folderDialog = null },
            title = { Text(stringResource(if (id == null) R.string.links_new_folder else R.string.rename), style = Troy.type.title, color = c.text) },
            text = { TroyTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.links_folder_name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) { if (id == null) vm.addFolder(name.trim()) else vm.renameFolder(id, name.trim()) }
                    folderDialog = null
                }) { Text(stringResource(R.string.ok), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { folderDialog = null }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
    }

    playlist?.let { p ->
        AlertDialog(
            onDismissRequest = { playlist = null },
            title = { Text(stringResource(R.string.links_playlist_title), style = Troy.type.title, color = c.text) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(context.resources.getQuantityString(R.plurals.links_playlist_entries, p.entries.size, p.entries.size), style = Troy.type.body, color = c.text2)
                    p.entries.take(50).forEachIndexed { i, e ->
                        Text(
                            e.name ?: e.url, style = Troy.type.bodySmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().troyFocusRing().clip(TroyShapes.Chip).clickable(role = Role.Button) { playlist = null; vm.playEntries(p.entries, i) }.padding(6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        vm.importEntries(p.url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Playlist" }, p.entries)
                        playlist = null
                    }) { Text(stringResource(R.string.links_import), color = c.text) }
                    TextButton(onClick = { playlist = null; vm.playEntries(p.entries, 0) }) { Text(stringResource(R.string.links_play_all), color = c.accent) }
                }
            },
            dismissButton = { TextButton(onClick = { playlist = null }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
    }
}

@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val c = Troy.colors
    Row(
        modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).troyFocusRing()
            .clickable(role = Role.Button, onClick = onClick).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Troy.type.bodySmall, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun SavedRow(l: SavedLink, canUp: Boolean, canDown: Boolean, modifier: Modifier, vm: UrlPlayerViewModel, onEdit: () -> Unit) {
    LinkRow(l.name, l.url, Icons.Outlined.Link, onClick = { vm.playSaved(l) }, modifier = modifier) {
        if (canUp) TroyIconButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up), { vm.move(l.id, -1) })
        if (canDown) TroyIconButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down), { vm.move(l.id, 1) })
        TroyIconButton(Icons.Outlined.Edit, stringResource(R.string.edit), onEdit)
        TroyIconButton(Icons.Outlined.Delete, stringResource(R.string.remove), { vm.delete(l.id) })
    }
}

@Composable
private fun FolderMenu(
    canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit,
    onRename: () -> Unit, onExport: () -> Unit, onPlayAll: () -> Unit, onDelete: () -> Unit,
) {
    val c = Troy.colors
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TroyIconButton(Icons.Outlined.MoreVert, stringResource(R.string.more_options), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.links_play_all), color = c.text) }, onClick = { open = false; onPlayAll() })
            if (canUp) DropdownMenuItem(text = { Text(stringResource(R.string.move_up), color = c.text) }, onClick = { open = false; onUp() })
            if (canDown) DropdownMenuItem(text = { Text(stringResource(R.string.move_down), color = c.text) }, onClick = { open = false; onDown() })
            DropdownMenuItem(text = { Text(stringResource(R.string.rename), color = c.text) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.links_export_m3u), color = c.text) }, onClick = { open = false; onExport() })
            DropdownMenuItem(text = { Text(stringResource(R.string.links_delete_folder), color = c.danger) }, onClick = { open = false; onDelete() })
        }
    }
}
