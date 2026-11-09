package com.troy.orcaplayer.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.R
import com.troy.orcaplayer.backup.BackupFormat
import com.troy.orcaplayer.backup.BackupFormatException
import com.troy.orcaplayer.backup.BackupManager
import com.troy.orcaplayer.backup.BackupPasswordException
import com.troy.orcaplayer.backup.BackupPayload
import com.troy.orcaplayer.backup.BackupSection
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.ui.tools.ToolCard
import com.troy.orcaplayer.ui.tools.ToolScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlin.system.exitProcess

sealed interface BackupUi {
    data object Idle : BackupUi
    data object Working : BackupUi
    data class Saved(val sections: Int) : BackupUi
    /** A file was opened and needs its password. */
    data class NeedsPassword(val uri: Uri, val wrong: Boolean) : BackupUi
    data class Preview(val payload: BackupPayload) : BackupUi
    data class Failed(val message: Int) : BackupUi
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: BackupManager,
) : ViewModel() {
    private val _ui = MutableStateFlow<BackupUi>(BackupUi.Idle)
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    fun export(uri: Uri, sections: Set<BackupSection>, password: String) {
        _ui.value = BackupUi.Working
        viewModelScope.launch {
            runCatching {
                val bytes = manager.create(sections, password.ifEmpty { null })
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
            }.onSuccess { _ui.value = BackupUi.Saved(sections.size) }
                .onFailure { _ui.value = BackupUi.Failed(R.string.backup_failed) }
        }
    }

    fun open(uri: Uri, password: String?) {
        _ui.value = BackupUi.Working
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                BackupFormat.read(bytes, password)
            }
            _ui.value = result.fold(
                onSuccess = { BackupUi.Preview(it) },
                onFailure = { e ->
                    when (e) {
                        is BackupPasswordException -> BackupUi.NeedsPassword(uri, wrong = password != null)
                        is BackupFormatException -> BackupUi.Failed(R.string.backup_not_backup)
                        else -> BackupUi.Failed(R.string.backup_read_failed)
                    }
                },
            )
        }
    }

    fun restore(payload: BackupPayload, sections: Set<BackupSection>) {
        _ui.value = BackupUi.Working
        viewModelScope.launch {
            runCatching { manager.restore(payload, sections) }
                .onSuccess { restart() }
                .onFailure { _ui.value = BackupUi.Failed(R.string.backup_restore_failed) }
        }
    }

    fun dismiss() {
        _ui.value = BackupUi.Idle
    }

    /** Everything reloads from the restored data. */
    private fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        exitProcess(0)
    }
}

private fun BackupSection.labelRes(): Int = when (this) {
    BackupSection.SETTINGS -> R.string.backup_settings
    BackupSection.SOURCES -> R.string.backup_sources
    BackupSection.LINKS -> R.string.backup_links
    BackupSection.MY_LIST -> R.string.backup_my_list
    BackupSection.PROFILES -> R.string.backup_profiles
    BackupSection.SHARES -> R.string.backup_shares
    BackupSection.HISTORY -> R.string.backup_history
}

/** Backup and restore (22). */
@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val c = Troy.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    // History is optional and off by default.
    var chosen by remember { mutableStateOf(BackupSection.entries.toSet() - BackupSection.HISTORY - BackupSection.SHARES) }
    val withPassword = password.isNotEmpty()
    val sections = chosen.filter { it != BackupSection.SHARES || withPassword }.toSet()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri, sections, password)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri, null)
    }

    ToolScaffold(stringResource(R.string.backup_title), onBack) {
        when (val u = ui) {
            is BackupUi.Saved -> TroyAlert(stringResource(R.string.backup_saved), AlertKind.OK, Modifier.fillMaxWidth())
            is BackupUi.Failed -> TroyAlert(stringResource(u.message), AlertKind.DANGER, Modifier.fillMaxWidth())
            BackupUi.Working -> Text(stringResource(R.string.backup_working), style = Troy.type.body, color = c.text2)
            else -> Unit
        }
        when (val u = ui) {
            is BackupUi.NeedsPassword -> PasswordCard(u, onOpen = { vm.open(u.uri, it) }, onCancel = vm::dismiss)
            is BackupUi.Preview -> RestoreCard(u.payload, onRestore = { vm.restore(u.payload, it) }, onCancel = vm::dismiss)
            else -> {
                ToolCard {
                    SectionLabel(stringResource(R.string.backup_make))
                    BackupSection.entries.forEach { s ->
                        val locked = s == BackupSection.SHARES && !withPassword
                        SectionRow(stringResource(s.labelRes()), s in sections, enabled = !locked) { on -> chosen = if (on) chosen + s else chosen - s }
                    }
                    TroyTextField(password, { password = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.backup_password_hint), password = true)
                    Text(
                        stringResource(if (withPassword) R.string.backup_with_password else R.string.backup_without_password),
                        style = Troy.type.bodySmall, color = c.text3,
                    )
                    TroyButton(stringResource(R.string.backup_save), { save.launch("orca-player-backup.json") }, kind = ButtonKind.PRIMARY, enabled = sections.isNotEmpty() && ui != BackupUi.Working)
                }
                ToolCard {
                    SectionLabel(stringResource(R.string.backup_restore))
                    Text(stringResource(R.string.backup_restore_hint), style = Troy.type.bodySmall, color = c.text2)
                    TroyButton(stringResource(R.string.backup_open), { pick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, enabled = ui != BackupUi.Working)
                }
            }
        }
    }
}

@Composable
private fun SectionRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Troy.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Troy.type.body, color = if (enabled) c.text else c.text3, modifier = Modifier.weight(1f))
        TroyToggle(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun PasswordCard(u: BackupUi.NeedsPassword, onOpen: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    ToolCard {
        SectionLabel(stringResource(R.string.backup_locked))
        if (u.wrong) TroyAlert(stringResource(R.string.backup_wrong_password), AlertKind.DANGER, Modifier.fillMaxWidth())
        TroyTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.backup_password), password = true, onImeAction = { onOpen(text) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TroyButton(stringResource(R.string.backup_unlock), { onOpen(text) }, kind = ButtonKind.PRIMARY, enabled = text.isNotEmpty())
            TroyButton(stringResource(R.string.dialog_cancel), onCancel, kind = ButtonKind.GHOST)
        }
    }
}

@Composable
private fun RestoreCard(payload: BackupPayload, onRestore: (Set<BackupSection>) -> Unit, onCancel: () -> Unit) {
    val c = Troy.colors
    var chosen by remember(payload) { mutableStateOf(payload.present.toSet()) }
    ToolCard {
        SectionLabel(stringResource(R.string.backup_inside))
        val date = remember(payload) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(payload.createdAt)) }
        Text(stringResource(R.string.backup_made_with, payload.app.ifBlank { "?" }, date), style = Troy.type.bodySmall, color = c.text2)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            payload.present.forEach { s -> SectionRow(stringResource(s.labelRes()), s in chosen) { on -> chosen = if (on) chosen + s else chosen - s } }
        }
        Text(stringResource(R.string.backup_restore_note), style = Troy.type.bodySmall, color = c.text3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TroyButton(stringResource(R.string.backup_restore_now), { onRestore(chosen) }, kind = ButtonKind.PRIMARY, enabled = chosen.isNotEmpty())
            TroyButton(stringResource(R.string.dialog_cancel), onCancel, kind = ButtonKind.GHOST)
        }
    }
}
