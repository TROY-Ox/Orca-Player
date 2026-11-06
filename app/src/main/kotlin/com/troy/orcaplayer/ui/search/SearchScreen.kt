package com.troy.orcaplayer.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.data.RowStyle
import com.troy.orcaplayer.ui.common.TitleCard
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.shell.MainTopBar
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(onOpenSettings: () -> Unit, onOpenProfiles: () -> Unit, onOpenTitle: (Title) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val localResults by vm.localResults.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val trending by vm.trending.collectAsStateWithLifecycle()
    val typeFilter by vm.typeFilter.collectAsStateWithLifecycle()
    val sourceFilter by vm.sourceFilter.collectAsStateWithLifecycle()

    val sources = results.items.flatMap { it.sources }.distinctBy { it.sourceId }
    val shown = results.items.filter { m ->
        (typeFilter == null || m.title.type == typeFilter || (typeFilter == TitleType.TV && m.title.type == TitleType.CHANNEL)) && (sourceFilter == null || m.sources.any { it.sourceId == sourceFilter })
    }

    Column(Modifier.fillMaxSize()) {
        MainTopBar(stringResource(R.string.tab_search), onOpenSettings, onOpenProfiles)
        TroyTextField(
            query, { vm.query.value = it },
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            placeholder = stringResource(R.string.search_hint),
            imeAction = ImeAction.Search,
            onImeAction = vm::submit,
            leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = c.text3) },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (query.trim().length < 2) {
                if (recent.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel(stringResource(R.string.search_recent), Modifier.weight(1f))
                            TroyButton(stringResource(R.string.clear), vm::clearRecent, kind = ButtonKind.GHOST)
                        }
                    }
                    items(recent, span = { GridItemSpan(maxLineSpan) }) { q ->
                        Row(
                            Modifier.fillMaxWidth().troyFocusRing().clip(TroyShapes.Control).clickable(role = Role.Button) { vm.query.value = q }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.History, contentDescription = null, tint = c.text3)
                            Spacer(Modifier.size(12.dp))
                            Text(q, style = Troy.type.body, color = c.text)
                        }
                    }
                }
                if (trending.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.search_trending), Modifier.padding(top = 8.dp)) }
                    items(trending, key = { "tr" + it.type + it.id }) { t -> TitleCard(t, RowStyle.POSTER, { onOpenTitle(t) }, Modifier.fillMaxWidth()) }
                }
                if (recent.isEmpty() && trending.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.search_intro), style = Troy.type.body, color = c.text2, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
                return@LazyVerticalGrid
            }
            if (localResults.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.search_local)) }
                items(localResults, key = { "l" + it.id }, span = { GridItemSpan(maxLineSpan) }) { m ->
                    Row(
                        Modifier.fillMaxWidth().troyFocusRing().clip(TroyShapes.Control)
                            .clickable(role = Role.Button) { scope.launch { PlayerLaunch.play(context, vm.playLocal(m)) } }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PlayCircle, contentDescription = null, tint = c.accent)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.folderName, style = Troy.type.bodySmall, color = c.text2, maxLines = 1)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(
                        if (results.pending > 0) stringResource(R.string.search_sources_pending, results.pending)
                        else stringResource(R.string.search_sources),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { TroyChip(stringResource(R.string.filter_all), typeFilter == null, { vm.typeFilter.value = null }) }
                        item { TroyChip(stringResource(R.string.type_movies), typeFilter == TitleType.MOVIE, { vm.typeFilter.value = TitleType.MOVIE }) }
                        item { TroyChip(stringResource(R.string.type_series), typeFilter == TitleType.SERIES, { vm.typeFilter.value = TitleType.SERIES }) }
                        item { TroyChip(stringResource(R.string.type_channels), typeFilter == TitleType.CHANNEL || typeFilter == TitleType.TV, { vm.typeFilter.value = TitleType.TV }) }
                    }
                    if (sources.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { TroyChip(stringResource(R.string.search_all_sources), sourceFilter == null, { vm.sourceFilter.value = null }) }
                            items(sources, key = { it.sourceId }) { s -> TroyChip(s.sourceName, sourceFilter == s.sourceId, { vm.sourceFilter.value = s.sourceId }) }
                        }
                    }
                    if (results.failed.isNotEmpty()) {
                        Text(stringResource(R.string.search_failed, results.failed.joinToString(", ")), style = Troy.type.bodySmall, color = c.text3)
                    }
                    if (shown.isEmpty() && results.pending == 0 && localResults.isEmpty()) {
                        Text(stringResource(R.string.search_nothing, query.trim()), style = Troy.type.body, color = c.text2, modifier = Modifier.padding(vertical = 16.dp))
                    }
                }
            }
            items(shown, key = { it.title.type.name + it.title.id + it.title.source.sourceId }) { m ->
                Column {
                    TitleCard(m.title, RowStyle.POSTER, { onOpenTitle(m.title) }, Modifier.fillMaxWidth())
                    if (m.sources.size > 1) Text(m.sources.joinToString(" · ") { it.sourceName }, style = Troy.type.label, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
