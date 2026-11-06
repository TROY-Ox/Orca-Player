package com.troy.orcaplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.ShimmerBlock
import com.troy.orcaplayer.core.design.component.pressScale
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.data.HealthState
import com.troy.orcaplayer.data.RowStyle
import java.util.concurrent.ConcurrentHashMap

/** Cards the user tapped, so the details screen can show them at once while full details load. */
object TitleMemory {
    private val map = ConcurrentHashMap<String, Title>()
    fun put(t: Title) { if (map.size > 500) map.clear(); map["${t.type}/${t.id}"] = t }
    fun get(type: String, id: String): Title? = map["$type/$id"]
}

/** An image from the web, with the theme's quiet fill while it loads or when there is none. */
@Composable
fun WebImage(url: String?, contentDescription: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val c = Troy.colors
    Box(modifier.background(c.controlFill), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Outlined.Movie, contentDescription = null, tint = c.text3)
        } else {
            AsyncImage(model = url, contentDescription = contentDescription, contentScale = scale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun HealthDot(state: HealthState, modifier: Modifier = Modifier) {
    val c = Troy.colors
    val color = when (state) {
        HealthState.OK -> c.ok
        HealthState.SLOW -> c.warn
        HealthState.FAILING -> c.danger
        HealthState.UNKNOWN -> c.text3
    }
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/** A small badge: hairline outline, or a soft fill of a semantic colour. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    val c = Troy.colors
    val fg = tint ?: c.text2
    Box(
        modifier
            .clip(TroyShapes.Chip)
            .background(if (tint != null) tint.copy(alpha = 0.14f) else c.controlFill)
            .border(1.dp, if (tint != null) tint.copy(alpha = 0.4f) else c.hair, TroyShapes.Chip)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = Troy.type.label, color = fg, maxLines = 1)
    }
}
