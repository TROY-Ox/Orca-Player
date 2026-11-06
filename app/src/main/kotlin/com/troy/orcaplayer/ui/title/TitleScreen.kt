package com.troy.orcaplayer.ui.title

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import android.widget.Toast
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.CheckCircle
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.settings.SourceSettings
import com.troy.orcaplayer.downloads.logic.DownloadStatus
import com.troy.orcaplayer.ui.downloads.BulkDownloadDialog
import com.troy.orcaplayer.ui.downloads.DownloadOptionsDialog
import com.troy.orcaplayer.ui.downloads.DownloadsViewModel
import com.troy.orcaplayer.ui.downloads.rememberDownloadGuard
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.ShimmerBlock
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Episode
import com.troy.orcaplayer.core.model.StreamKind
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.data.StreamEntry
import com.troy.orcaplayer.sources.api.AudioTag
import com.troy.orcaplayer.sources.api.HdrTag
import com.troy.orcaplayer.sources.api.StreamSort
import com.troy.orcaplayer.ui.common.Badge
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.shell.BackTopBar

private val pickerLanguages = listOf("ar", "en", "fr", "es", "de", "tr", "hi", "ja", "ko")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleScreen(onBack: () -> Unit, vm: TitleViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val title by vm.title.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var confirmOpen by remember { mutableStateOf<TitleEvent.Open?>(null) }
    val dl: DownloadsViewModel = hiltViewModel()
    val guard = rememberDownloadGuard(dl)
    val dlItems by dl.items.collectAsStateWithLifecycle()
    val downloaded = remember(dlItems) {
        dlItems.filter { it.status == DownloadStatus.COMPLETED && it.profileId == dl.profileId }.mapNotNull { it.meta.mediaKeyId }.toSet()
    }
    // Bulk: null episodes is the movie itself; a list is the episodes to choose from.
    var bulk by remember { mutableStateOf<List<Episode>?>(null) }
    var bulkMovie by remember { mutableStateOf(false) }
    var streamDownload by remember { mutableStateOf<StreamEntry?>(null) }
    val preferredHeight = vm.settings.collectAsStateWithLifecycle().value[SourceSettings.PreferredQuality].toIntOrNull()
    fun queued(n: Int) {
        val msg = if (n == 0) context.getString(R.string.dl_none_found) else context.resources.getQuantityString(R.plurals.dl_queued_count, n, n)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(event) {
        when (val e = event) {
            is TitleEvent.Play -> PlayerLaunch.play(context, e.request)
            is TitleEvent.Open -> confirmOpen = e
            null -> Unit
        }
        if (event != null) vm.consumeEvent()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth()) {
                    WebImage(title?.background ?: title?.poster, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Box(
                        Modifier.matchParentSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.55f), c.bg.copy(alpha = 0f), c.bg))),
                    )
                    BackTopBar("", onBack)
                    val logo = title?.logo
                    Box(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        if (logo != null) {
                            WebImage(logo, title?.name, Modifier.height(64.dp).widthIn(max = 260.dp).fillMaxWidth(), scale = androidx.compose.ui.layout.ContentScale.Fit)
                        } else {
                            Text(title?.name ?: "", style = Troy.type.display, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            val t = title
            if (t == null) {
                item { Text(stringResource(if (loading) R.string.loading else R.string.title_not_found), style = Troy.type.body, color = c.text2, modifier = Modifier.padding(16.dp)) }
                return@LazyColumn
            }
            item(key = "info") {
                Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val meta = listOfNotNull(t.year?.toString() ?: t.releaseInfo, t.runtime, t.rating?.let { "★ $it" }, t.country).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = Troy.type.bodySmall, color = c.text2)
                    if (t.genres.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            t.genres.forEach { Badge(it) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val single = t.type != TitleType.SERIES || t.episodes.isEmpty()
                        if (single) {
                            TroyButton(stringResource(R.string.player_play), { vm.openStreams(null) }, kind = ButtonKind.PRIMARY, icon = Icons.Outlined.PlayArrow)
                            TroyButton(stringResource(R.string.dl_download), { guard(t.source, drm = false, downloadable = true) { bulkMovie = true } }, icon = Icons.Outlined.Download)
                        } else {
                            TroyButton(stringResource(R.string.dl_download_season), {
                                guard(t.source, drm = false, downloadable = true) { bulk = t.episodes.filter { (it.season ?: 0) == season } }
                            }, icon = Icons.Outlined.Download)
                        }
                        t.trailers.firstOrNull()?.let { yt ->
                            TroyButton(stringResource(R.string.title_trailer), { confirmOpen = TitleEvent.Open(yt, youtube = true) }, icon = Icons.Outlined.SmartDisplay)
                        }
                    }
                    if (vm.progressKey(null) in downloaded) Badge(stringResource(R.string.dl_downloaded), tint = c.accent)
                    t.description?.let { Text(it, style = Troy.type.body, color = c.text) }
                    if (t.cast.isNotEmpty()) Text(stringResource(R.string.title_cast, t.cast.take(8).joinToString(", ")), style = Troy.type.bodySmall, color = c.text2)
                    if (t.directors.isNotEmpty()) Text(stringResource(R.string.title_directors, t.directors.joinToString(", ")), style = Troy.type.bodySmall, color = c.text2)
                    Text(stringResource(R.string.title_from, t.source.sourceName), style = Troy.type.label, color = c.text3)
                }
            }
            if (t.episodes.isNotEmpty()) {
                val seasons = vm.seasons()
                item(key = "seasons") {
                    LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            TroyChip(if (s == 0) stringResource(R.string.title_specials) else stringResource(R.string.title_season, s), season == s, { vm.selectSeason(s) })
                        }
                    }
                }
                val eps = t.episodes.filter { (it.season ?: 0) == season }
                items(eps, key = { "ep" + it.id }) { e ->
                    EpisodeRow(
                        e, progress[vm.progressKey(e)], downloaded = vm.progressKey(e) in downloaded,
                        onDownload = { guard(t.source, drm = false, downloadable = true) { dl.downloadBest(t, listOf(e), preferredHeight, ::queued) } },
                    ) { vm.openStreams(e) }
                }
            }
        }

        picker?.let { p ->
            BackHandler { vm.closeStreams() }
            Box(
                Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.6f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = vm::closeStreams),
                contentAlignment = Alignment.BottomCenter,
            ) {
                BoxWithConstraints {
                    Column(
                        Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight * 0.85f)
                            .glass(TroyShapes.Panel, elevation = 24.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            p.episode?.let { e -> listOfNotNull(e.season?.let { "S$it" }, e.number?.let { "E$it" }).joinToString("") + (e.name?.let { " · $it" } ?: "") }
                                ?: stringResource(R.string.streams_title),
                            style = Troy.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(StreamSort.entries) { s -> TroyChip(stringResource(s.label()), p.sort == s, { vm.setSort(s) }) }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { SectionLabel(stringResource(R.string.streams_audio), Modifier.padding(top = 10.dp)) }
                            item { TroyChip(stringResource(R.string.streams_any), p.audioLanguage == null, { vm.setLanguages(null, p.subtitleLanguage) }) }
                            items(pickerLanguages) { l -> TroyChip(l.uppercase(), p.audioLanguage == l, { vm.setLanguages(l, p.subtitleLanguage) }) }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { SectionLabel(stringResource(R.string.streams_subtitles), Modifier.padding(top = 10.dp)) }
                            item { TroyChip(stringResource(R.string.streams_any), p.subtitleLanguage == null, { vm.setLanguages(p.audioLanguage, null) }) }
                            items(pickerLanguages) { l -> TroyChip(l.uppercase(), p.subtitleLanguage == l, { vm.setLanguages(p.audioLanguage, l) }) }
                        }
                        val ranked = vm.ranked()
                        if (p.state.pending > 0) Text(stringResource(R.string.streams_pending, p.state.pending), style = Troy.type.bodySmall, color = c.text2)
                        if (p.state.failed.isNotEmpty()) Text(stringResource(R.string.search_failed, p.state.failed.joinToString(", ")), style = Troy.type.bodySmall, color = c.text3)
                        if (ranked.isEmpty() && p.state.pending == 0) Text(stringResource(R.string.streams_none), style = Troy.type.body, color = c.text2)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ranked.isEmpty() && p.state.pending > 0) items(4) { ShimmerBlock(Modifier.fillMaxWidth().height(64.dp).clip(TroyShapes.Control)) }
                            items(ranked, key = { it.option.source.sourceId + it.option.url + (it.option.name ?: "") }) { s ->
                                StreamRow(s, onDownload = { guard(s.option.source, s.option.drmProtected, s.option.downloadable) { streamDownload = s } }) { vm.play(s) }
                            }
                        }
                    }
                }
            }
        }
    }

    val t = title
    if (t != null && bulkMovie) {
        BulkDownloadDialog(t.name, episodes = null, initialQuality = preferredHeight, smart = null, onDismiss = { bulkMovie = false }) { q, _, _ ->
            bulkMovie = false
            dl.downloadBest(t, listOf(null), q, ::queued)
        }
    }
    val bulkList = bulk
    if (t != null && bulkList != null) {
        BulkDownloadDialog(
            stringResource(R.string.dl_download_season), episodes = bulkList, initialQuality = preferredHeight, smart = dl.isSmart(t.id),
            onDismiss = { bulk = null },
        ) { q, chosen, smart ->
            bulk = null
            if (chosen.isNotEmpty()) dl.downloadBest(t, chosen, q, ::queued)
            dl.setSmart(t, q, smart)
        }
    }
    val sd = streamDownload
    val pk = picker
    if (t != null && sd != null) {
        DownloadOptionsDialog(
            vm = dl, name = sd.option.name ?: t.name, url = sd.option.url, headers = sd.option.headers,
            subtitles = { dl.subtitlesFor(t, pk?.episode, sd) }, preferredLanguage = pk?.subtitleLanguage,
            onDismiss = { streamDownload = null },
        ) { choice ->
            streamDownload = null
            dl.downloadStream(t, pk?.episode, sd, choice.subtitles, choice.tracks, choice.startNow, choice.kind)
            queued(1)
        }
    }

    confirmOpen?.let { o ->
        AlertDialog(
            onDismissRequest = { confirmOpen = null },
            title = { Text(stringResource(if (o.youtube) R.string.open_youtube_title else R.string.open_external_title), style = Troy.type.title, color = c.text) },
            text = { Text(stringResource(if (o.youtube) R.string.open_youtube_message else R.string.open_external_message), style = Troy.type.body, color = c.text2) },
            confirmButton = {
                TextButton(onClick = {
                    // YouTube links open in the YouTube app or its own page; streams are never extracted.
                    val url = if (o.youtube) "https://www.youtube.com/watch?v=" + Uri.encode(o.url) else o.url
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    confirmOpen = null
                }) { Text(stringResource(R.string.open), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = null }) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
    }
}

private fun StreamSort.label(): Int = when (this) {
    StreamSort.QUALITY -> R.string.sort_quality_short
    StreamSort.SIZE_LARGEST -> R.string.sort_size_largest_short
    StreamSort.SIZE_SMALLEST -> R.string.sort_size_smallest_short
    StreamSort.LANGUAGE -> R.string.sort_language_short
    StreamSort.SOURCE -> R.string.sort_source_short
}

@Composable
private fun EpisodeRow(e: Episode, progress: Float?, downloaded: Boolean, onDownload: () -> Unit, onClick: () -> Unit) {
    val c = Troy.colors
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 860.dp).fillMaxWidth().troyFocusRing().clip(TroyShapes.Control)
            .clickable(role = Role.Button, onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(TroyShapes.Control)) {
            WebImage(e.thumbnail, null, Modifier.fillMaxSize())
            if (progress != null && progress > 0.01f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(c.controlFill)) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(c.accent))
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(e.number?.let { "$it." }, e.name).joinToString(" ").ifEmpty { e.id },
                style = Troy.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            e.released?.take(10)?.let { Text(it, style = Troy.type.label, color = c.text3) }
            e.overview?.let { Text(it, style = Troy.type.bodySmall, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (downloaded) {
            Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.dl_downloaded), tint = c.accent, modifier = Modifier.padding(horizontal = 12.dp).size(22.dp))
        } else {
            TroyIconButton(Icons.Outlined.Download, stringResource(R.string.dl_download), onDownload)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamRow(s: StreamEntry, onDownload: () -> Unit, onClick: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    val i = s.info
    val enabled = s.playable || s.option.kind == StreamKind.EXTERNAL || s.option.kind == StreamKind.YOUTUBE
    Column(
        Modifier.fillMaxWidth().clip(TroyShapes.Control).background(c.controlFill).troyFocusRing()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            (s.option.name ?: s.option.source.sourceName).replace('\n', ' '),
            style = Troy.type.body, color = if (enabled) c.text else c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        (s.option.description ?: s.option.filename)?.let {
            Text(it, style = Troy.type.bodySmall, color = if (enabled) c.text2 else c.text3, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            i.resolution?.let { Badge(if (it == 2160) "4K" else "${it}p", tint = c.accent) }
            i.hdr.forEach { Badge(it.label()) }
            i.audio.forEach { Badge(it.label()) }
            i.channels?.let { Badge(it) }
            i.codec?.let { Badge(it) }
            i.origin?.let { Badge(it) }
            i.sizeBytes?.let { Badge(Formatter.formatShortFileSize(context, it)) }
            i.languages.take(4).forEach { Badge(if (it == "multi") stringResource(R.string.badge_multi) else it.uppercase()) }
            if (i.hasSubtitles) Badge(stringResource(R.string.badge_subs))
            Badge(s.option.source.sourceName)
            if (s.playable && s.option.kind == StreamKind.URL && s.option.downloadable && !s.option.drmProtected) {
                TroyIconButton(Icons.Outlined.Download, stringResource(R.string.dl_download), onDownload)
            }
            if (i.insecure) Badge(stringResource(R.string.badge_insecure), tint = c.warn)
            when (s.option.kind) {
                StreamKind.EXTERNAL -> Badge(stringResource(R.string.badge_external), tint = c.info)
                StreamKind.YOUTUBE -> Badge("YouTube", tint = c.info)
                StreamKind.TORRENT, StreamKind.UNSUPPORTED -> Badge(stringResource(R.string.badge_unsupported), tint = c.text3)
                StreamKind.URL -> Unit
            }
        }
    }
}

private fun HdrTag.label() = when (this) {
    HdrTag.DOLBY_VISION -> "DV"
    HdrTag.HDR10_PLUS -> "HDR10+"
    HdrTag.HDR10 -> "HDR10"
    HdrTag.HLG -> "HLG"
    HdrTag.HDR -> "HDR"
}

private fun AudioTag.label() = when (this) {
    AudioTag.ATMOS -> "Atmos"
    AudioTag.TRUEHD -> "TrueHD"
    AudioTag.DTS_X -> "DTS:X"
    AudioTag.DTS_HD -> "DTS-HD"
    AudioTag.DTS -> "DTS"
    AudioTag.DDP -> "DD+"
    AudioTag.DD -> "DD"
    AudioTag.AAC -> "AAC"
    AudioTag.OPUS -> "Opus"
    AudioTag.FLAC -> "FLAC"
}
