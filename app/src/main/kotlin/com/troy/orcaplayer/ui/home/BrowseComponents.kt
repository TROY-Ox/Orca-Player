@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.troy.orcaplayer.ui.home

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.pressScale
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.core.settings.BrowsingSettings
import com.troy.orcaplayer.data.RowStyle
import com.troy.orcaplayer.ui.common.Badge
import com.troy.orcaplayer.ui.common.LocalCardSpec
import com.troy.orcaplayer.ui.common.LocalTitlePreview
import com.troy.orcaplayer.ui.common.LocalTitleProgress
import com.troy.orcaplayer.ui.common.ProgressLine
import com.troy.orcaplayer.ui.common.RowHeader
import com.troy.orcaplayer.ui.common.TitleCard
import com.troy.orcaplayer.ui.common.WebImage
import com.troy.orcaplayer.ui.downloads.DownloadsViewModel
import com.troy.orcaplayer.ui.downloads.rememberDownloadGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gives every title card in [content] the long-press preview card (17.3) and the progress bars,
 * so a long press means one thing everywhere.
 */
@Composable
fun TitlePreviewHost(openTitle: (Title, String?) -> Unit, content: @Composable () -> Unit) {
    val browse: BrowseViewModel = hiltViewModel()
    val progress by browse.titleProgress.collectAsStateWithLifecycle()
    var previewing by remember { mutableStateOf<Title?>(null) }
    CompositionLocalProvider(LocalTitlePreview provides { t -> previewing = t }, LocalTitleProgress provides progress) {
        content()
    }
    previewing?.let { t ->
        TitlePreviewDialog(t, browse, openTitle, onRemoveFromRow = null, onDismiss = { previewing = null })
    }
}

/** The larger preview card: genres, a short description, and Play, My List, Watched, Download and Details. */
@Composable
fun TitlePreviewDialog(
    title: Title,
    browse: BrowseViewModel,
    openTitle: (Title, String?) -> Unit,
    onRemoveFromRow: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = Troy.colors
    val context = LocalContext.current
    val dl: DownloadsViewModel = hiltViewModel()
    val guard = rememberDownloadGuard(dl)
    val keys by browse.myKeys.collectAsStateWithLifecycle()
    val listed = com.troy.orcaplayer.core.model.MyListKeys.forTitle(title.type, title.id) in keys
    val movie = title.type != TitleType.SERIES
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 520.dp).fillMaxWidth().glass(TroyShapes.Panel, elevation = 24.dp).clip(TroyShapes.Panel),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                WebImage(title.background ?: title.poster, title.name, Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg.copy(alpha = 0.9f)))))
                Text(
                    title.name, style = Troy.type.headline, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val meta = listOfNotNull(title.year?.toString() ?: title.releaseInfo, title.runtime, title.rating?.let { "★ $it" }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = Troy.type.bodySmall, color = c.text2)
                if (title.genres.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        title.genres.take(5).forEach { Badge(it) }
                    }
                }
                title.description?.let { Text(it, style = Troy.type.bodySmall, color = c.text2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroyButton(stringResource(R.string.player_play), {
                        onDismiss()
                        openTitle(title, if (movie) BrowseViewModel.PLAY_MOVIE else null)
                    }, kind = ButtonKind.PRIMARY, icon = Icons.Filled.PlayArrow)
                    TroyButton(
                        stringResource(if (listed) R.string.mylist_remove else R.string.mylist_add), { browse.toggle(title) },
                        icon = if (listed) Icons.Filled.CheckCircle else Icons.Outlined.Add,
                    )
                    if (movie) {
                        TroyButton(stringResource(R.string.mark_watched), {
                            browse.markWatched(title)
                            Toast.makeText(context, R.string.marked_watched, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }, icon = Icons.Outlined.Done)
                        TroyButton(stringResource(R.string.dl_download), {
                            guard(title.source, drm = false, downloadable = true) {
                                dl.downloadBest(title, listOf(null), null) { n ->
                                    Toast.makeText(context, if (n > 0) R.string.dl_queued else R.string.dl_none_found, Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            }
                        }, icon = Icons.Outlined.Download)
                    }
                    TroyButton(stringResource(R.string.preview_details), { onDismiss(); openTitle(title, null) }, icon = Icons.Outlined.Info)
                    if (onRemoveFromRow != null) {
                        TroyButton(stringResource(R.string.cw_remove), { onRemoveFromRow(); onDismiss() }, kind = ButtonKind.GHOST, icon = Icons.Outlined.Close)
                    }
                }
            }
        }
    }
}

