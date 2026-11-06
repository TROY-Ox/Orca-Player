package com.troy.orcaplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.HomeRow
import com.troy.orcaplayer.ui.shell.BackTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeRowsViewModel @Inject constructor(private val hub: ContentHub, private val addons: AddonRepository) : ViewModel() {
    /** Every catalog that can be a Home row, Home ones first in their order, with whether it shows. */
    fun current(): List<Pair<HomeRow, Boolean>> {
        val home = hub.homeRows()
        val keys = home.map { it.key }.toSet()
        val others = hub.discoverCatalogs().filter { it.key !in keys && it.decl.requiredExtras.isEmpty() }
        return home.map { it to true } + others.map { it to false }
    }

    fun save(rows: List<Pair<HomeRow, Boolean>>) {
        addons.setHomeLayout(rows.filter { it.second }.map { it.first.key }, rows.filterNot { it.second }.map { it.first.key }.toSet())
    }
}

/** Reorder and hide Home rows across every add-on (17.1). */
@Composable
fun HomeRowsScreen(onBack: () -> Unit, vm: HomeRowsViewModel = hiltViewModel()) {
    val c = Troy.colors
    val rows = remember { mutableStateListOf<Pair<HomeRow, Boolean>>() }
    var changed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { rows.clear(); rows.addAll(vm.current()) }
    fun done() {
        if (changed) vm.save(rows.toList())
        onBack()
    }
    androidx.activity.compose.BackHandler { done() }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.home_rows_title), ::done) {
            TroyButton(stringResource(R.string.home_rows_save), ::done, kind = ButtonKind.GHOST, enabled = changed)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.home_rows_summary), style = Troy.type.bodySmall, color = c.text2) }
            itemsIndexed(rows, key = { _, r -> r.first.key }) { i, (row, shown) ->
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(TroyShapes.Control).background(c.controlFill).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = Troy.type.body, color = if (shown) c.text else c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SectionLabel(row.addonName)
                    }
                    TroyIconButton(Icons.Filled.ArrowUpward, stringResource(R.string.dl_move_up), {
                        if (i > 0) { rows.add(i - 1, rows.removeAt(i)); changed = true }
                    })
                    TroyIconButton(Icons.Filled.ArrowDownward, stringResource(R.string.dl_move_down), {
                        if (i < rows.lastIndex) { rows.add(i + 1, rows.removeAt(i)); changed = true }
                    })
                    TroyToggle(shown, onCheckedChange = { on -> rows[i] = row to on; changed = true })
                }
            }
        }
    }
}
