package com.troy.orcaplayer.ui.local

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PinLock
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.ui.common.ConfirmDialog
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.player.timecode
import com.troy.orcaplayer.ui.profiles.DeviceAuth
import com.troy.orcaplayer.ui.profiles.NewPinDialog
import com.troy.orcaplayer.ui.profiles.PinDialog
import com.troy.orcaplayer.ui.shell.BackTopBar
import com.troy.orcaplayer.vault.VaultItem

/** The private folder's own PIN, for devices without a screen lock. Only a salted hash is kept. */
object VaultLock {
    private fun prefs(c: Context) = c.getSharedPreferences("vault_lock", Context.MODE_PRIVATE)
    fun hasPin(c: Context) = prefs(c).contains("hash")
    fun setPin(c: Context, pin: String) {
        val salt = PinLock.newSalt()
        prefs(c).edit().putString("salt", salt).putString("hash", PinLock.hash(pin, salt)).putInt("fails", 0).apply()
    }
    fun check(c: Context, pin: String): Boolean {
        val p = prefs(c)
        if (waitLeft(c) > 0) return false
        val ok = PinLock.verify(pin, p.getString("hash", null), p.getString("salt", null))
        val fails = if (ok) 0 else p.getInt("fails", 0) + 1
        p.edit().putInt("fails", fails).putLong("at", System.currentTimeMillis()).apply()
        return ok
    }
    fun waitLeft(c: Context): Long {
        val p = prefs(c)
        val until = p.getLong("at", 0) + PinLock.lockoutMs(p.getInt("fails", 0))
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    /** Unlocked until the screen is left or the app goes to the background. */
    @Volatile var unlocked = false
}

/**
 * The private folder (20.4): locked by fingerprint, face unlock or the device screen lock, or by
 * its own PIN on devices without one. Files here are hidden from the gallery and the rest of the app.
 */
@Composable
fun PrivateFolderScreen(onBack: () -> Unit, ops: FileOpsViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var unlocked by remember { mutableStateOf(VaultLock.unlocked) }
    val title = stringResource(R.string.vault_title)
    val cancel = stringResource(R.string.dialog_cancel)
    val bio = activity != null && DeviceAuth.available(activity)
    var askPin by remember { mutableStateOf(false) }
    var setPin by remember { mutableStateOf(false) }

    fun unlock() {
        when {
            bio -> DeviceAuth.ask(activity!!, title, cancel) { unlocked = true; VaultLock.unlocked = true }
            VaultLock.hasPin(context) -> askPin = true
            else -> setPin = true
        }
    }
    LaunchedEffect(Unit) { if (!unlocked) unlock() }
    // Leaving the app locks the folder again.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { VaultLock.unlocked = false; unlocked = false }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(title, onBack = onBack) {}
        if (!unlocked) {
            EmptyState(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.vault_locked),
                message = stringResource(if (bio) R.string.vault_locked_message else R.string.vault_locked_pin),
                modifier = Modifier.weight(1f),
                actionLabel = stringResource(R.string.vault_unlock),
                onAction = ::unlock,
            )
        } else {
            VaultList(ops)
        }
    }
    if (askPin) {
        PinDialog(
            title = title,
            message = null,
            waitLeft = { VaultLock.waitLeft(context) },
            onDismiss = { askPin = false },
            check = { VaultLock.check(context, it) },
            onSuccess = { askPin = false; unlocked = true; VaultLock.unlocked = true },
        )
    }
    if (setPin) {
        NewPinDialog(onDismiss = { setPin = false }) { pin ->
            VaultLock.setPin(context, pin)
            setPin = false
            unlocked = true
            VaultLock.unlocked = true
        }
    }
}

@Composable
private fun VaultList(ops: FileOpsViewModel) {
    val c = Troy.colors
    val context = LocalContext.current
    val items by ops.vault.items.collectAsStateWithLifecycle()
    var selection by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<VaultItem?>(null) }

    FileOpsHost(ops)
    TroyAlert(stringResource(R.string.vault_warning), AlertKind.WARN, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    val sel = selection
    if (sel != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.vault_selected, sel.size), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TroyIconButton(Icons.Filled.Visibility, stringResource(R.string.vault_unhide), { ops.unhide(sel); selection = null })
            if (sel.size == 1) TroyIconButton(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.files_rename), { renaming = items.firstOrNull { it.id == sel.first() } })
            TroyIconButton(Icons.Filled.Delete, stringResource(R.string.files_delete), { confirmDelete = true }, tint = c.danger)
            TroyButton(stringResource(R.string.dialog_cancel), { selection = null }, kind = ButtonKind.GHOST)
        }
    }
    if (items.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.LockOpen,
            title = stringResource(R.string.vault_empty),
            message = stringResource(R.string.vault_empty_message),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val sorted = items.sortedByDescending { it.addedAt }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(sorted, key = { it.id }) { item ->
            val selected = sel?.contains(item.id)
            Row(
                Modifier.fillMaxWidth().troyFocusRing()
                    .background(if (selected == true) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                    .combinedClickable(
                        role = Role.Button,
                        onLongClick = { selection = (sel ?: emptyList()).let { if (item.id in it) it - item.id else it + item.id }.ifEmpty { null } },
                        onClick = {
                            if (sel != null) {
                                selection = (if (item.id in sel) sel - item.id else sel + item.id).ifEmpty { null }
                            } else {
                                PlayerLaunch.play(context, request(sorted, sorted.indexOf(item)))
                            }
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (selected == true) Icons.Filled.CheckCircle else if (item.isAudio) Icons.Filled.MusicNote else Icons.Filled.Movie,
                    contentDescription = null, tint = if (selected == true) c.accent else c.text3, modifier = Modifier.size(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = Troy.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val details = listOfNotNull(
                        Formatter.formatShortFileSize(context, item.sizeBytes),
                        item.durationMs.takeIf { it > 0 }?.let { timecode(it) },
                    ).joinToString(" · ")
                    Text(details, style = Troy.type.bodySmall, color = c.text2)
                }
            }
        }
    }
    if (confirmDelete && sel != null) {
        ConfirmDialog(
            title = stringResource(R.string.vault_delete_title),
            message = stringResource(R.string.vault_delete_message),
            confirm = stringResource(R.string.files_delete),
            danger = true,
            onConfirm = { confirmDelete = false; ops.deleteHidden(sel); selection = null },
            onDismiss = { confirmDelete = false },
        )
    }
    renaming?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        ConfirmDialog(
            title = stringResource(R.string.files_rename),
            message = stringResource(R.string.files_rename_message),
            confirm = stringResource(R.string.files_rename),
            onConfirm = { renaming = null; ops.renameHidden(item.id, name); selection = null },
            onDismiss = { renaming = null },
            extra = { TroyTextField(name, { name = it }, Modifier.fillMaxWidth()) },
        )
    }
}

/** Private-folder files play from their encrypted copies; resume carries over from before they were hidden. */
private fun request(items: List<VaultItem>, index: Int): PlayRequest {
    val from = (index - 50).coerceAtLeast(0)
    val window = items.subList(from, (index + 50).coerceAtMost(items.size))
    return PlayRequest(
        window.map { v ->
            PlayItem(
                uri = v.uri,
                title = v.name.substringBeforeLast('.'),
                key = v.key ?: MediaKeys.forLink(v.uri),
                source = SourceRef(SourceKind.LOCAL, "private"),
                canSave = false,
                downloadable = false,
                mimeType = v.mimeType,
            )
        },
        index - from,
    )
}