/**
 * The featured area (17.2): one title or a carousel, with a large backdrop, logo, short
 * description, and Play and My List. Slides advance on their own and pause while touched.
 */
@Composable
fun HeroArea(
    items: List<Title>,
    browse: BrowseViewModel,
    onPlay: (Title) -> Unit,
    onOpen: (Title) -> Unit,
) {
    val v by browse.settings.collectAsStateWithLifecycle()
    val mode = v[BrowsingSettings.Hero]
    if (mode == "off" || items.isEmpty()) return
    val slides = if (mode == "single") items.take(1) else items.take(8)
    val c = Troy.colors
    val motion = Troy.motion
    val pager = rememberPagerState { slides.size }
    var touching by remember { mutableStateOf(false) }
    val keys by browse.myKeys.collectAsStateWithLifecycle()
    val interval = (v[BrowsingSettings.HeroInterval].toLongOrNull() ?: 8) * 1000
    val speed = BrowsingSettings.speedFactor(v[BrowsingSettings.AnimationSpeed])
    LaunchedEffect(slides.size, touching, v[BrowsingSettings.HeroAutoplay], motion.reduceMotion) {
        if (slides.size < 2 || touching || !v[BrowsingSettings.HeroAutoplay]) return@LaunchedEffect
        while (true) {
            delay(interval)
            if (!pager.isScrollInProgress) {
                val next = (pager.currentPage + 1) % slides.size
                if (motion.reduceMotion) pager.scrollToPage(next)
                else pager.animateScrollToPage(next, animationSpec = motion.springSpec(com.troy.orcaplayer.core.design.motion.TroySprings.Move.let { it.copy(response = it.response * speed) }))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = (maxWidth * 9f / 16f).coerceAtMost(440.dp)
        Box(
            Modifier.fillMaxWidth().height(height).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    touching = false
                }
            },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val t = slides[page]
                Box(
                    Modifier.fillMaxSize().combinedClickable(role = Role.Button, onClick = { onOpen(t) }),
                ) {
                    WebImage(t.background ?: t.poster, null, Modifier.fillMaxSize())
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.35f), c.bg.copy(alpha = 0f), c.bg))))
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 20.dp).widthIn(max = 560.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (t.logo != null) {
                            WebImage(t.logo, t.name, Modifier.height(56.dp).widthIn(max = 240.dp).fillMaxWidth(), scale = androidx.compose.ui.layout.ContentScale.Fit)
                        } else {
                            Text(t.name, style = Troy.type.display, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        t.description?.let { Text(it, style = Troy.type.bodySmall, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TroyButton(stringResource(R.string.player_play), { onPlay(t) }, kind = ButtonKind.PRIMARY, icon = Icons.Filled.PlayArrow)
                            val listed = com.troy.orcaplayer.core.model.MyListKeys.forTitle(t.type, t.id) in keys
                            TroyButton(
                                stringResource(R.string.mylist_short), { browse.toggle(t) },
                                icon = if (listed) Icons.Filled.CheckCircle else Icons.Outlined.Add,
                            )
                        }
                    }
                }
            }
            if (slides.size > 1) {
                Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(slides.size) { i ->
                        Box(Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape).background(if (i == pager.currentPage) c.accent else c.text3))
                    }
                }
            }
        }
    }
}

