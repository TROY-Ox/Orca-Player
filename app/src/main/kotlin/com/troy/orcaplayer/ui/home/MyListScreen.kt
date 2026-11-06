@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.troy.orcaplayer.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.shell.BackTopBar

/** The full My List (17.4): view, sort and reorder. */
@Composable
fun MyListScreen(onBack: () -> Unit, onOpenDetails: (String, String, String?) -> Unit, browse: BrowseViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val items by browse.myList.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(MyListSort.CUSTOM) }
    val shown = browse.sorted(items, sort)
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.mylist_title), onBack)
        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.BookmarkBorder,
                title = stringResource(R.string.mylist_empty_title),
                message = stringResource(R.string.mylist_empty_message),
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            )
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MyListSort.entries) { s ->
                TroyChip(
                    stringResource(
                        when (s) {
                            MyListSort.CUSTOM -> R.string.mylist_sort_custom
                            MyListSort.ADDED -> R.string.mylist_sort_added
                            MyListSort.NAME -> R.string.mylist_sort_name
                            MyListSort.YEAR -> R.string.mylist_sort_year
                        },
                    ),
                    sort == s, { sort = s },
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(shown, key = { _, e -> e.itemKey }) { i, e ->
                Row(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().troyFocusRing().clip(TroyShapes.Control)
                        .clickable(role = Role.Button) {
                            when (val t = browse.open(e)) {
                                is OpenTarget.Play -> PlayerLaunch.play(context, t.request)
                                is OpenTarget.Details -> onOpenDetails(t.type, t.id, t.play)
                                null -> Toast.makeText(context, R.string.cw_unavailable, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .background(c.controlFill).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp, 84.dp).clip(TroyShapes.Chip)) { WebImage(e.poster, null, Modifier.fillMaxSize()) }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = Troy.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(e.year?.toString(), e.sourceName).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = Troy.type.label, color = c.text3, maxLines = 1)
                    }
                    if (sort == MyListSort.CUSTOM) {
                        TroyIconButton(Icons.Filled.ArrowUpward, stringResource(R.string.dl_move_up), {
                            if (i > 0) browse.reorder(shown.map { it.itemKey }.toMutableList().apply { add(i - 1, removeAt(i)) })
                        })
                        TroyIconButton(Icons.Filled.ArrowDownward, stringResource(R.string.dl_move_down), {
                            if (i < shown.lastIndex) browse.reorder(shown.map { it.itemKey }.toMutableList().apply { add(i + 1, removeAt(i)) })
                        })
                    }
                    TroyIconButton(Icons.Outlined.Close, stringResource(R.string.mylist_remove), { browse.remove(e.itemKey) })
                }
            }
        }
    }
}
