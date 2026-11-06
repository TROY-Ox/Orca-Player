package com.troy.orcaplayer.ui.player

import android.content.Context
import android.view.accessibility.CaptioningManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.Cue
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyAccent
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.core.settings.SettingsValues
import kotlin.math.roundToInt

/** One line block to draw: text with simple styling, or a picture subtitle (PGS, VobSub, DVB). */
data class DrawnCue(val text: String?, val italic: Boolean = false, val bold: Boolean = false, val bitmap: android.graphics.Bitmap? = null, val top: Boolean = false)

data class SubtitleStyle(
    val sizeSp: Float,
    val color: Color,
    val outline: Boolean,
    val shadow: Boolean,
    val background: String,
    val bold: Boolean,
    val bottomMarginFraction: Float,
)

@Composable
fun subtitleStyle(v: SettingsValues): SubtitleStyle {
    val context = LocalContext.current
    val accent = Troy.colors.accent
    val systemScale = remember {
        (context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager)?.fontScale ?: 1f
    }
    val size = when (v[PlayerSettings.SubtitleSize]) {
        "small" -> 16f
        "medium" -> 20f
        "large" -> 25f
        "xlarge" -> 31f
        else -> 20f * systemScale
    }
    val color = when (v[PlayerSettings.SubtitleColor]) {
        "yellow" -> TroyAccent.fromKey("yellow")?.colors?.accent ?: Color.White
        "accent" -> accent
        else -> Color.White
    }
    val margin = when (v[PlayerSettings.SubtitleMargin]) {
        "low" -> 0.04f
        "high" -> 0.16f
        else -> 0.08f
    }
    return SubtitleStyle(size, color, v[PlayerSettings.SubtitleOutline], v[PlayerSettings.SubtitleShadow], v[PlayerSettings.SubtitleBackground], v[PlayerSettings.SubtitleBold], margin)
}

/** Converts Media3 cues (embedded tracks) into the app's drawn cues. */
fun List<Cue>.toDrawn(): List<DrawnCue> = mapNotNull { cue ->
    when {
        cue.bitmap != null -> DrawnCue(null, bitmap = cue.bitmap)
        cue.text != null -> DrawnCue(cue.text.toString(), top = cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line < 0.3f)
        else -> null
    }
}

/**
 * The app's own subtitle layer. Text runs in its natural direction (Arabic right to left, with correct
 * shaping from the text engine). Drag the text up or down to move it.
 */
@Composable
fun SubtitleLayer(cues: List<DrawnCue>, style: SubtitleStyle, controlsVisible: Boolean, modifier: Modifier = Modifier) {
    var dragOffsetPx by rememberSaveable { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val heightPx = with(density) { maxHeight.toPx() }
        // Lift subtitles above the seek bar while the controls show.
        val lift = if (controlsVisible) with(density) { 72.dp.toPx() } else 0f
        val bottomPx = heightPx * style.bottomMarginFraction + lift - dragOffsetPx
        val bottomCues = cues.filter { !it.top }
        val topCues = cues.filter { it.top }
        if (topCues.isNotEmpty()) {
            CueColumn(topCues, style, Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
        }
        if (bottomCues.isNotEmpty()) {
            CueColumn(
                bottomCues,
                style,
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(0, -bottomPx.roundToInt().coerceIn(0, (heightPx * 0.85f).roundToInt())) }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dy ->
                            change.consume()
                            dragOffsetPx = (dragOffsetPx + dy).coerceIn(-heightPx * 0.6f, heightPx * 0.1f)
                        }
                    },
            )
        }
    }
}

@Composable
private fun CueColumn(cues: List<DrawnCue>, style: SubtitleStyle, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        cues.forEach { cue ->
            val bmp = cue.bitmap
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.width(with(LocalDensity.current) { bmp.width.toDp() }))
            } else if (!cue.text.isNullOrBlank()) {
                CueText(cue, style)
            }
        }
    }
}

@Composable
private fun CueText(cue: DrawnCue, style: SubtitleStyle) {
    val family = Troy.type.body.fontFamily
    val base = TextStyle(
        fontFamily = family,
        fontSize = style.sizeSp.sp,
        lineHeight = (style.sizeSp * 1.3f).sp,
        fontWeight = if (style.bold || cue.bold) FontWeight.Bold else FontWeight.Medium,
        fontStyle = if (cue.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Center,
        textDirection = TextDirection.Content,
    )
    val bg = when (style.background) {
        "translucent" -> Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
        "solid" -> Modifier.background(Color.Black, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
        else -> Modifier
    }
    val text = cue.text.orEmpty()
    Box(bg, contentAlignment = Alignment.Center) {
        if (style.outline) {
            Text(text, style = base.copy(color = Color.Black, drawStyle = Stroke(width = style.sizeSp * 0.22f)))
        }
        Text(
            text,
            style = base.copy(
                color = style.color,
                shadow = if (style.shadow) Shadow(Color.Black.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(2f, 2f), 6f) else null,
            ),
        )
    }
}
