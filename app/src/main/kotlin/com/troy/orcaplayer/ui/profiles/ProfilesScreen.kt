package com.troy.orcaplayer.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.materialize
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.KidsRules
import com.troy.orcaplayer.core.model.Profile
import com.troy.orcaplayer.core.settings.Settings
import com.troy.orcaplayer.ui.shell.BackTopBar

/** Daily limits offered for Kids profiles, in minutes; 0 is no limit. */
private val DAILY_LIMITS = listOf(0, 30, 60, 90, 120, 180)

/** Profiles (18): switch, add, edit, reorder and delete. */
@Composable
fun ProfilesScreen(onBack: () -> Unit, vm: ProfilesViewModel = hiltViewModel()) {
    val c = Troy.colors
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf<Profile?>(null) }
    var unlockForEdit by remember { mutableStateOf(false) }
    val accents = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(profiles) { profiles.forEach { accents[it.id] = vm.accentOf(it.id) } }

    val editing = profiles.firstOrNull { it.id == editingId }
    if (creating || editing != null) {
        ProfileEditor(
            existing = editing,
            accent = editing?.let { accents[it.id] } ?: Settings.Accent.default,
            vm = vm,
            onDone = { creating = false; editingId = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.profiles_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(profiles, key = { _, p -> p.id }) { i, p ->
                Row(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp).materialize(i).glass()
                        .troyFocusRing()
                        .clickable(role = Role.Button) {
                            when {
                                p.id == current -> Unit
                                p.hasPin -> { unlockForEdit = false; unlocking = p }
                                else -> vm.switchTo(p.id, onBack)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(p.avatar, accents[p.id], selected = p.id == current)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(p.name, style = Troy.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val tags = listOfNotNull(
                            if (p.isKids) stringResource(R.string.profile_kids) else null,
                            if (p.hasPin) stringResource(R.string.profile_has_pin) else null,
                        )
                        if (tags.isNotEmpty()) SectionLabel(tags.joinToString(" · "))
                    }
                    if (p.hasPin) Icon(Icons.Outlined.Lock, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
                    if (p.id == current) Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.profiles_current), tint = c.accent)
                    TroyIconButton(Icons.Filled.ArrowUpward, stringResource(R.string.dl_move_up), { vm.move(p.id, -1) })
                    TroyIconButton(Icons.Filled.ArrowDownward, stringResource(R.string.dl_move_down), { vm.move(p.id, 1) })
                    TroyIconButton(Icons.Outlined.Edit, stringResource(R.string.profile_edit), {
                        // Changing a PIN-protected profile asks for its PIN first.
                        if (p.hasPin && p.id != current) { unlockForEdit = true; unlocking = p } else editingId = p.id
                    })
                }
            }
            item {
                TroyButton(
                    stringResource(R.string.profile_add), { creating = true },
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp),
                    icon = Icons.Outlined.Add,
                )
            }
        }
    }

    unlocking?.let { target ->
        PinDialog(
            title = stringResource(R.string.pin_enter_for, target.name),
            message = null,
            waitLeft = { vm.pinWaitLeft(target.id) },
            onDismiss = { unlocking = null },
            check = { vm.checkPin(target.id, it) },
            onSuccess = {
                unlocking = null
                if (unlockForEdit) editingId = target.id else vm.switchTo(target.id, onBack)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(existing: Profile?, accent: String, vm: ProfilesViewModel, onDone: () -> Unit) {
    val c = Troy.colors
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var avatar by rememberSaveable { mutableStateOf(existing?.avatar ?: Profile.DEFAULT_AVATAR) }
    var accentKey by rememberSaveable { mutableStateOf(accent) }
    var isKids by rememberSaveable { mutableStateOf(existing?.isKids ?: false) }
    var kids by remember { mutableStateOf(existing?.kids ?: KidsRules()) }
    var newPin by remember { mutableStateOf<String?>(null) }
    var removePin by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val catalogs = remember { vm.kidsCatalogs() }
    LaunchedEffect(Unit) { folders = vm.folders() }
    val isDefault = existing?.id == Profile.DEFAULT_ID
    val hasPin = (existing?.hasPin == true && !removePin) || newPin != null

    BackHandler(onBack = onDone)
    fun save() = vm.save(existing, name, avatar, accentKey, isKids, kids, newPin, removePin) { onDone() }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(if (existing == null) R.string.profile_add else R.string.profile_edit), onDone) {
            TroyButton(stringResource(R.string.profile_save), ::save, kind = ButtonKind.PRIMARY, enabled = name.isNotBlank())
        }
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(avatar, accentKey, size = 64.dp)
                    Spacer(Modifier.size(16.dp))
                    TroyTextField(name, { name = it.take(30) }, Modifier.weight(1f), placeholder = stringResource(R.string.profile_name))
                }
            }
            item {
                SectionLabel(stringResource(R.string.profile_avatar))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AVATARS.forEach { a ->
                        Column(Modifier.troyFocusRing(26.dp).clip(CircleShape).clickable(role = Role.RadioButton) { avatar = a }) {
                            Avatar(a, accentKey, size = 48.dp, selected = a == avatar)
                        }
                    }
                }
            }
            item {
                SectionLabel(stringResource(R.string.profile_accent))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROFILE_ACCENTS.forEach { ch ->
                        Row(
                            Modifier.troyFocusRing().clip(TroyShapes.Chip).clickable(role = Role.RadioButton) { accentKey = ch.value }
                                .background(if (ch.value == accentKey) c.controlFill else androidx.compose.ui.graphics.Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.size(16.dp).clip(CircleShape).background(accentColor(ch.value)))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(ch.labelRes), style = Troy.type.bodySmall, color = c.text)
                        }
                    }
                }
            }
            item {
                ToggleRow(stringResource(R.string.profile_kids), stringResource(R.string.profile_kids_summary), isKids, enabled = !isDefault) { isKids = it }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_pin), style = Troy.type.body, color = c.text)
                        Text(stringResource(if (hasPin) R.string.profile_pin_on else R.string.profile_pin_off), style = Troy.type.bodySmall, color = c.text2)
                    }
                    if (hasPin) {
                        TroyButton(stringResource(R.string.profile_pin_remove), { newPin = null; removePin = true }, kind = ButtonKind.GHOST)
                    }
                    TroyButton(stringResource(if (hasPin) R.string.profile_pin_change else R.string.profile_pin_set), { settingPin = true })
                }
            }
            if (isKids) {
                item {
                    SectionLabel(stringResource(R.string.kids_daily_limit))
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DAILY_LIMITS.forEach { m ->
                            TroyChip(
                                if (m == 0) stringResource(R.string.kids_no_limit) else stringResource(R.string.kids_minutes, m),
                                kids.dailyLimitMinutes == m, { kids = kids.copy(dailyLimitMinutes = m) },
                            )
                        }
                    }
                }
                item {
                    ToggleRow(stringResource(R.string.kids_pinning), stringResource(R.string.kids_pinning_summary), kids.screenPinning) {
                        kids = kids.copy(screenPinning = it)
                    }
                }
                item {
                    SectionLabel(stringResource(R.string.kids_catalogs))
                    Text(stringResource(R.string.kids_catalogs_summary), style = Troy.type.bodySmall, color = c.text2)
                }
                if (catalogs.isEmpty()) {
                    item { Text(stringResource(R.string.kids_catalogs_none), style = Troy.type.bodySmall, color = c.text3) }
                }
                catalogs.forEach { row ->
                    item(key = "cat:" + row.key) {
                        ToggleRow(row.name, row.addonName, row.key in kids.catalogs) { on ->
                            kids = kids.copy(catalogs = if (on) kids.catalogs + row.key else kids.catalogs - row.key)
                        }
                    }
                }
                item {
                    SectionLabel(stringResource(R.string.kids_folders))
                    Text(stringResource(R.string.kids_folders_summary), style = Troy.type.bodySmall, color = c.text2)
                }
                folders.forEach { (id, label) ->
                    item(key = "folder:$id") {
                        ToggleRow(label, null, id in kids.folders) { on ->
                            kids = kids.copy(folders = if (on) kids.folders + id else kids.folders - id)
                        }
                    }
                }
                if (kids.titles.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.kids_titles_count, kids.titles.size), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
                            TroyButton(stringResource(R.string.kids_titles_clear), { kids = kids.copy(titles = emptySet()) }, kind = ButtonKind.GHOST)
                        }
                    }
                }
            }
            if (existing != null && !isDefault) {
                item {
                    TroyButton(stringResource(R.string.profile_delete), { confirmDelete = true }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (settingPin) {
        NewPinDialog(onDismiss = { settingPin = false }, onSet = { newPin = it; removePin = false; settingPin = false })
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.profile_delete_title, existing.name), style = Troy.type.title, color = c.text) },
            text = { Text(stringResource(R.string.profile_delete_message), style = Troy.type.body, color = c.text2) },
            confirmButton = {
                TextButton(onClick = { vm.delete(existing.id); confirmDelete = false; onDone() }) { Text(stringResource(R.string.profile_delete), color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Troy.colors
    Row(
        Modifier.fillMaxWidth().clip(TroyShapes.Control).background(c.controlFill).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Troy.type.body, color = if (enabled) c.text else c.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            summary?.let { Text(it, style = Troy.type.bodySmall, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        TroyToggle(checked, onCheckedChange = onChange, enabled = enabled)
    }
}
