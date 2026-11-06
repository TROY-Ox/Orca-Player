package com.troy.orcaplayer.ui.addons

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.data.Health
import com.troy.orcaplayer.data.HealthState
import com.troy.orcaplayer.data.InstalledAddon
import com.troy.orcaplayer.sources.stremio.ManifestException
import com.troy.orcaplayer.sources.stremio.StremioUrls
import com.troy.orcaplayer.ui.common.Badge
import com.troy.orcaplayer.ui.common.HealthDot
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.shell.MainTopBar
import java.text.DateFormat
import java.util.Date

/** Opens a page in a Custom Tab (addon configure pages, 15.1). */
fun openCustomTab(context: Context, url: String) {
    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
        .onFailure {
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
}

fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddonsScreen(
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenAddon: (String) -> Unit,
    onOpenDiscover: () -> Unit,
    installRequest: String?,
    onInstallRequestHandled: () -> Unit,
    onOpenExtensions: (() -> Unit)? = null,
    vm: AddonsViewModel = hiltViewModel(),
) {
    val c = Troy.colors
    val context = LocalContext.current
    val list by vm.list.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val directory by vm.directory.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var url by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AddonFilter.ALL) }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(installRequest) {
        if (installRequest != null) {
            vm.openPreview(installRequest)
            onInstallRequestHandled()
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportJson().toByteArray()) } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.use { vm.import(it.readBytes().toString(Charsets.UTF_8)) } }
    }

    val shown = list.addons.withIndex().filter { vm.matches(it.value, filter, query) }
    val selecting = selected.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        MainTopBar(stringResource(R.string.tab_addons), onOpenSettings, onOpenProfiles) {
            TroyIconButton(Icons.Outlined.Explore, stringResource(R.string.discover_title), onOpenDiscover)
            Box {
                TroyIconButton(Icons.Outlined.MoreVert, stringResource(R.string.more_options), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.addons_check_updates), color = c.text) }, onClick = { menu = false; vm.refresh() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.addons_export_file), color = c.text) }, onClick = { menu = false; exporter.launch("orca-sources.json") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.addons_export_text), color = c.text) }, onClick = {
                        menu = false
                        copyText(context, "ORCA sources", vm.exportJson())
                        android.widget.Toast.makeText(context, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.addons_import_file), color = c.text) }, onClick = { menu = false; importer.launch(arrayOf("application/json", "text/*", "*/*")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.addons_import_clipboard), color = c.text) }, onClick = {
                        menu = false
                        clipboardText(context)?.let(vm::import)
                    })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "add") {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionLabel(stringResource(R.string.addons_add_by_url))
                    TroyTextField(
                        url, { url = it }, Modifier.fillMaxWidth(),
                        placeholder = stringResource(R.string.addons_url_hint),
                        onImeAction = { vm.openPreview(url) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyButton(stringResource(R.string.paste), { clipboardText(context)?.let { url = it.trim() } }, icon = Icons.Outlined.ContentPaste)
                        TroyButton(stringResource(R.string.addons_preview), { vm.openPreview(url) }, kind = ButtonKind.PRIMARY, enabled = url.isNotBlank())
                    }
                }
            }
            // Extended build: CloudStream repositories and extensions (19.2).
            if (onOpenExtensions != null) {
                item(key = "extensions") {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ext_entry_title), style = Troy.type.body, color = c.text)
                            Text(stringResource(R.string.ext_entry_summary), style = Troy.type.bodySmall, color = c.text2)
                        }
                        TroyButton(stringResource(R.string.ext_entry_open), onOpenExtensions)
                    }
                }
            }
            if (list.addons.isNotEmpty()) {
                item(key = "search") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.addons_search_hint))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Addons and Extensions only mean something when extensions exist.
                            items(AddonFilter.entries.filter { com.troy.orcaplayer.flavor.Flavor.EXTENSIONS || (it != AddonFilter.ADDONS && it != AddonFilter.EXTENSIONS) }) { f ->
                                TroyChip(stringResource(f.label()), selected = filter == f, onClick = { filter = f })
                            }
                        }
                        if (refreshing) Text(stringResource(R.string.addons_checking), style = Troy.type.bodySmall, color = c.text2)
                    }
                }
            }
            if (list.addons.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Outlined.Extension, stringResource(R.string.addons_empty_title), stringResource(R.string.addons_empty_message_v2),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, it -> it.value.id }) { _, iv ->
                val a = iv.value
                AddonRow(
                    addon = a,
                    index = iv.index,
                    count = list.addons.size,
                    health = health[a.id],
                    selected = a.id in selected,
                    selecting = selecting,
                    onClick = { if (selecting) selected = selected.toggle(a.id) else onOpenAddon(a.id) },
                    onLongClick = { selected = selected.toggle(a.id) },
                    onToggle = { vm.setEnabled(setOf(a.id), it) },
                    onMove = { to -> vm.move(iv.index, to) },
                    dragEnabled = filter == AddonFilter.ALL && query.isBlank(),
                )
            }
            // Directory addons (addon_catalog, 15.5): the user's own, none built in.
            val directories = list.addons.filter { it.enabled }.flatMap { a -> a.manifest.addonCatalogs.map { a to it } }
            if (directories.isNotEmpty()) {
                item(key = "dirs") {
                    SectionLabel(stringResource(R.string.addons_directories), Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                }
                items(directories, key = { "dir:" + it.first.id + it.second.key }) { (a, cat) ->
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).troyFocusRing()
                            .combinedClickable(role = Role.Button, onClick = { vm.openDirectory(a, cat) }).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = c.accent)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cat.name, style = Troy.type.body, color = c.text)
                            Text(a.manifest.name, style = Troy.type.bodySmall, color = c.text2)
                        }
                    }
                }
            }
        }
    }

    // Multi-select bar
    if (selecting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel, elevation = 16.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TroyIconButton(Icons.Outlined.Close, stringResource(R.string.dialog_cancel), { selected = emptySet() })
                Text(pluralStringResource(R.plurals.addons_selected, selected.size, selected.size), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
                TroyButton(stringResource(R.string.enable), { vm.setEnabled(selected, true); selected = emptySet() }, kind = ButtonKind.GHOST)
                TroyButton(stringResource(R.string.disable), { vm.setEnabled(selected, false); selected = emptySet() }, kind = ButtonKind.GHOST)
                TroyButton(stringResource(R.string.remove), { vm.remove(selected); selected = emptySet() }, kind = ButtonKind.GHOST)
            }
        }
    } else if (undo.isNotEmpty()) {
        LaunchedEffect(undo) {
            kotlinx.coroutines.delay(8_000)
            vm.dismissUndo()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel, elevation = 16.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pluralStringResource(R.plurals.addons_removed, undo.size, undo.size), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
                TroyButton(stringResource(R.string.undo), vm::undoRemove, kind = ButtonKind.GHOST)
            }
        }
    }

    message?.let { n ->
        LaunchedEffect(n) {
            android.widget.Toast.makeText(context, context.resources.getQuantityString(R.plurals.addons_imported, n.toIntOrNull() ?: 0, n.toIntOrNull() ?: 0), android.widget.Toast.LENGTH_SHORT).show()
            vm.clearMessage()
        }
    }

    when (val p = preview) {
        PreviewState.Idle -> Unit
        is PreviewState.Loading -> AlertDialog(
            onDismissRequest = vm::closePreview,
            title = { Text(stringResource(R.string.addons_reading), style = Troy.type.title, color = c.text) },
            text = { Text(p.input, style = Troy.type.mono, color = c.text2, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = vm::closePreview) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
        is PreviewState.Failed -> AlertDialog(
            onDismissRequest = vm::closePreview,
            title = { Text(stringResource(R.string.addons_failed_title), style = Troy.type.title, color = c.text) },
            text = {
                Text(
                    when (p.reason) {
                        ManifestException.Reason.NOT_A_LINK -> stringResource(R.string.addons_failed_link)
                        ManifestException.Reason.TIMEOUT -> stringResource(R.string.addons_failed_timeout)
                        ManifestException.Reason.NETWORK -> stringResource(R.string.addons_failed_network)
                        ManifestException.Reason.HTTP -> stringResource(R.string.addons_failed_http, p.detail ?: "")
                        ManifestException.Reason.NOT_A_MANIFEST -> stringResource(R.string.addons_failed_manifest)
                        null -> p.detail ?: stringResource(R.string.addons_failed_network)
                    },
                    style = Troy.type.body, color = c.text2,
                )
            },
            confirmButton = { TextButton(onClick = vm::closePreview) { Text(stringResource(R.string.ok), color = c.accent) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
        is PreviewState.Ready -> InstallPreview(p, onInstall = { vm.install(p) }, onConfigure = { openCustomTab(context, StremioUrls.configureUrl(p.url)) }, onDismiss = vm::closePreview)
    }

    directory?.let { d ->
        AlertDialog(
            onDismissRequest = vm::closeDirectory,
            title = { Text(d.addonName, style = Troy.type.title, color = c.text) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        d.loading -> Text(stringResource(R.string.loading), style = Troy.type.body, color = c.text2)
                        d.error != null -> Text(stringResource(R.string.row_failed, d.error), style = Troy.type.body, color = c.text2)
                        d.items.isEmpty() -> Text(stringResource(R.string.row_empty), style = Troy.type.body, color = c.text2)
                    }
                    d.items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().troyFocusRing().clip(TroyShapes.Control)
                                .combinedClickable(role = Role.Button, onClick = { vm.closeDirectory(); vm.installListing(item) }).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WebImage(item.manifest?.logo, null, Modifier.size(36.dp).clip(TroyShapes.Chip))
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.manifest?.name ?: item.transportUrl, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                item.manifest?.description?.let { Text(it, style = Troy.type.bodySmall, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeDirectory) { Text(stringResource(R.string.close), color = c.text2) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

private fun AddonFilter.label(): Int = when (this) {
    AddonFilter.ALL -> R.string.filter_all
    AddonFilter.ADDONS -> R.string.filter_addons
    AddonFilter.EXTENSIONS -> R.string.filter_extensions
    AddonFilter.ENABLED -> R.string.filter_enabled
    AddonFilter.DISABLED -> R.string.filter_disabled
    AddonFilter.ERRORS -> R.string.filter_errors
    AddonFilter.UPDATES -> R.string.filter_updates
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddonRow(
    addon: InstalledAddon,
    index: Int,
    count: Int,
    health: Health?,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    dragEnabled: Boolean,
) {
    val c = Troy.colors
    val m = addon.manifest
    val state = if (addon.lastError != null && health == null) HealthState.FAILING else health?.state ?: HealthState.UNKNOWN
    var dragY by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { 76.dp.toPx() }
    val checked = health?.lastAt?.takeIf { it > 0 } ?: addon.lastChecked.takeIf { it > 0 }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .zIndex(if (dragY != 0f) 1f else 0f)
            .graphicsLayer { translationY = dragY }
            .glass(TroyShapes.Panel)
            .then(if (selected) Modifier.background(c.accent.copy(alpha = 0.12f), TroyShapes.Panel) else Modifier)
            .troyFocusRing()
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WebImage(m.logo, null, Modifier.size(44.dp).clip(TroyShapes.Control))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.name, style = Troy.type.body, color = if (addon.enabled) c.text else c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (addon.updatedFrom != null) Badge(stringResource(R.string.badge_updated), tint = c.info)
                if (addon.adult) Badge("18+", tint = c.warn)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HealthDot(state)
                Text(
                    listOfNotNull(
                        stringResource(if (com.troy.orcaplayer.data.ExtensionBridge.handles(addon.manifestUrl)) R.string.kind_extension else R.string.kind_addon),
                        "v${m.version}",
                        when (state) {
                            HealthState.SLOW -> stringResource(R.string.health_slow)
                            HealthState.FAILING -> stringResource(R.string.health_failing)
                            HealthState.OK -> stringResource(R.string.health_ok)
                            HealthState.UNKNOWN -> null
                        },
                        checked?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
                    ).joinToString(" · "),
                    style = Troy.type.bodySmall, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!selecting) {
            if (dragEnabled) {
                // Drag to reorder; the arrows do the same for D-pad, keyboard and TalkBack users.
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = stringResource(R.string.cd_reorder),
                    tint = c.text3,
                    modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(index, count) {
                        var at = index
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at = index },
                            onDragEnd = { dragY = 0f },
                            onDragCancel = { dragY = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragY += amount.y
                            if (dragY > stepPx / 2 && at < count - 1) {
                                onMove(at + 1); at += 1; dragY -= stepPx
                            } else if (dragY < -stepPx / 2 && at > 0) {
                                onMove(at - 1); at -= 1; dragY += stepPx
                            }
                        }
                    },
                )
                Column {
                    if (index > 0) Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up), tint = c.text2, modifier = Modifier.size(28.dp).troyFocusRing().combinedClickable(role = Role.Button, onClick = { onMove(index - 1) }))
                    if (index < count - 1) Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down), tint = c.text2, modifier = Modifier.size(28.dp).troyFocusRing().combinedClickable(role = Role.Button, onClick = { onMove(index + 1) }))
                }
            }
            Box(Modifier.padding(horizontal = 8.dp)) { TroyToggle(addon.enabled, onToggle) }
        }
    }
}

@Composable
private fun InstallPreview(p: PreviewState.Ready, onInstall: () -> Unit, onConfigure: () -> Unit, onDismiss: () -> Unit) {
    val c = Troy.colors
    val m = p.manifest
    val hints = m.behaviorHints
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WebImage(m.logo, null, Modifier.size(48.dp).clip(TroyShapes.Control))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(m.name, style = Troy.type.title, color = c.text)
                    Text("v${m.version}", style = Troy.type.mono, color = c.text2)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                m.description?.let { Text(it, style = Troy.type.body, color = c.text2) }
                if (hints.p2p) TroyAlert(stringResource(R.string.addons_warn_p2p), AlertKind.WARN)
                if (hints.adult) TroyAlert(stringResource(R.string.addons_warn_adult), AlertKind.WARN)
                if (hints.configurationRequired) TroyAlert(stringResource(R.string.addons_needs_config), AlertKind.INFO)
                if (p.installed != null) TroyAlert(stringResource(R.string.addons_replaces, p.installed.manifest.version), AlertKind.INFO)
                if (m.types.isNotEmpty()) PreviewLine(stringResource(R.string.addons_types), m.types.joinToString(", "))
                if (m.resources.isNotEmpty()) PreviewLine(stringResource(R.string.addons_resources), m.resources.joinToString(", ") { it.name })
                if (m.catalogs.isNotEmpty()) PreviewLine(stringResource(R.string.addons_catalogs), m.catalogs.joinToString(", ") { "${it.name} (${it.type})" })
                PreviewLine(stringResource(R.string.addons_link), p.url)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hints.configurable || hints.configurationRequired) TextButton(onClick = onConfigure) { Text(stringResource(R.string.addons_configure), color = c.text) }
                if (!hints.configurationRequired) {
                    TextButton(onClick = onInstall) { Text(stringResource(if (p.installed != null) R.string.addons_update else R.string.addons_install), color = c.accent) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        containerColor = Troy.glass.panel.copy(alpha = 1f),
    )
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Column {
        SectionLabel(label)
        Text(value, style = Troy.type.bodySmall, color = Troy.colors.text)
    }
}
