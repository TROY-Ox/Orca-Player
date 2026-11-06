package com.troy.orcaplayer.ui.local

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.sources.local.LocalMedia
import com.troy.orcaplayer.ui.common.ConfirmDialog

/** What to do once the user has picked a folder. */
private enum class PickFor { COPY, MOVE }

/** The top bar while files are selected (20.4): count, select all and the file actions. */
@Composable
fun FileSelectionBar(chosen: List<LocalMedia>, allVisible: List<LocalMedia>, ops: FileOpsViewModel, onSelectAll: () -> Unit, onDone: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmHide by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LocalMedia?>(null) }
    var pickFor by remember { mutableStateOf<PickFor?>(null) }
    var menu by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val mode = pickFor
        pickFor = null
        if (tree != null && mode != null) {
            ops.copyTo(chosen, tree, move = mode == PickFor.MOVE)
            onDone()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TroyIconButton(Icons.Filled.Close, stringResource(R.string.files_cancel_selection), onDone)
        Text(
            pluralStringResource(R.plurals.files_selected_count, chosen.size, formatNumber(chosen.size)),
            style = Troy.type.title, color = c.text, modifier = Modifier.weight(1f),
        )
        if (chosen.size < allVisible.size) TroyIconButton(Icons.Filled.SelectAll, stringResource(R.string.files_select_all), onSelectAll)
        TroyIconButton(Icons.Filled.Share, stringResource(R.string.files_share), {
            runCatching { context.startActivity(ops.shareIntent(chosen)) }
        })
        TroyIconButton(Icons.Filled.Delete, stringResource(R.string.files_delete), { confirmDelete = true }, tint = c.danger)
        androidx.compose.foundation.layout.Box {
            TroyIconButton(Icons.Filled.MoreVert, stringResource(R.string.files_more), { menu = true })
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.glassSolid) {
                @Composable
                fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, action: () -> Unit) = DropdownMenuItem(
                    text = { Text(stringResource(label), color = c.text) },
                    leadingIcon = { Icon(icon, contentDescription = null, tint = c.text2) },
                    onClick = { menu = false; action() },
                )
                if (chosen.size == 1) item(Icons.Filled.DriveFileRenameOutline, R.string.files_rename) { renaming = chosen.first() }
                item(Icons.Filled.ContentCopy, R.string.files_copy) { pickFor = PickFor.COPY; runCatching { pickFolder.launch(null) } }
                item(Icons.AutoMirrored.Filled.DriveFileMove, R.string.files_move) { pickFor = PickFor.MOVE; runCatching { pickFolder.launch(null) } }
                item(Icons.Filled.Lock, R.string.vault_hide) { confirmHide = true }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.files_delete_title, chosen.size, formatNumber(chosen.size)),
            message = stringResource(R.string.files_delete_message),
            confirm = stringResource(R.string.files_delete),
            danger = true,
            onConfirm = { confirmDelete = false; ops.delete(chosen); onDone() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmHide) {
        ConfirmDialog(
            title = stringResource(R.string.vault_hide_title),
            message = stringResource(R.string.vault_warning),
            confirm = stringResource(R.string.vault_hide),
            onConfirm = { confirmHide = false; ops.hide(chosen); onDone() },
            onDismiss = { confirmHide = false },
        )
    }
    renaming?.let { m ->
        var name by remember(m.uri) { mutableStateOf(m.name.substringBeforeLast('.')) }
        ConfirmDialog(
            title = stringResource(R.string.files_rename),
            message = stringResource(R.string.files_rename_message),
            confirm = stringResource(R.string.files_rename),
            onConfirm = { renaming = null; ops.rename(m, name); onDone() },
            onDismiss = { renaming = null },
            extra = { TroyTextField(name, { name = it }, Modifier.fillMaxWidth()) },
        )
    }
}

/**
 * Shows Android's consent screens, progress for long copies and the results of file actions.
 * Lives as long as the screen, so results still show after the selection ends.
 */
@Composable
fun FileOpsHost(ops: FileOpsViewModel) {
    val c = Troy.colors
    val context = LocalContext.current
    val progress by ops.progress.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        ops.onConsent(r.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(ops) {
        ops.consent.collect { sender -> runCatching { consent.launch(IntentSenderRequest.Builder(sender).build()) }.onFailure { ops.onConsent(false) } }
    }
    LaunchedEffect(ops) {
        ops.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    progress?.let { p ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(p.labelRes, p.name), style = Troy.type.bodySmall, color = c.text2, maxLines = 1)
            LinearProgressIndicator(
                progress = { if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = c.accent,
                trackColor = c.controlFill,
            )
        }
    }
}
