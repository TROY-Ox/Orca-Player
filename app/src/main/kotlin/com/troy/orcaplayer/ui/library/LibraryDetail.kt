package com.troy.orcaplayer.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.data.LibraryItemEntity
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.library.TmdbResult
import com.troy.orcaplayer.ui.common.ConfirmDialog
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.shell.BackTopBar
import kotlinx.coroutines.launch

/** One movie in the library: details, Play, watched mark, Fix match and Edit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryMovieScreen(id: String, onBack: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val content by vm.content.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val movie = content.movies.firstOrNull { it.id == id }
        ?: content.shows.flatMap { it.episodes }.firstOrNull { it.id == id }
    var fixing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var asEpisode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(movie?.title.orEmpty(), onBack = onBack) {}
        if (movie == null) {
            if (content.loaded) LaunchedEffect(Unit) { onBack() }
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Hero(movie.backdrop ?: movie.poster, movie.title)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(movie.title, style = Troy.type.title, color = Troy.colors.text)
                    if (movie.kind == com.troy.orcaplayer.library.Library.KIND_EPISODE) {
                        Text(LibraryViewModel.episodeLabel(movie), style = Troy.type.body, color = Troy.colors.text2)
                    }
                    Meta(listOfNotNull(movie.year?.toString(), movie.rating?.let { "★ " + rating(it) }, movie.genres, runtime(movie.durationMs)))
                    movie.overview?.let { Text(it, style = Troy.type.body, color = Troy.colors.text2) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyButton(stringResource(R.string.library_play), {
                            scope.launch { PlayerLaunch.play(context, vm.request(movie)) }
                        }, kind = ButtonKind.PRIMARY, icon = Icons.Filled.PlayArrow)
                        val isWatched = movie.id in watched
                        TroyButton(
                            stringResource(if (isWatched) R.string.library_mark_unwatched else R.string.library_mark_watched),
                            { vm.setWatched(listOf(movie.id), !isWatched) },
                            icon = if (isWatched) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        )
                        if (movie.kind == com.troy.orcaplayer.library.Library.KIND_EPISODE) {
                            TroyButton(stringResource(R.string.library_is_movie), { vm.makeMovie(movie.id); onBack() }, kind = ButtonKind.GHOST)
                        } else {
                            TroyButton(stringResource(R.string.library_fix_match), { fixing = true }, kind = ButtonKind.GHOST)
                            TroyButton(stringResource(R.string.library_edit), { editing = true }, kind = ButtonKind.GHOST)
                            TroyButton(stringResource(R.string.library_is_episode), { asEpisode = true }, kind = ButtonKind.GHOST)
                        }
                    }
                    FileLine(movie)
                }
            }
        }
    }
    val m = movie ?: return
    if (fixing) FixMatchDialog(vm, m.title, m.year, series = false, onDismiss = { fixing = false }) { vm.fixMovie(m.id, it.id) }
    if (editing) EditDialog(m.title, m.year, onDismiss = { editing = false }) { t, y -> vm.editMovie(m.id, t, y) }
    if (asEpisode) EpisodeDialog(m.title, onDismiss = { asEpisode = false }) { show, s, e -> vm.makeEpisode(m.id, show, s, e); onBack() }
}

/** A series: seasons, episodes with watched marks, and Fix match for the whole show. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryShowScreen(showKey: String, onBack: () -> Unit, onOpenItem: (String) -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val c = Troy.colors
    val content by vm.content.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val show = content.shows.firstOrNull { it.show.showKey == showKey }
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    var fixing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(show?.show?.name.orEmpty(), onBack = onBack) {}
        if (show == null) {
            if (content.loaded) LaunchedEffect(Unit) { onBack() }
            return@Column
        }
        val current = season?.takeIf { it in show.seasons } ?: show.seasons.firstOrNull { it > 0 } ?: show.seasons.firstOrNull()
        val episodes = show.episodes.filter { it.season == current }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Hero(show.show.backdrop ?: show.show.poster, show.show.name)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(show.show.name, style = Troy.type.title, color = c.text)
                    Meta(listOfNotNull(show.show.year?.toString(), show.show.rating?.let { "★ " + rating(it) }, show.show.genres))
                    show.show.overview?.let { Text(it, style = Troy.type.body, color = c.text2, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val next = show.episodes.firstOrNull { it.id !in watched } ?: show.episodes.first()
                        TroyButton(
                            stringResource(R.string.library_play_episode, LibraryViewModel.episodeLabel(next)),
                            { scope.launch { PlayerLaunch.play(context, vm.request(next, show.episodes)) } },
                            kind = ButtonKind.PRIMARY, icon = Icons.Filled.PlayArrow,
                        )
                        TroyButton(stringResource(R.string.library_fix_match), { fixing = true }, kind = ButtonKind.GHOST)
                        TroyButton(stringResource(R.string.library_edit), { editing = true }, kind = ButtonKind.GHOST)
                    }
                    if (show.seasons.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            show.seasons.forEach { s ->
                                TroyChip(
                                    if (s == 0) stringResource(R.string.library_specials) else stringResource(R.string.library_season, s),
                                    s == current, { season = s },
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.library_episodes, episodes.size), style = Troy.type.label, color = c.text2, modifier = Modifier.weight(1f))
                        val all = episodes.all { it.id in watched }
                        TroyButton(
                            stringResource(if (all) R.string.library_mark_unwatched else R.string.library_mark_season_watched),
                            { vm.setWatched(episodes.map { it.id }, !all) }, kind = ButtonKind.GHOST,
                        )
                    }
                }
            }
            items(episodes, key = { it.id }) { e ->
                val isWatched = e.id in watched
                Row(
                    Modifier.fillMaxWidth().troyFocusRing()
                        .clickable(role = Role.Button) { scope.launch { PlayerLaunch.play(context, vm.request(e, show.episodes)) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                        WebImage(e.still ?: show.show.backdrop, null, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${e.episode ?: "?"}. " + (e.episodeTitle ?: e.fileName.substringBeforeLast('.')),
                            style = Troy.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        runtime(e.durationMs)?.let { Text(it, style = Troy.type.bodySmall, color = c.text2) }
                    }
                    Icon(
                        if (isWatched) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = stringResource(if (isWatched) R.string.library_mark_unwatched else R.string.library_mark_watched),
                        tint = if (isWatched) c.accent else c.text3,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { vm.setWatched(listOf(e.id), !isWatched) }.padding(8.dp),
                    )
                    TroyIconButton(Icons.Filled.MoreVert, stringResource(R.string.library_details), { onOpenItem(e.id) }, tint = c.text2)
                }
            }
        }
    }
    val s = show ?: return
    if (fixing) FixMatchDialog(vm, s.show.name, s.show.year, series = true, onDismiss = { fixing = false }) { vm.fixShow(s.show.showKey, it.id) }
    if (editing) EditDialog(s.show.name, s.show.year, onDismiss = { editing = false }) { t, y -> vm.editShow(s.show.showKey, t, y) }
}

@Composable
private fun Hero(image: String?, name: String) {
    val c = Troy.colors
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(max = 320.dp)) {
        WebImage(image, name, Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg))))
    }
}

@Composable
private fun Meta(parts: List<String>) {
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = Troy.type.bodySmall, color = Troy.colors.text2)
}

@Composable
private fun FileLine(item: LibraryItemEntity) {
    val context = LocalContext.current
    val size = if (item.sizeBytes > 0) Formatter.formatShortFileSize(context, item.sizeBytes) else null
    Text(listOfNotNull(item.fileName, size).joinToString(" · "), style = Troy.type.label, color = Troy.colors.text3)
}

private fun runtime(ms: Long): String? {
    if (ms <= 0) return null
    val min = ms / 60_000
    return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
}

/** Search TMDB by name and year and pick the right title. */
@Composable
private fun FixMatchDialog(
    vm: LibraryViewModel,
    initialTitle: String,
    initialYear: Int?,
    series: Boolean,
    onDismiss: () -> Unit,
    onPick: (TmdbResult) -> Unit,
) {
    val c = Troy.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialTitle) }
    var year by remember { mutableStateOf(initialYear?.toString().orEmpty()) }
    var results by remember { mutableStateOf<List<TmdbResult>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val noKey = vm.options().key.isBlank()
    fun search() {
        busy = true; error = null
        scope.launch {
            runCatching { vm.search(query.trim(), year.toIntOrNull(), series) }
                .onSuccess { results = it }
                .onFailure { error = it.message }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (!noKey) search() }
    ConfirmDialog(
        title = stringResource(R.string.library_fix_match),
        message = stringResource(if (noKey) R.string.library_no_key else R.string.library_fix_message),
        confirm = stringResource(R.string.library_search),
        onConfirm = { if (!noKey) search() },
        onDismiss = onDismiss,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.library_title_hint), onImeAction = { search() })
                TroyTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.library_year_hint), keyboardType = KeyboardType.Number)
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                error?.let { Text(it, style = Troy.type.bodySmall, color = c.danger) }
                results?.let { list ->
                    if (list.isEmpty()) Text(stringResource(R.string.library_no_results), style = Troy.type.body, color = c.text2)
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(list.take(20), key = { it.id }) { r ->
                            Row(
                                Modifier.fillMaxWidth().troyFocusRing().clickable(role = Role.Button) { onPick(r); onDismiss() }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp))) {
                                    WebImage(com.troy.orcaplayer.library.Tmdb.image(r.posterPath, "w92"), null, Modifier.fillMaxSize())
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(r.displayName, style = Troy.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    r.year?.let { Text(it.toString(), style = Troy.type.bodySmall, color = c.text2) }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EditDialog(title: String, year: Int?, onDismiss: () -> Unit, onSave: (String, Int?) -> Unit) {
    var t by remember { mutableStateOf(title) }
    var y by remember { mutableStateOf(year?.toString().orEmpty()) }
    ConfirmDialog(
        title = stringResource(R.string.library_edit),
        message = stringResource(R.string.library_edit_message),
        confirm = stringResource(R.string.network_save),
        onConfirm = { if (t.isNotBlank()) { onSave(t.trim(), y.toIntOrNull()); onDismiss() } },
        onDismiss = onDismiss,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyTextField(t, { t = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.library_title_hint))
                TroyTextField(y, { y = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.library_year_hint), keyboardType = KeyboardType.Number)
            }
        },
    )
}

@Composable
private fun EpisodeDialog(title: String, onDismiss: () -> Unit, onSave: (String, Int?, Int?) -> Unit) {
    var show by remember { mutableStateOf(title) }
    var season by remember { mutableStateOf("1") }
    var episode by remember { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(R.string.library_is_episode),
        message = stringResource(R.string.library_is_episode_message),
        confirm = stringResource(R.string.network_save),
        onConfirm = { if (show.isNotBlank()) { onSave(show.trim(), season.toIntOrNull(), episode.toIntOrNull()); onDismiss() } },
        onDismiss = onDismiss,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TroyTextField(show, { show = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.library_show_hint))
                TroyTextField(season, { season = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.library_season_hint), keyboardType = KeyboardType.Number)
                TroyTextField(episode, { episode = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.library_episode_hint), keyboardType = KeyboardType.Number)
            }
        },
    )
}
