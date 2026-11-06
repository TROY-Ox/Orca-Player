@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.troy.orcaplayer.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.ShimmerBlock
import com.troy.orcaplayer.core.design.component.pressScale
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.settings.BrowsingSettings
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.data.RowStyle
import kotlin.math.abs

/** Card size, roundness, spacing, poster shape and depth effect from Settings (17.1). */
@Immutable
data class CardSpec(
    val scale: Float = 1f,
    val shape: Shape = TroyShapes.Control,
    val spacing: Dp = 12.dp,
    val posterRatio: Float = 2f / 3f,
    val parallax: Boolean = true,
) {
    fun width(style: RowStyle): Dp = when (style) {
        RowStyle.POSTER -> 120.dp
        RowStyle.LANDSCAPE -> 220.dp
        RowStyle.LARGE -> 160.dp
        RowStyle.TOP10 -> 168.dp
        RowStyle.HERO -> 300.dp
    } * scale

    fun ratio(style: RowStyle): Float = when (style) {
        RowStyle.LANDSCAPE, RowStyle.HERO -> 16f / 9f
        else -> posterRatio
    }

    companion object {
        fun from(v: SettingsValues, reduceMotion: Boolean): CardSpec = CardSpec(
            scale = when (v[BrowsingSettings.CardSize]) { "small" -> 0.85f; "large" -> 1.2f; else -> 1f },
            shape = when (v[BrowsingSettings.CardCorners]) { "small" -> TroyShapes.Chip; "large" -> TroyShapes.Panel; else -> TroyShapes.Control },
            spacing = when (v[BrowsingSettings.CardSpacing]) { "compact" -> 8.dp; "roomy" -> 18.dp; else -> 12.dp },
            posterRatio = if (v[BrowsingSettings.CardShape] == "3:4") 3f / 4f else 2f / 3f,
            parallax = v[BrowsingSettings.Parallax] && !reduceMotion,
        )
    }
}

val LocalCardSpec = compositionLocalOf { CardSpec() }

/** What a long press on any title card does: the preview card (17.3). Set once by the shell. */
val LocalTitlePreview = compositionLocalOf<((Title) -> Unit)?> { null }

/** Progress of partly watched titles, by "TYPE/id", for the bar on each card. */
val LocalTitleProgress = compositionLocalOf<Map<String, Float>> { emptyMap() }

fun RowStyle.cardWidth(): Dp = CardSpec().width(this)

