package com.troy.orcaplayer.ui.addons

import com.troy.orcaplayer.ui.common.keyed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.ShimmerBlock
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.data.RowStyle
import com.troy.orcaplayer.ui.common.TitleCard
import com.troy.orcaplayer.ui.shell.BackTopBar
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip

@Composable
fun DiscoverScreen(onBack: () -> Unit, onOpenTitle: (Title) -> Unit, vm: DiscoverViewModel = hiltViewModel()) {
    val c = Troy.colors
    val catalogs by vm.catalogs.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 8 } }
    LaunchedEffect(grid) { snapshotFlow { nearEnd }.collect { if (it) vm.loadMore() } }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.discover_title), onBack)
        if (catalogs.isEmpty()) {
            EmptyState(Icons.Outlined.Explore, stringResource(R.string.discover_empty_title), stringResource(R.string.discover_empty_message))
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(catalogs, key = { it.key }) { row ->
                TroyChip("${row.name} · ${row.addonName}", selected = s.row?.key == row.key, onClick = { vm.select(row) })
            }
        }
        val row = s.row
        row?.decl?.extra?.filter { it.name != "search" && it.name != "skip" && it.options.isNotEmpty() }?.forEach { extra ->
            SectionLabel(extra.name.replaceFirstChar { it.uppercase() }, Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!extra.isRequired) item { TroyChip(stringResource(R.string.filter_all), selected = s.filters[extra.name] == null, onClick = { vm.setFilter(extra.name, null) }) }
                items(extra.options) { opt -> TroyChip(opt, selected = s.filters[extra.name] == opt, onClick = { vm.setFilter(extra.name, opt) }) }
            }
        }
        when {
            s.needsChoice -> Text(stringResource(R.string.discover_choose), style = Troy.type.body, color = c.text2, modifier = Modifier.padding(20.dp))
            s.items.isEmpty() && s.error != null -> Text(stringResource(R.string.row_failed, s.error ?: ""), style = Troy.type.body, color = c.text2, modifier = Modifier.padding(20.dp))
            s.items.isEmpty() && !s.loading -> Text(stringResource(R.string.row_empty), style = Troy.type.body, color = c.text2, modifier = Modifier.padding(20.dp))
        }
        val style = row?.style?.takeIf { it == RowStyle.LANDSCAPE } ?: RowStyle.POSTER
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (style == RowStyle.LANDSCAPE) 200.dp else 110.dp),
            state = grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(s.items.keyed { it.type.name + it.id }, key = { it.first }) { (_, t) -> TitleCard(t, style, { onOpenTitle(t) }, Modifier.fillMaxWidth()) }
            if (s.loading) items(if (s.items.isEmpty()) 12 else 3) { ShimmerBlock(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(TroyShapes.Control)) }
        }
    }
}
