package com.troy.orcaplayer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.ui.common.RowState
import com.troy.orcaplayer.ui.common.TitleRow
import com.troy.orcaplayer.ui.shell.MainTopBar

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenTitle: (Title) -> Unit,
    onOpenCatalog: (addonId: String, catalogKey: String) -> Unit,
    onOpenUrlPlayer: () -> Unit,
    onOpenAddons: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val c = Troy.colors
    val rows by vm.rows.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val hasAddons by vm.hasAddons.collectAsStateWithLifecycle()
    val list = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        MainTopBar(stringResource(R.string.tab_home), onOpenSettings, onOpenProfiles) {
            if (rows.isNotEmpty()) TroyIconButton(Icons.Outlined.Refresh, stringResource(R.string.refresh), vm::refresh)
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item(key = "url") {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().glass(TroyShapes.Panel).troyFocusRing()
                        .clickable(role = Role.Button, onClick = onOpenUrlPlayer).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = null, tint = c.accent, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.url_player_title), style = Troy.type.title, color = c.text)
                        Text(stringResource(R.string.url_player_summary), style = Troy.type.bodySmall, color = c.text2)
                    }
                }
            }
            if (!hasAddons) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.Extension,
                        title = stringResource(R.string.home_no_sources_title),
                        message = stringResource(R.string.home_no_sources_message),
                        actionLabel = stringResource(R.string.addons_add),
                        onAction = onOpenAddons,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            }
            items(rows, key = { it.key }) { row ->
                TitleRow(
                    name = row.name,
                    subtitle = row.addonName,
                    state = states[row.key] ?: RowState(),
                    style = row.style,
                    onOpen = onOpenTitle,
                    onNeedMore = { vm.loadMore(row) },
                    onSeeAll = { onOpenCatalog(row.addonId, row.decl.key) },
                )
            }
        }
    }
}