/** Scales and shifts a card a little as it slides past the middle of its row. */
private fun Modifier.parallax(state: LazyListState, index: Int, on: Boolean): Modifier = if (!on) this else graphicsLayer {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
    val center = item.offset + item.size / 2f - info.viewportStartOffset
    val f = ((center - viewport / 2f) / viewport).coerceIn(-1f, 1f)
    val s = 1f - 0.05f * abs(f)
    scaleX = s
    scaleY = s
    translationX = -f * 6.dp.toPx()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TitleCard(
    title: Title,
    style: RowStyle,
    onClick: () -> Unit,
    modifier: Modifier? = null,
    rank: Int? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = Troy.colors
    val spec = LocalCardSpec.current
    val preview = LocalTitlePreview.current
    val longPress = onLongClick ?: preview?.let { p -> { p(title) } }
    val shown = progress ?: LocalTitleProgress.current["${title.type.name}/${title.id}"]
    val source = remember { MutableInteractionSource() }
    val shape = spec.shape
    Column(
        (modifier ?: Modifier.width(spec.width(style)))
            .pressScale(source)
            .troyFocusRing()
            .clip(shape)
            .combinedClickable(interactionSource = source, indication = null, role = Role.Button, onLongClick = longPress, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            style == RowStyle.TOP10 && rank != null && rank <= 10 -> Row(verticalAlignment = Alignment.Bottom) {
                // A large outlined number beside the poster, Top 10 style.
                Text(
                    rank.toString(),
                    style = Troy.type.display.copy(fontSize = 72.sp, fontWeight = FontWeight.Black),
                    color = c.text3,
                    modifier = Modifier.padding(end = 2.dp),
                )
                PosterImage(title, style, shape, shown, Modifier.weight(1f))
            }
            style == RowStyle.HERO -> Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)) {
                WebImage(title.background ?: title.poster, title.name, Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg.copy(alpha = 0.85f)))))
                Text(
                    title.name, style = Troy.type.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
                ProgressLine(shown, Modifier.align(Alignment.BottomStart))
            }
            else -> PosterImage(title, style, shape, shown, Modifier.fillMaxWidth())
        }
        if (style != RowStyle.HERO) {
            Text(title.name, style = Troy.type.bodySmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
            val meta = listOfNotNull(title.year?.toString() ?: title.releaseInfo, title.rating?.let { "★ $it" }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = Troy.type.label, color = c.text3, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
private fun PosterImage(title: Title, style: RowStyle, shape: Shape, progress: Float?, modifier: Modifier) {
    val c = Troy.colors
    val spec = LocalCardSpec.current
    val image = if (style == RowStyle.LANDSCAPE) title.background ?: title.poster else title.poster ?: title.background
    Box(modifier.aspectRatio(spec.ratio(style)).clip(shape).border(1.dp, c.hair, shape)) {
        WebImage(image, title.name, Modifier.fillMaxSize())
        ProgressLine(progress, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
fun ProgressLine(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null || progress <= 0.01f) return
    val c = Troy.colors
    Box(modifier.fillMaxWidth().height(3.dp).background(c.controlFill)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(c.accent))
    }
}

data class RowState(
    val items: List<Title> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val hasMore: Boolean = true,
)

/** A row header with an optional See all. */
@Composable
fun RowHeader(name: String, subtitle: String?, onSeeAll: (() -> Unit)?) {
    val c = Troy.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(
            if (onSeeAll != null) Modifier.troyFocusRing().clip(TroyShapes.Control).combinedClickable(role = Role.Button, onClick = onSeeAll) else Modifier,
        ).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = Troy.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) SectionLabel(subtitle)
        }
        if (onSeeAll != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.see_all), tint = c.text3)
    }
}

/**
 * A row of cards that loads the next page near its end (14.1), with momentum scrolling that
 * snaps gently to the start of a card, and the depth effect (17.1).
 */
@Composable
fun TitleRow(
    name: String,
    subtitle: String?,
    state: RowState,
    style: RowStyle,
    onOpen: (Title) -> Unit,
    onNeedMore: () -> Unit,
    onSeeAll: (() -> Unit)?,
) {
    val c = Troy.colors
    val spec = LocalCardSpec.current
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(list, state.hasMore) {
        snapshotFlow { nearEnd }.collect { if (it && state.hasMore && !state.loading && state.items.isNotEmpty()) onNeedMore() }
    }
    val width = spec.width(style)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        RowHeader(name, subtitle, onSeeAll)
        when {
            state.items.isEmpty() && state.loading -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(spec.spacing),
                userScrollEnabled = false,
            ) {
                items(6) { ShimmerBlock(Modifier.width(width).aspectRatio(spec.ratio(style)).clip(spec.shape)) }
            }
            state.items.isEmpty() && state.error != null -> Text(
                stringResource(R.string.row_failed, state.error),
                style = Troy.type.bodySmall, color = c.text3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            state.items.isEmpty() -> Text(stringResource(R.string.row_empty), style = Troy.type.bodySmall, color = c.text3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            else -> LazyRow(
                state = list,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(spec.spacing),
                flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Start),
            ) {
                itemsIndexed(state.items, key = { _, t -> t.type.name + t.id }) { i, t ->
                    TitleCard(t, style, { onOpen(t) }, Modifier.width(width).parallax(list, i, spec.parallax), rank = i + 1)
                }
                if (state.loading) item { ShimmerBlock(Modifier.width(width).aspectRatio(spec.ratio(style)).clip(spec.shape)) }
            }
        }
    }
}

/** Corner shapes for one card radius, for code that builds its own shapes. */
fun cardShape(radius: Dp) = RoundedCornerShape(radius)
