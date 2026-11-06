package com.troy.orcaplayer.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.data.LibraryItemEntity
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.core.settings.ExtrasSettings
import com.troy.orcaplayer.data.RowStyle
import com.troy.orcaplayer.library.Tmdb
import com.troy.orcaplayer.ui.common.TitleCard
import com.troy.orcaplayer.ui.shell.BackTopBar
import com.troy.orcaplayer.core.settings.R as SR

private enum class LibraryTab { MOVIES, SERIES, COLLECTIONS }
private enum class WatchFilter { ALL, UNWATCHED, WATCHED }

/** Movies and series from the user's folders and shares, with TMDB posters when a key is set (20.1). */
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val c = Troy.colors
    val content by vm.content.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.MOVIES) }
    var filter by rememberSaveable { mutableStateOf(WatchFilter.ALL) }
    var collection by rememberSaveable { mutableStateOf<Int?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort = settings[ExtrasSettings.LibrarySort]

    BackHandler(enabled = collection != null) { collection = null }

    Column(Modifier.fillMaxSize()) {
        val collectionName = content.collections.firstOrNull { it.id == collection }?.name
        BackTopBar(collectionName ?: stringResource(R.string.library_title), onBack = { if (collection != null) collection = null else onBack() }) {
            TroyIconButton(Icons.Filled.Refresh, stringResource(R.string.library_rescan), { vm.rescan() })
            Box {
                TroyIconButton(Icons.AutoMirrored.Filled.Sort, stringResource(SR.string.setting_library_sort), { sortMenu = true })
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, containerColor = c.glassSolid) {
                    listOf(
                        "added" to SR.string.sort_added, "title" to SR.string.sort_title, "year" to SR.string.sort_year,
                        "rating" to SR.string.sort_rating, "watched" to SR.string.sort_last_watched,
                    ).forEach { (k, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label), color = if (k == sort) c.accent else c.text) },
                            onClick = { vm.setSort(k); sortMenu = false },
                        )
                    }
                }
            }
        }
        scan?.let { s ->
            if (s.error != null) {
                TroyAlert(stringResource(R.string.library_scan_failed, s.error.orEmpty()), AlertKind.DANGER, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val label = if (s.matching > 0) stringResource(R.string.library_matching, s.matched, s.matching)
                    else stringResource(R.string.library_scanning, s.found)
                    Text(label, style = Troy.type.bodySmall, color = c.text2)
                    if (s.matching > 0) LinearProgressIndicator(progress = { s.matched.toFloat() / s.matching }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.controlFill)
                    else LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.controlFill)
                }
            }
        }
        if (settings[ExtrasSettings.LibraryOnline] && settings[ExtrasSettings.TmdbKey].isBlank()) {
            TroyAlert(stringResource(R.string.library_no_key), AlertKind.INFO, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (collection == null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyChip(stringResource(R.string.library_movies), tab == LibraryTab.MOVIES, { tab = LibraryTab.MOVIES })
                TroyChip(stringResource(R.string.library_series), tab == LibraryTab.SERIES, { tab = LibraryTab.SERIES })
                if (content.collections.isNotEmpty()) TroyChip(stringResource(R.string.library_collections), tab == LibraryTab.COLLECTIONS, { tab = LibraryTab.COLLECTIONS })
                if (tab != LibraryTab.COLLECTIONS) {
                    Box(Modifier.padding(horizontal = 4.dp))
                    TroyChip(stringResource(R.string.library_all), filter == WatchFilter.ALL, { filter = WatchFilter.ALL })
                    TroyChip(stringResource(R.string.library_unwatched), filter == WatchFilter.UNWATCHED, { filter = WatchFilter.UNWATCHED })
                    TroyChip(stringResource(R.string.library_watched), filter == WatchFilter.WATCHED, { filter = WatchFilter.WATCHED })
                }
            }
        }

        fun passes(ids: List<String>): Boolean = when (filter) {
            WatchFilter.ALL -> true
            WatchFilter.WATCHED -> ids.isNotEmpty() && ids.all { it in watched }
            WatchFilter.UNWATCHED -> ids.any { it !in watched }
        }
        val lastWatched: (List<String>) -> Long = { ids -> ids.maxOfOrNull { watched[it] ?: 0L } ?: 0L }

        val cards: List<Pair<Title, () -> Unit>> = when {
            collection != null -> content.collections.firstOrNull { it.id == collection }?.movies.orEmpty().map { m -> m.toTitle() to { onOpenMovie(m.id) } }
            tab == LibraryTab.MOVIES -> content.movies.filter { passes(listOf(it.id)) }
                .let { sortMovies(it, sort, lastWatched) }
                .map { m -> m.toTitle() to { onOpenMovie(m.id) } }
            tab == LibraryTab.SERIES -> content.shows.filter { passes(it.episodes.map { e -> e.id }) }
                .let { list ->
                    when (sort) {
                        "title" -> list.sortedBy { it.show.name.lowercase() }
                        "year" -> list.sortedByDescending { it.show.year ?: 0 }
                        "rating" -> list.sortedByDescending { it.show.rating ?: 0.0 }
                        "watched" -> list.sortedByDescending { s -> lastWatched(s.episodes.map { it.id }) }
                        else -> list.sortedByDescending { it.lastAdded }
                    }
                }
                .map { s ->
                    Title(
                        id = s.show.showKey, source = LIBRARY, type = TitleType.SERIES, name = s.show.name,
                        poster = s.show.poster, background = s.show.backdrop, year = s.show.year, rating = s.show.rating?.let(::rating),
                    ) to { onOpenShow(s.show.showKey) }
                }
            else -> content.collections.map { col ->
                val first = col.movies.first()
                Title(id = "c${col.id}", source = LIBRARY, type = TitleType.MOVIE, name = col.name, poster = first.poster, background = first.backdrop) to { collection = col.id }
            }
        }

        if (content.loaded && cards.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = stringResource(R.string.library_empty),
                message = stringResource(R.string.library_empty_message),
                actionLabel = stringResource(R.string.library_rescan),
                onAction = { vm.rescan() },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(cards, key = { it.first.id }) { (title, open) ->
                    TitleCard(title, RowStyle.POSTER, onClick = open, modifier = Modifier.fillMaxWidth(), onLongClick = {})
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (settings[ExtrasSettings.LibraryOnline]) {
                        Text(Tmdb.ATTRIBUTION, style = Troy.type.label, color = c.text3, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

internal val LIBRARY = SourceRef(SourceKind.LOCAL, "library")

internal fun rating(r: Double): String = String.format(java.util.Locale.ROOT, "%.1f", r)

internal fun LibraryItemEntity.toTitle() = Title(
    id = id, source = LIBRARY, type = TitleType.MOVIE, name = title, poster = poster, background = backdrop,
    description = overview, year = year, rating = rating?.let(::rating), genres = genres?.split(", ").orEmpty(),
)

private fun sortMovies(list: List<LibraryItemEntity>, sort: String, lastWatched: (List<String>) -> Long) = when (sort) {
    "title" -> list.sortedBy { it.title.lowercase() }
    "year" -> list.sortedByDescending { it.year ?: 0 }
    "rating" -> list.sortedByDescending { it.rating ?: 0.0 }
    "watched" -> list.sortedByDescending { lastWatched(listOf(it.id)) }
    else -> list.sortedByDescending { it.addedAt }
}