/** Continue Watching (17.5): newest first with progress; swipe up on touch screens to remove one. */
@Composable
fun ContinueRow(browse: BrowseViewModel, onOpen: (ContinueItem) -> Unit, openTitle: (Title, String?) -> Unit) {
    val items by browse.continueWatching.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    val c = Troy.colors
    val spec = LocalCardSpec.current
    var previewing by remember { mutableStateOf<ContinueItem?>(null) }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        RowHeader(stringResource(R.string.row_continue), null, null)
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(spec.spacing),
            flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Start),
        ) {
            items(items, key = { it.key }) { item ->
                ContinueCard(item, onClick = { onOpen(item) }, onLongClick = { previewing = item }, onRemove = { browse.removeFromRow(item) })
            }
        }
    }
    previewing?.let { item ->
        val t = browse.titleOf(item)
        if (t != null) {
            TitlePreviewDialog(t, browse, openTitle, onRemoveFromRow = { browse.removeFromRow(item) }, onDismiss = { previewing = null })
        } else {
            Dialog(onDismissRequest = { previewing = null }) {
                Column(Modifier.glass(TroyShapes.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name, style = Troy.type.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyButton(stringResource(R.string.player_play), { previewing = null; onOpen(item) }, kind = ButtonKind.PRIMARY, icon = Icons.Filled.PlayArrow)
                        TroyButton(stringResource(R.string.cw_remove), { browse.removeFromRow(item); previewing = null }, icon = Icons.Outlined.Close)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(item: ContinueItem, onClick: () -> Unit, onLongClick: () -> Unit, onRemove: () -> Unit) {
    val c = Troy.colors
    val spec = LocalCardSpec.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val motion = Troy.motion
    val limit = with(density) { 72.dp.toPx() }
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.width(spec.width(RowStyle.LANDSCAPE))
            .graphicsLayer {
                translationY = offset.value
                alpha = 1f - (-offset.value / (limit * 1.5f)).coerceIn(0f, 0.8f)
            }
            .draggable(
                rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceAtMost(0f)) } },
                Orientation.Vertical,
                onDragStopped = {
                    if (offset.value < -limit) onRemove() else offset.animateTo(0f, motion.springSpec())
                },
            )
            .pressScale(source)
            .troyFocusRing()
            .clip(spec.shape)
            .combinedClickable(interactionSource = source, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(spec.shape)) {
            WebImage(item.entry.poster, item.name, Modifier.fillMaxSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg.copy(alpha = 0.6f)))))
            ProgressLine(item.progress.coerceAtLeast(0.02f), Modifier.align(Alignment.BottomStart))
        }
        Text(item.name, style = Troy.type.bodySmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        item.entry.subtitle?.let { Text(it, style = Troy.type.label, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp)) }
    }
}

/** My List on Home (17.4); the header opens the full list. */
@Composable
fun MyListRow(browse: BrowseViewModel, onOpenList: () -> Unit, onOpen: (com.troy.orcaplayer.core.data.MyListEntity) -> Unit) {
    val items by browse.myList.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    val spec = LocalCardSpec.current
    val list = rememberLazyListState()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        RowHeader(stringResource(R.string.mylist_title), null, onOpenList)
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(spec.spacing),
            flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Start),
        ) {
            items(items.take(30), key = { it.itemKey }) { e ->
                val t = browse.titleOf(e)
                if (t != null) {
                    TitleCard(t, RowStyle.POSTER, { onOpen(e) })
                } else {
                    Column(
                        Modifier.width(spec.width(RowStyle.POSTER)).troyFocusRing().clip(spec.shape).combinedClickable(role = Role.Button) { onOpen(e) },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WebImage(e.poster, e.name, Modifier.fillMaxWidth().aspectRatio(spec.ratio(RowStyle.POSTER)).clip(spec.shape))
                        Text(e.name, style = Troy.type.bodySmall, color = Troy.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A tall row header area that keeps Home from jumping while the hero loads. */
@Composable
fun HeroPlaceholder() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(max = 440.dp).height(maxWidth * 9f / 16f).background(Troy.colors.controlFill))
    }
}
