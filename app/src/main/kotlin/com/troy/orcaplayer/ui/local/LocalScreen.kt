package com.troy.orcaplayer.ui.local

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.LoadingList
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.sources.local.LocalFilter
import com.troy.orcaplayer.sources.local.LocalFolder
import com.troy.orcaplayer.sources.local.LocalMedia
import com.troy.orcaplayer.sources.local.LocalSort
import com.troy.orcaplayer.sources.local.LocalSorting
import com.troy.orcaplayer.sources.local.Thumbnails
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.player.timecode
import com.troy.orcaplayer.ui.shell.BackTopBar
import com.troy.orcaplayer.ui.shell.MainTopBar
import kotlinx.coroutines.launch
import com.troy.orcaplayer.core.settings.R as SR
import androidx.compose.material.icons.filled.Download

/** Local tab: folders or all videos, as a list or grid, with sort, filter and search (section 6). */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LocalScreen(
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenUrlPlayer: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenPrivate: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    vm: LocalViewModel = hiltViewModel(),
) {
    val c = Troy.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access by vm.access.collectAsStateWithLifecycle()
    val all by vm.media.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    // File manager (20.4): long press starts selecting; null means "not selecting".
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    val ops: FileOpsViewModel = hiltViewModel()
    var visible by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    fun sel(m: LocalMedia): Boolean? = selection?.contains(m.uri)
    val kids = com.troy.orcaplayer.ui.shell.LocalKidsGuard.current.active
    fun toggle(m: LocalMedia) {
        // Kids profiles can't change files (18).
        if (kids) return
        val now = selection ?: emptySet()
        val next = if (m.uri in now) now - m.uri else now + m.uri
        selection = next.takeIf { it.isNotEmpty() }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshAccess() }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addFolder(uri) }

    val tab = settings[PlayerSettings.LibraryTab]
    val grid = settings[PlayerSettings.LibraryView] == "grid"
    val sort = LocalSort.fromKey(settings[PlayerSettings.LibrarySort])
    val filter = LocalFilter.fromKey(settings[PlayerSettings.LibraryFilter])
    val gridMin = when (settings[PlayerSettings.GridSize]) { "small" -> 120.dp; "large" -> 240.dp; else -> 168.dp }

    BackHandler(enabled = selection != null) { selection = null }
    BackHandler(enabled = folderId != null && selection == null) { folderId = null }

    val play: (List<LocalMedia>, Int) -> Unit = { list, index ->
        scope.launch { PlayerLaunch.play(context, vm.request(list, index)) }
    }

    Column(Modifier.fillMaxSize()) {
        val filtered = all?.let { LocalSorting.filter(it, filter, query) }
        val folderName = folderId?.let { id -> all?.firstOrNull { it.folderId == id }?.folderName }
        val actions: @Composable () -> Unit = {
            TroyIconButton(Icons.Filled.Download, stringResource(R.string.downloads_title), onOpenDownloads)
            TroyIconButton(Icons.Filled.Search, stringResource(R.string.local_search), { searching = !searching; if (!searching) query = "" })
            TroyIconButton(
                if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                stringResource(if (grid) SR.string.view_list else SR.string.view_grid),
                { vm.set(PlayerSettings.LibraryView, if (grid) "list" else "grid") },
            )
            Box {
                TroyIconButton(Icons.AutoMirrored.Filled.Sort, stringResource(SR.string.setting_library_sort), { sortMenu = true })
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, containerColor = c.glassSolid) {
                    listOf(
                        LocalSort.NAME to SR.string.sort_name, LocalSort.DATE to SR.string.sort_date,
                        LocalSort.SIZE to SR.string.sort_size, LocalSort.LENGTH to SR.string.sort_length,
                    ).forEach { (s, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label), color = if (s == sort) c.accent else c.text) },
                            onClick = { vm.set(PlayerSettings.LibrarySort, s.key); sortMenu = false },
                        )
                    }
                }
            }
        }
        val chosen = selection?.let { set -> all.orEmpty().filter { it.uri in set } }
        if (chosen != null) {
            FileSelectionBar(
                chosen = chosen,
                allVisible = visible,
                ops = ops,
                onSelectAll = { selection = visible.map { it.uri }.toSet() },
                onDone = { selection = null },
            )
        } else if (folderId != null) {
            BackTopBar(folderName ?: "", onBack = { folderId = null }) { Row { actions() } }
        } else {
            MainTopBar(stringResource(R.string.tab_local), onOpenSettings, onOpenProfiles) {
                actions()
                TroyIconButton(Icons.Filled.Link, stringResource(R.string.url_player_title), onOpenUrlPlayer)
                TroyIconButton(Icons.Filled.CreateNewFolder, stringResource(R.string.local_add_folder), { runCatching { pickFolder.launch(null) } })
                if (!kids && settings[com.troy.orcaplayer.core.settings.ExtrasSettings.LibraryEnabled]) {
                    TroyIconButton(Icons.Filled.VideoLibrary, stringResource(R.string.library_title), onOpenLibrary)
                }
                if (!kids) TroyIconButton(Icons.Filled.Lan, stringResource(R.string.network_title), onOpenNetwork)
                if (!kids) TroyIconButton(Icons.Filled.Lock, stringResource(R.string.vault_title), onOpenPrivate)
            }
        }
        FileOpsHost(ops)
        if (searching) {
            TroyTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = stringResource(R.string.local_search_hint),
                leading = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.text3) },
            )
        }

        if (access == MediaAccess.NONE && all.isNullOrEmpty()) {
            EmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = stringResource(R.string.local_permission_title),
                message = stringResource(R.string.local_permission_message),
                modifier = Modifier.weight(1f),
                actionLabel = stringResource(R.string.local_permission_button),
                onAction = { permissions.launch(LocalViewModel.permissions()) },
            )
            return@Column
        }
        if (access == MediaAccess.PARTIAL) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TroyAlert(stringResource(R.string.local_partial_access), AlertKind.INFO, Modifier.weight(1f))
                TroyButton(stringResource(R.string.local_allow_more), { permissions.launch(LocalViewModel.permissions()) }, kind = ButtonKind.GHOST)
            }
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (folderId == null) {
                TroyChip(stringResource(SR.string.library_folders), tab == "folders", { vm.set(PlayerSettings.LibraryTab, "folders") })
                TroyChip(stringResource(SR.string.library_all), tab == "all", { vm.set(PlayerSettings.LibraryTab, "all") })
            }
            TroyChip(stringResource(SR.string.filter_video), filter == LocalFilter.VIDEO, { vm.set(PlayerSettings.LibraryFilter, "video") })
            TroyChip(stringResource(SR.string.filter_audio), filter == LocalFilter.AUDIO, { vm.set(PlayerSettings.LibraryFilter, "audio") })
            TroyChip(stringResource(SR.string.filter_all), filter == LocalFilter.ALL, { vm.set(PlayerSettings.LibraryFilter, "all") })
        }

        when {
            filtered == null -> LoadingList(modifier = Modifier.weight(1f))
            filtered.isEmpty() -> EmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = stringResource(if (query.isBlank()) R.string.local_empty_title else R.string.local_no_results),
                message = stringResource(if (query.isBlank()) R.string.local_empty_message else R.string.local_no_results_message),
                modifier = Modifier.weight(1f),
            )
            folderId == null && tab == "folders" && query.isBlank() -> {
                val folders = LocalSorting.folders(filtered, sort)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(folders, key = { it.id }) { f -> FolderRow(f, vm.thumbnails) { folderId = f.id } }
                }
            }
            else -> {
                val list = LocalSorting.sort(if (folderId != null) filtered.filter { it.folderId == folderId } else filtered, sort)
                visible = list
                if (grid) {
                    LazyVerticalGrid(
                        GridCells.Adaptive(gridMin),
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(list, key = { _, m -> m.uri }) { i, m ->
                            MediaCard(m, progress[m.uri], vm.thumbnails, sel(m), { toggle(m) }) { if (selection != null) toggle(m) else play(list, i) }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                        itemsIndexed(list, key = { _, m -> m.uri }) { i, m ->
                            MediaRow(m, progress[m.uri], vm.thumbnails, sel(m), { toggle(m) }) { if (selection != null) toggle(m) else play(list, i) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberThumb(media: LocalMedia?, thumbnails: Thumbnails): Bitmap? {
    if (media == null) return null
    val state by produceState(thumbnails.cached(media), media.uri) { if (value == null) value = thumbnails.load(media) }
    return state
}

@Composable
private fun Thumb(media: LocalMedia?, thumbnails: Thumbnails, progress: Progress?, modifier: Modifier) {
    val c = Troy.colors
    val bmp = rememberThumb(media, thumbnails)
    Box(modifier.clip(TroyShapes.Control).background(c.controlFill)) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                if (media?.isAudio == true) Icons.Filled.MusicNote else Icons.Filled.VideoLibrary,
                contentDescription = null,
                tint = c.text3,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        if (media != null) {
            if (media.durationMs > 0) {
                Text(
                    timecode(media.durationMs),
                    style = Troy.type.label,
                    color = c.text,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(c.scrim, TroyShapes.Pill).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            media.resolutionBadge?.let {
                Text(
                    it,
                    style = Troy.type.label,
                    color = c.text,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(c.scrim, TroyShapes.Pill).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        if (progress != null && progress.fraction > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(c.scrim)) {
                Box(Modifier.fillMaxWidth(progress.fraction).height(3.dp).background(c.accent))
            }
        }
    }
}

@Composable
private fun SelectMark(selected: Boolean) {
    val c = Troy.colors
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
        contentDescription = stringResource(if (selected) R.string.files_selected else R.string.files_not_selected),
        tint = if (selected) c.accent else c.text3,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun NewDot() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Troy.colors.accent))
}

@Composable
private fun MediaRow(m: LocalMedia, progress: Progress?, thumbnails: Thumbnails, selected: Boolean?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().troyFocusRing()
            .background(if (selected == true) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected != null) SelectMark(selected)
        Thumb(m, thumbnails, progress, Modifier.width(128.dp).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (progress == null) NewDot()
                Text(
                    m.name,
                    style = Troy.type.body.copy(textDirection = TextDirection.Content),
                    color = c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(Formatter.formatShortFileSize(context, m.sizeBytes) + " · " + m.folderName, style = Troy.type.bodySmall, color = c.text2, maxLines = 1)
        }
    }
}

@Composable
private fun MediaCard(m: LocalMedia, progress: Progress?, thumbnails: Thumbnails, selected: Boolean?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val c = Troy.colors
    Column(
        Modifier.troyFocusRing()
            .background(if (selected == true) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent, TroyShapes.Control)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Thumb(m, thumbnails, progress, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            if (selected != null) Box(Modifier.padding(6.dp)) { SelectMark(selected) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (progress == null) NewDot()
            Text(
                m.name,
                style = Troy.type.bodySmall.copy(textDirection = TextDirection.Content),
                color = c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FolderRow(f: LocalFolder, thumbnails: Thumbnails, onClick: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().troyFocusRing().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(96.dp).aspectRatio(16f / 9f)) {
            Thumb(f.cover, thumbnails, null, Modifier.fillMaxSize())
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = c.text,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.name, style = Troy.type.body.copy(textDirection = TextDirection.Content), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.local_folder_count, formatNumber(f.count)) + " · " + Formatter.formatShortFileSize(context, f.totalBytes),
                style = Troy.type.bodySmall,
                color = c.text2,
            )
        }
    }
}
