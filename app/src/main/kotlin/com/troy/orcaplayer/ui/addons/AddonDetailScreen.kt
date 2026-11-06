package com.troy.orcaplayer.ui.addons

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
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
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Profile
import com.troy.orcaplayer.data.AddonList
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.CatalogPlace
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.Health
import com.troy.orcaplayer.data.InstalledAddon
import com.troy.orcaplayer.data.RowStyle
import com.troy.orcaplayer.data.StoreJson
import com.troy.orcaplayer.sources.stremio.AddonManifest
import com.troy.orcaplayer.sources.stremio.StremioUrls
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.shell.BackTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AddonDetailViewModel @Inject constructor(
    val repo: AddonRepository,
    hub: ContentHub,
    saved: SavedStateHandle,
) : ViewModel() {
    val id: String = saved.get<String>("id").orEmpty()
    val list: StateFlow<AddonList> = repo.list
    val health: StateFlow<Map<String, Health>> = repo.health
    val profile: StateFlow<Profile?> = hub.profile

    fun edit(change: (InstalledAddon) -> InstalledAddon) = repo.edit(id, change)
    fun editCatalog(key: String, change: (com.troy.orcaplayer.data.CatalogPref) -> com.troy.orcaplayer.data.CatalogPref) = repo.editCatalog(id, key, change)
    fun moveCatalog(key: String, delta: Int) = repo.moveCatalog(id, key, delta)
    fun setTrending(key: String?) = repo.setTrending(if (key == null) null else id, key)
    fun markSeen() = repo.markSeen(id)
    fun remove() = repo.remove(setOf(id))
    fun rawManifest(a: InstalledAddon): String =
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(AddonManifest.serializer(), a.manifest)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddonDetailScreen(onBack: () -> Unit, vm: AddonDetailViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val list by vm.list.collectAsStateWithLifecycle()
    val healthMap by vm.health.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val a = list.addons.firstOrNull { it.id == vm.id }
    var raw by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(a?.manifest?.name ?: "", onBack)
        if (a == null) return@Column
        val m = a.manifest
        val h = healthMap[a.id]
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WebImage(m.logo, null, Modifier.size(56.dp).clip(TroyShapes.Control))
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = Troy.type.title, color = c.text)
                        Text("v${m.version} · ${m.id}", style = Troy.type.mono, color = c.text2)
                    }
                }
                m.description?.let { Text(it, style = Troy.type.body, color = c.text2) }
                val stats = listOfNotNull(
                    h?.let { stringResource(R.string.addon_stats, it.avgMs, (it.errorRate * 100).toInt()) },
                    (h?.lastError ?: a.lastError)?.let { stringResource(R.string.addon_last_error, it) },
                )
                stats.forEach { Text(it, style = Troy.type.bodySmall, color = c.text2) }
                if (a.updatedFrom != null) {
                    SectionLabel(stringResource(R.string.addon_updated_from, a.updatedFrom))
                    if (a.changes.isEmpty()) Text(stringResource(R.string.addon_no_changes), style = Troy.type.bodySmall, color = c.text2)
                    a.changes.forEach { Text(it, style = Troy.type.bodySmall, color = c.text) }
                    TroyButton(stringResource(R.string.addon_mark_seen), vm::markSeen, kind = ButtonKind.GHOST)
                }
            }

            Card {
                SectionLabel(stringResource(R.string.addon_use))
                Toggle(stringResource(R.string.filter_enabled), a.enabled) { v -> vm.edit { it.copy(enabled = v) } }
                profile?.let { p ->
                    if (p.isKids && a.adult) {
                        Text(stringResource(R.string.addon_adult_kids), style = Troy.type.bodySmall, color = c.text2)
                    } else {
                        val on = if (p.isKids) p.id in a.onForKids else p.id !in a.offForProfiles
                        Toggle(stringResource(R.string.addon_on_for_profile, p.name), on) { v ->
                            vm.edit {
                                if (p.isKids) it.copy(onForKids = if (v) it.onForKids + p.id else it.onForKids - p.id)
                                else it.copy(offForProfiles = if (v) it.offForProfiles - p.id else it.offForProfiles + p.id)
                            }
                        }
                    }
                }
                Toggle(stringResource(R.string.addon_use_catalogs), a.useCatalogs) { v -> vm.edit { it.copy(useCatalogs = v) } }
                Toggle(stringResource(R.string.addon_use_details), a.useDetails) { v -> vm.edit { it.copy(useDetails = v) } }
                Toggle(stringResource(R.string.addon_use_streams), a.useStreams) { v -> vm.edit { it.copy(useStreams = v) } }
                Toggle(stringResource(R.string.addon_use_subtitles), a.useSubtitles) { v -> vm.edit { it.copy(useSubtitles = v) } }
                Toggle(stringResource(R.string.addon_in_search), a.inSearch) { v -> vm.edit { it.copy(inSearch = v) } }
                Toggle(stringResource(R.string.addon_allow_downloads), a.allowDownloads) { v -> vm.edit { it.copy(allowDownloads = v) } }
                SectionLabel(stringResource(R.string.setting_addon_timeout))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroyChip(stringResource(R.string.timeout_default), a.timeoutSec == null, { vm.edit { it.copy(timeoutSec = null) } })
                    listOf(5, 10, 20, 30).forEach { s -> TroyChip(stringResource(R.string.seconds_n, s), a.timeoutSec == s, { vm.edit { it.copy(timeoutSec = s) } }) }
                }
            }

            if (m.catalogs.isNotEmpty()) {
                Card {
                    SectionLabel(stringResource(R.string.addon_catalog_editor))
                    val cats = a.effectiveCatalogs()
                    cats.forEachIndexed { i, (decl, pref) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(pref.name ?: decl.name, style = Troy.type.body, color = c.text)
                                    Text("${decl.type} · ${decl.id}", style = Troy.type.label, color = c.text3)
                                }
                                TroyIconButton(Icons.Outlined.Edit, stringResource(R.string.rename), { renaming = decl.key })
                                if (i > 0) TroyIconButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up), { vm.moveCatalog(decl.key, -1) })
                                if (i < cats.lastIndex) TroyIconButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down), { vm.moveCatalog(decl.key, 1) })
                            }
                            if (!decl.searchOnly) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val homeAllowed = decl.requiredExtras.none { it.name != "search" } || pref.defaultGenre != null
                                    if (homeAllowed) TroyChip(stringResource(R.string.place_home), pref.place == CatalogPlace.HOME, { vm.editCatalog(decl.key) { it.copy(place = CatalogPlace.HOME) } })
                                    TroyChip(stringResource(R.string.place_discover), pref.place == CatalogPlace.DISCOVER, { vm.editCatalog(decl.key) { it.copy(place = CatalogPlace.DISCOVER) } })
                                    TroyChip(stringResource(R.string.place_hidden), pref.place == CatalogPlace.HIDDEN, { vm.editCatalog(decl.key) { it.copy(place = CatalogPlace.HIDDEN) } })
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RowStyle.entries.forEach { st -> TroyChip(stringResource(st.label()), pref.rowStyle == st, { vm.editCatalog(decl.key) { it.copy(rowStyle = st) } }) }
                                }
                                if (decl.genres.isNotEmpty()) {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val required = decl.requiredExtras.any { it.name == "genre" }
                                        if (!required) TroyChip(stringResource(R.string.genre_any), pref.defaultGenre == null, { vm.editCatalog(decl.key) { it.copy(defaultGenre = null) } })
                                        decl.genres.forEach { g -> TroyChip(g, pref.defaultGenre == g, { vm.editCatalog(decl.key) { it.copy(defaultGenre = g) } }) }
                                    }
                                }
                                val trendingKey = "${a.id}|${decl.key}"
                                Toggle(stringResource(R.string.addon_trending), list.trending == trendingKey) { v -> vm.setTrending(if (v) decl.key else null) }
                            }
                        }
                    }
                }
            }

            Card {
                SectionLabel(stringResource(R.string.addon_actions))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.behaviorHints.configurable || m.behaviorHints.configurationRequired) {
                        TroyButton(stringResource(R.string.addon_configure_again), { openCustomTab(context, StremioUrls.configureUrl(a.manifestUrl)) })
                    }
                    TroyButton(stringResource(R.string.addon_copy_link), {
                        copyText(context, m.name, a.manifestUrl)
                        android.widget.Toast.makeText(context, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
                    })
                    TroyButton(stringResource(R.string.share), {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.manifestUrl), null))
                    })
                    TroyButton(stringResource(R.string.addon_raw), { raw = true })
                    TroyButton(stringResource(R.string.remove), { confirmRemove = true })
                }
            }
        }

        if (raw) {
            AlertDialog(
                onDismissRequest = { raw = false },
                title = { Text(stringResource(R.string.addon_raw), style = Troy.type.title, color = c.text) },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text(vm.rawManifest(a), style = Troy.type.mono, color = c.text2)
                    }
                },
                confirmButton = { TextButton(onClick = { raw = false }) { Text(stringResource(R.string.close), color = c.text2) } },
                containerColor = Troy.glass.panel.copy(alpha = 1f),
            )
        }
        renaming?.let { key ->
            var text by remember(key) { mutableStateOf(a.prefFor(key).name ?: m.catalogs.firstOrNull { it.key == key }?.name.orEmpty()) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text(stringResource(R.string.rename), style = Troy.type.title, color = c.text) },
                text = { TroyTextField(text, { text = it }, Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.editCatalog(key) { it.copy(name = text.trim().ifEmpty { null }) }
                        renaming = null
                    }) { Text(stringResource(R.string.ok), color = c.accent) }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
                containerColor = Troy.glass.panel.copy(alpha = 1f),
            )
        }
        if (confirmRemove) {
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text(stringResource(R.string.addon_remove_title, m.name), style = Troy.type.title, color = c.text) },
                text = { Text(stringResource(R.string.addon_remove_message), style = Troy.type.body, color = c.text2) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRemove = false
                        vm.remove()
                        onBack()
                    }) { Text(stringResource(R.string.remove), color = c.danger) }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
                containerColor = Troy.glass.panel.copy(alpha = 1f),
            )
        }
    }
}

private fun RowStyle.label(): Int = when (this) {
    RowStyle.POSTER -> R.string.row_style_poster
    RowStyle.LANDSCAPE -> R.string.row_style_landscape
    RowStyle.LARGE -> R.string.row_style_large
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).troyFocusRing().clip(TroyShapes.Control)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Troy.type.body, color = Troy.colors.text, modifier = Modifier.weight(1f))
        TroyToggle(checked, onCheckedChange = null)
    }
}
