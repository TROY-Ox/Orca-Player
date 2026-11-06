package com.troy.orcaplayer.flavor

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.sources.cloudstream.PluginEntry
import com.troy.orcaplayer.sources.cloudstream.RepoState
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.shell.BackTopBar
import kotlinx.coroutines.launch

/** CloudStream repositories and extensions (19.2). Nothing is suggested or built in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionsManagerScreen(onBack: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = Flavor.manager
    val state by m.state.collectAsStateWithLifecycle()
    val failures by m.host.failures.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pendingRepo by remember { mutableStateOf<RepoState?>(null) }
    var removeRepo by remember { mutableStateOf<RepoState?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun work(key: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = key
        scope.launch {
            runCatching { block() }.onFailure { toast(context.getString(R.string.ext_failed, it.message ?: it.javaClass.simpleName)) }
            busy = null
        }
    }

    val installed = state.installed.associateBy { it.internalName }
    val all = state.repos.flatMap { r -> r.plugins.map { r to it } }
    val languages = all.mapNotNull { it.second.language?.lowercase() }.distinct().sorted()
    val types = all.flatMap { it.second.tvTypes.orEmpty() }.distinct().sorted()
    val shown = all.filter { (_, p) ->
        (language == null || p.language?.lowercase() == language) &&
            (type == null || type in p.tvTypes.orEmpty()) &&
            (query.isBlank() || p.name.contains(query, true) || p.description?.contains(query, true) == true || p.authors.any { it.contains(query, true) })
    }.sortedWith(compareBy({ it.second.internalName !in installed }, { it.second.name.lowercase() }))
    val updates = m.updates()

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.ext_title), onBack) {
            TroyIconButton(Icons.Outlined.Refresh, stringResource(R.string.ext_refresh), {
                work("refresh") { val n = m.refresh(); toast(context.resources.getQuantityString(R.plurals.ext_updates, n, n)) }
            })
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TroyAlert(stringResource(R.string.ext_warning), AlertKind.WARN, Modifier.widthIn(max = 760.dp).fillMaxWidth())
            }
            item {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.ext_add_repo))
                    TroyTextField(url, { url = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.ext_repo_hint))
                    TroyButton(stringResource(R.string.ext_add), {
                        work("add") { pendingRepo = m.fetchRepo(url) }
                    }, kind = ButtonKind.PRIMARY, enabled = url.isNotBlank() && busy == null)
                }
            }
            if (state.repos.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.ext_repos)) }
                items(state.repos, key = { "repo:" + it.url }) { r ->
                    Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().glass(TroyShapes.Control).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                r.lastError?.let { stringResource(R.string.ext_repo_error, it) }
                                    ?: context.resources.getQuantityString(R.plurals.ext_count, r.plugins.size, r.plugins.size),
                                style = Troy.type.bodySmall, color = if (r.lastError != null) c.danger else c.text2, maxLines = 2,
                            )
                        }
                        TroyButton(stringResource(R.string.remove), { removeRepo = r }, kind = ButtonKind.GHOST)
                    }
                }
                item {
                    Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ext_auto_update), style = Troy.type.body, color = c.text)
                            Text(stringResource(R.string.ext_auto_update_summary), style = Troy.type.bodySmall, color = c.text2)
                        }
                        TroyToggle(state.autoUpdate, onCheckedChange = { m.setAutoUpdate(it) })
                    }
                }
                if (updates.isNotEmpty()) {
                    item {
                        TroyButton(
                            context.resources.getQuantityString(R.plurals.ext_update_all, updates.size, updates.size),
                            { work("all") { m.updateAll(); Flavor.addons.resetClients() } }, kind = ButtonKind.PRIMARY,
                        )
                    }
                }
                item {
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.ext_search))
                        if (languages.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { TroyChip(stringResource(R.string.filter_all), language == null, { language = null }) }
                                items(languages) { l -> TroyChip(l.uppercase(), language == l, { language = l }) }
                            }
                        }
                        if (types.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { TroyChip(stringResource(R.string.filter_all), type == null, { type = null }) }
                                items(types) { t -> TroyChip(t, type == t, { type = t }) }
                            }
                        }
                    }
                }
            }
            if (state.repos.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.Extension, stringResource(R.string.ext_empty_title), stringResource(R.string.ext_empty_message),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            items(shown, key = { "p:" + it.first.url + "|" + it.second.internalName }) { (repo, p) ->
                val mine = installed[p.internalName]
                PluginRow(
                    p = p,
                    installedVersion = mine?.entry?.version,
                    enabled = mine?.enabled,
                    failure = failures[p.internalName],
                    busy = busy == p.internalName,
                    onInstall = { work(p.internalName) { m.install(repo.url, p); Flavor.addons.resetClients() } },
                    onUninstall = { work(p.internalName) { m.uninstall(setOf(p.internalName)) } },
                    onEnable = { on ->
                        work(p.internalName) {
                            m.setEnabled(p.internalName, on)
                            Flavor.addons.setEnabled(Flavor.entriesOf(p.internalName), on)
                        }
                    },
                )
            }
        }
    }

    pendingRepo?.let { r ->
        // The trust screen, every time a repository is added (19.2).
        AlertDialog(
            onDismissRequest = { pendingRepo = null },
            title = { Text(stringResource(R.string.ext_trust_title), style = Troy.type.title, color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.name, style = Troy.type.body, color = c.text)
                    r.description?.let { Text(it, style = Troy.type.bodySmall, color = c.text2) }
                    Text(context.resources.getQuantityString(R.plurals.ext_count, r.plugins.size, r.plugins.size), style = Troy.type.bodySmall, color = c.text2)
                    Text(stringResource(R.string.ext_trust_message), style = Troy.type.body, color = c.text)
                }
            },
            confirmButton = {
                TextButton(onClick = { m.addRepo(r); url = ""; pendingRepo = null }) { Text(stringResource(R.string.ext_trust_accept), color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { pendingRepo = null }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        )
    }
    removeRepo?.let { r ->
        AlertDialog(
            onDismissRequest = { removeRepo = null },
            title = { Text(stringResource(R.string.ext_remove_repo_title, r.name), style = Troy.type.title, color = c.text) },
            text = { Text(stringResource(R.string.ext_remove_repo_message), style = Troy.type.body, color = c.text2) },
            confirmButton = {
                TextButton(onClick = { removeRepo = null; work("remove") { m.removeRepo(r.url) } }) { Text(stringResource(R.string.remove), color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { removeRepo = null }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginRow(
    p: PluginEntry,
    installedVersion: Int?,
    enabled: Boolean?,
    failure: String?,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onEnable: (Boolean) -> Unit,
) {
    val c = Troy.colors
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WebImage(p.iconUrl, null, Modifier.size(40.dp).clip(TroyShapes.Control))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    "v${p.version}",
                    p.language?.uppercase(),
                    p.tvTypes?.takeIf { it.isNotEmpty() }?.joinToString(),
                    p.fileSize?.let { android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, it) },
                    when (p.status) {
                        0 -> stringResource(R.string.ext_status_down)
                        2 -> stringResource(R.string.ext_status_slow)
                        3 -> stringResource(R.string.ext_status_beta)
                        else -> null
                    },
                ).joinToString(" · ")
                Text(meta, style = Troy.type.label, color = c.text3, maxLines = 2)
            }
            if (enabled != null) TroyToggle(enabled, onCheckedChange = onEnable, enabled = !busy)
        }
        p.description?.let { Text(it, style = Troy.type.bodySmall, color = c.text2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        if (p.authors.isNotEmpty()) Text(stringResource(R.string.ext_authors, p.authors.joinToString()), style = Troy.type.label, color = c.text3)
        failure?.let { TroyAlert(stringResource(R.string.ext_load_failed, it), AlertKind.DANGER) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !p.compatible -> Text(stringResource(R.string.ext_incompatible), style = Troy.type.bodySmall, color = c.warn)
                busy -> Text(stringResource(R.string.ext_working), style = Troy.type.bodySmall, color = c.text2)
                installedVersion == null -> TroyButton(stringResource(R.string.ext_install), onInstall)
                installedVersion < p.version -> TroyButton(stringResource(R.string.ext_update), onInstall, kind = ButtonKind.PRIMARY)
            }
            if (installedVersion != null && !busy) TroyButton(stringResource(R.string.ext_uninstall), onUninstall, kind = ButtonKind.GHOST)
        }
    }
}
