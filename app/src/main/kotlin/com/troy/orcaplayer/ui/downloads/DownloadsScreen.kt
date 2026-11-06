package com.troy.orcaplayer.ui.downloads

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.downloads.Sinks
import com.troy.orcaplayer.downloads.logic.DownloadError
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadPlace
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import com.troy.orcaplayer.downloads.logic.Format
import com.troy.orcaplayer.downloads.logic.WaitReason
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.shell.BackTopBar

/** Downloads (16.7): Active, Completed and Failed, with the queue's controls and a storage bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(initialTab: String?, onBack: () -> Unit, onPlay: (PlayRequest) -> Unit, vm: DownloadsViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(runCatching { DownloadsTab.valueOf(initialTab ?: "") }.getOrDefault(DownloadsTab.ACTIVE)) }
    var sort by rememberSaveable { mutableStateOf(DownloadSort.NEWEST) }
    var grouped by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<DownloadItem?>(null) }

    val active = items.filter { it.active || it.status == DownloadStatus.PAUSED }.sortedWith(compareBy({ it.order }, { it.createdAt }))
    val completed = items.filter { it.status == DownloadStatus.COMPLETED }.let { list ->
        when (sort) {
            DownloadSort.NEWEST -> list.sortedByDescending { it.completedAt }
            DownloadSort.NAME -> list.sortedBy { it.fileName.lowercase() }
            DownloadSort.SIZE -> list.sortedByDescending { it.totalBytes }
        }
    }
    val failed = items.filter { it.status == DownloadStatus.FAILED }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.downloads_title), onBack) {
            if (active.any { it.status != DownloadStatus.PAUSED }) TroyIconButton(Icons.Filled.Pause, stringResource(R.string.dl_pause_all_short), vm::pauseAll)
            if (active.any { it.status == DownloadStatus.PAUSED }) TroyIconButton(Icons.Filled.PlayArrow, stringResource(R.string.dl_resume_all_short), vm::resumeAll)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { StorageBar(vm) }
            item {
                FlowRow(Modifier.widthIn(max = 720.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroyChip(stringResource(R.string.dl_tab_active, active.size), tab == DownloadsTab.ACTIVE, { tab = DownloadsTab.ACTIVE })
                    TroyChip(stringResource(R.string.dl_tab_completed, completed.size), tab == DownloadsTab.COMPLETED, { tab = DownloadsTab.COMPLETED })
                    TroyChip(stringResource(R.string.dl_tab_failed, failed.size), tab == DownloadsTab.FAILED, { tab = DownloadsTab.FAILED })
                }
            }
            if (tab == DownloadsTab.COMPLETED && completed.isNotEmpty()) {
                item {
                    FlowRow(Modifier.widthIn(max = 720.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyChip(stringResource(R.string.dl_group_series), grouped, { grouped = !grouped })
                        TroyChip(stringResource(R.string.dl_sort_newest), sort == DownloadSort.NEWEST, { sort = DownloadSort.NEWEST })
                        TroyChip(stringResource(R.string.dl_sort_name), sort == DownloadSort.NAME, { sort = DownloadSort.NAME })
                        TroyChip(stringResource(R.string.dl_sort_size), sort == DownloadSort.SIZE, { sort = DownloadSort.SIZE })
                    }
                }
            }
            val list = when (tab) {
                DownloadsTab.ACTIVE -> active
                DownloadsTab.COMPLETED -> completed
                DownloadsTab.FAILED -> failed
            }
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Filled.Download,
                        stringResource(R.string.dl_empty_title),
                        stringResource(if (tab == DownloadsTab.FAILED) R.string.dl_empty_failed else R.string.dl_empty_message),
                        Modifier.height(320.dp),
                    )
                }
            } else if (tab == DownloadsTab.COMPLETED && grouped) {
                val groups = list.groupBy { it.seriesKey }
                groups.forEach { (key, group) ->
                    if (key != null) item(key = "h:$key") {
                        SectionLabel(group.first().meta.title, Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 6.dp))
                    }
                    val sorted = if (key != null) group.sortedWith(compareBy({ it.meta.season ?: 0 }, { it.meta.episode ?: 0 })) else group
                    items(sorted, key = { it.id }) { item ->
                        DownloadRow(item, live[item.id]?.speed ?: 0, vm, context, onPlay, onDelete = { confirmDelete = it })
                    }
                }
            } else {
                items(list, key = { it.id }) { item ->
                    val index = active.indexOf(item)
                    DownloadRow(
                        item, live[item.id]?.speed ?: 0, vm, context, onPlay, onDelete = { confirmDelete = it },
                        reorder = if (tab == DownloadsTab.ACTIVE) Reorder(index, active.size) { to -> vm.move(item.id, to) } else null,
                    )
                }
            }
        }
    }

    confirmDelete?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.dl_delete_title), style = Troy.type.title, color = c.text) },
            text = { Text(item.fileName, style = Troy.type.body, color = c.text2) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.delete(item.id); confirmDelete = null }) { Text(stringResource(R.string.dl_delete_confirm), color = c.danger) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
    }
}

private data class Reorder(val index: Int, val count: Int, val onMove: (Int) -> Unit)

@Composable
private fun StorageBar(vm: DownloadsViewModel) {
    val c = Troy.colors
    val context = LocalContext.current
    val used = remember(vm.items.collectAsStateWithLifecycle().value.size) { vm.bytesUsed() }
    val (free, total) = remember(used) { Sinks.freeBytesTotal(context) }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dl_storage, Format.bytes(used), Format.bytes(free)), style = Troy.type.bodySmall, color = c.text2)
        if (total > 0) {
            val usedOther = (total - free - used).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth().height(6.dp).clip(TroyShapes.Pill).background(c.controlFill)) {
                val fDownloads = (used.toFloat() / total).coerceIn(0f, 1f)
                val fOther = (usedOther.toFloat() / total).coerceIn(0f, 1f - fDownloads)
                if (fDownloads > 0f) Box(Modifier.weight(fDownloads).fillMaxHeight().background(c.accent))
                if (fOther > 0f) Box(Modifier.weight(fOther).fillMaxHeight().background(c.text3))
                val rest = 1f - fDownloads - fOther
                if (rest > 0f) Box(Modifier.weight(rest).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    speed: Long,
    vm: DownloadsViewModel,
    context: Context,
    onPlay: (PlayRequest) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    reorder: Reorder? = null,
) {
    val c = Troy.colors
    var menu by remember { mutableStateOf(false) }
    val done = item.status == DownloadStatus.COMPLETED
    Row(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).troyFocusRing()
            .clickable(role = Role.Button, enabled = done) { vm.playSeries(item)?.let(onPlay) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp, 76.dp).clip(TroyShapes.Control).background(c.controlFill), contentAlignment = Alignment.Center) {
            if (item.meta.poster != null) WebImage(item.meta.poster, null, Modifier.fillMaxSize())
            else Icon(Icons.Filled.Movie, null, tint = c.text3)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.fileName.substringBeforeLast('.'), style = Troy.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(statusLine(item, speed), style = Troy.type.bodySmall, color = if (item.status == DownloadStatus.FAILED || item.error == DownloadError.NO_SPACE) c.danger else c.text2, maxLines = 2)
            if (!done && item.status != DownloadStatus.FAILED) {
                val f = item.fraction
                Box(Modifier.fillMaxWidth().height(4.dp).clip(TroyShapes.Pill).background(c.controlFill)) {
                    if (f != null) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(if (item.status == DownloadStatus.RUNNING) c.accent else c.text3))
                }
            }
        }
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING -> {
                if (item.status == DownloadStatus.WAITING && (item.waitReason == WaitReason.SCHEDULE || item.waitReason == WaitReason.CHARGING)) {
                    TroyIconButton(Icons.Filled.SkipNext, stringResource(R.string.dl_start_now), { vm.startNow(item.id) })
                }
                TroyIconButton(Icons.Filled.Pause, stringResource(R.string.dl_pause), { vm.pause(item.id) })
                TroyIconButton(Icons.Filled.Close, stringResource(R.string.dl_cancel), { onDelete(item) })
            }
            DownloadStatus.PAUSED -> {
                TroyIconButton(Icons.Filled.PlayArrow, stringResource(R.string.dl_resume), { vm.resume(item.id) })
                TroyIconButton(Icons.Filled.Close, stringResource(R.string.dl_cancel), { onDelete(item) })
            }
            DownloadStatus.FAILED -> {
                TroyIconButton(Icons.Filled.Refresh, stringResource(R.string.dl_retry), { vm.retry(item.id) })
                TroyIconButton(Icons.Filled.Delete, stringResource(R.string.dl_delete_confirm), { onDelete(item) })
            }
            DownloadStatus.COMPLETED -> {
                TroyIconButton(Icons.Filled.PlayArrow, stringResource(R.string.dl_play), { vm.playSeries(item)?.let(onPlay) })
                Box {
                    TroyIconButton(Icons.Filled.MoreVert, stringResource(R.string.dl_more), { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        if (canOpenFolder(item)) MenuRow(Icons.Filled.Folder, stringResource(R.string.dl_open_folder)) { menu = false; openFolder(context, item) }
                        if (canShare(item)) MenuRow(Icons.Filled.Share, stringResource(R.string.dl_share)) { menu = false; share(context, item) }
                        if (!item.isStream && item.outputUri != null) MenuRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.dl_open_with)) { menu = false; openWith(context, item) }
                        if (!item.isStream && item.place != DownloadPlace.PRIVATE) MenuRow(Icons.Filled.Lock, stringResource(R.string.dl_move_private)) {
                            menu = false
                            vm.moveToPrivate(item.id) { ok -> Toast.makeText(context, if (ok) R.string.dl_moved_private else R.string.dl_move_failed, Toast.LENGTH_SHORT).show() }
                        }
                        if (item.isStream) MenuRow(Icons.Filled.Movie, stringResource(R.string.dl_export_mp4)) {
                            menu = false
                            if (vm.exportMp4(item)) Toast.makeText(context, R.string.dl_export_started, Toast.LENGTH_SHORT).show()
                        }
                        MenuRow(Icons.Filled.Refresh, stringResource(R.string.dl_again)) { menu = false; vm.again(item.id) }
                        MenuRow(Icons.Filled.Delete, stringResource(R.string.dl_delete_confirm)) { menu = false; onDelete(item) }
                    }
                }
            }
        }
        if (reorder != null && reorder.count > 1) {
            Column {
                if (reorder.index > 0) TroyIconButton(Icons.Filled.ArrowUpward, stringResource(R.string.dl_move_up), { reorder.onMove(reorder.index - 1) })
                if (reorder.index < reorder.count - 1) TroyIconButton(Icons.Filled.ArrowDownward, stringResource(R.string.dl_move_down), { reorder.onMove(reorder.index + 1) })
            }
            DragHandle(reorder)
        }
    }
}

@Composable
private fun DragHandle(reorder: Reorder) {
    val c = Troy.colors
    val stepPx = with(LocalDensity.current) { 100.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = stringResource(R.string.cd_reorder),
        tint = c.text3,
        modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(reorder.index, reorder.count) {
            var at = reorder.index
            detectDragGesturesAfterLongPress(
                onDragStart = { at = reorder.index },
                onDragEnd = { dragY = 0f },
                onDragCancel = { dragY = 0f },
            ) { change, amount ->
                change.consume()
                dragY += amount.y
                if (dragY > stepPx / 2 && at < reorder.count - 1) {
                    reorder.onMove(at + 1); at += 1; dragY -= stepPx
                } else if (dragY < -stepPx / 2 && at > 0) {
                    reorder.onMove(at - 1); at -= 1; dragY += stepPx
                }
            }
        },
    )
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = Troy.type.body, color = Troy.colors.text) },
        leadingIcon = { Icon(icon, null, tint = Troy.colors.text2) },
        onClick = onClick,
    )
}

@Composable
private fun statusLine(item: DownloadItem, speed: Long): String {
    val size = when {
        item.totalBytes > 0 && item.status != DownloadStatus.COMPLETED -> stringResource(R.string.dl_size_of, Format.bytes(item.bytesDone), Format.bytes(item.totalBytes))
        item.totalBytes > 0 -> Format.bytes(item.totalBytes)
        item.bytesDone > 0 -> Format.bytes(item.bytesDone)
        else -> null
    }
    val percent = item.fraction?.let { "${(it * 100).toInt()}%" }
    val parts: List<String?> = when (item.status) {
        DownloadStatus.RUNNING -> {
            val eta = Format.etaSeconds(item.bytesDone, item.totalBytes, speed)
            listOf(percent, if (speed > 0) Format.bytes(speed) + "/s" else null, eta?.let { etaText(it) }, size)
        }
        DownloadStatus.QUEUED -> listOf(stringResource(R.string.dl_status_queued), percent, size)
        DownloadStatus.WAITING -> listOf(waitText(item.waitReason), percent, size)
        DownloadStatus.PAUSED -> listOf(
            if (item.error == DownloadError.NO_SPACE) stringResource(R.string.dl_status_no_space) else stringResource(R.string.dl_status_paused),
            percent, size,
        )
        DownloadStatus.FAILED -> listOf(errorText(item.error), item.errorDetail?.take(60))
        DownloadStatus.COMPLETED -> listOfNotNull(
            item.meta.quality ?: item.tracks?.label,
            size,
            stringResource(when (item.place) {
                DownloadPlace.PUBLIC -> R.string.dl_where_public
                DownloadPlace.FOLDER -> R.string.dl_where_folder
                DownloadPlace.PRIVATE -> if (item.isStream) R.string.dl_where_app else R.string.dl_where_private
            }),
        )
    }
    val line = parts.filterNotNull().joinToString(" · ")
    return if (item.restartedNotice && item.status != DownloadStatus.COMPLETED) line + " · " + stringResource(R.string.dl_restarted) else line
}

@Composable
private fun etaText(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.dl_eta_seconds, seconds.toInt())
    seconds < 3600 -> pluralStringResource(R.plurals.dl_eta_minutes, (seconds / 60).toInt(), (seconds / 60).toInt())
    else -> pluralStringResource(R.plurals.dl_eta_hours, (seconds / 3600).toInt(), (seconds / 3600).toInt())
}

@Composable
private fun waitText(reason: WaitReason?): String = stringResource(
    when (reason) {
        WaitReason.NETWORK -> R.string.dl_wait_network
        WaitReason.WIFI -> R.string.dl_wait_wifi
        WaitReason.CHARGING -> R.string.dl_wait_charging
        WaitReason.SCHEDULE -> R.string.dl_wait_schedule
        WaitReason.STORAGE -> R.string.dl_status_no_space
        WaitReason.RETRY -> R.string.dl_wait_retry
        null -> R.string.dl_status_queued
    },
)

@Composable
private fun errorText(error: DownloadError?): String = stringResource(
    when (error) {
        DownloadError.NETWORK -> R.string.dl_error_network
        DownloadError.HTTP -> R.string.dl_error_http
        DownloadError.EXPIRED -> R.string.dl_error_expired
        DownloadError.NO_SPACE -> R.string.dl_status_no_space
        DownloadError.STORAGE -> R.string.dl_error_storage
        DownloadError.NOT_ALLOWED -> R.string.save_denied_drm
        DownloadError.UNKNOWN, null -> R.string.dl_error_unknown
    },
)

private fun canOpenFolder(item: DownloadItem) = !item.isStream && (item.place == DownloadPlace.PUBLIC || item.place == DownloadPlace.FOLDER)

private fun canShare(item: DownloadItem) = !item.isStream && item.outputUri?.startsWith("content://") == true

private fun mimeOf(item: DownloadItem) = item.mimeType ?: "video/*"

private fun openFolder(context: Context, item: DownloadItem) {
    val uri = when (item.place) {
        DownloadPlace.FOLDER -> item.folderUri?.let(Uri::parse)?.let { tree -> DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }
        else -> DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Movies/${Sinks.APP_FOLDER}/${Sinks.DOWNLOADS_FOLDER}")
    } ?: return
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.dl_no_app, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, R.string.dl_no_app, Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, item: DownloadItem) {
    val uri = item.outputUri?.let(Uri::parse) ?: return
    val send = Intent(Intent.ACTION_SEND).setType(mimeOf(item)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openWith(context: Context, item: DownloadItem) {
    val uri = item.outputUri?.let(Uri::parse) ?: return
    if (uri.scheme == "file") {
        Toast.makeText(context, R.string.dl_no_app, Toast.LENGTH_SHORT).show()
        return
    }
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(item)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.dl_no_app, Toast.LENGTH_SHORT).show()
    }
}
