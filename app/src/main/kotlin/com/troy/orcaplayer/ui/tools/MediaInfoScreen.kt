package com.troy.orcaplayer.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.tools.InfoKey
import com.troy.orcaplayer.tools.MediaInfo
import com.troy.orcaplayer.tools.MediaInfoReader
import com.troy.orcaplayer.tools.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun infoLabel(context: Context, key: InfoKey): String = context.getString(
    when (key) {
        InfoKey.FILE -> R.string.info_file
        InfoKey.CONTAINER -> R.string.info_container
        InfoKey.DURATION -> R.string.info_duration
        InfoKey.SIZE -> R.string.info_size
        InfoKey.BITRATE -> R.string.info_bitrate
        InfoKey.CODEC -> R.string.info_codec
        InfoKey.PROFILE -> R.string.info_profile
        InfoKey.RESOLUTION -> R.string.info_resolution
        InfoKey.FRAME_RATE -> R.string.info_frame_rate
        InfoKey.BIT_DEPTH -> R.string.info_bit_depth
        InfoKey.COLOR -> R.string.info_color
        InfoKey.HDR -> R.string.info_hdr
        InfoKey.ROTATION -> R.string.info_rotation
        InfoKey.CHANNELS -> R.string.info_channels
        InfoKey.SAMPLE_RATE -> R.string.info_sample_rate
        InfoKey.LANGUAGE -> R.string.info_language
        InfoKey.TITLE -> R.string.info_title
        InfoKey.TAG_TITLE -> R.string.info_tag_title
        InfoKey.TAG_ARTIST -> R.string.info_tag_artist
        InfoKey.TAG_ALBUM -> R.string.info_tag_album
        InfoKey.TAG_DATE -> R.string.info_tag_date
        InfoKey.TAG_GENRE -> R.string.info_tag_genre
        InfoKey.TAG_LOCATION -> R.string.info_tag_location
    },
)

fun trackLabel(context: Context, kind: TrackKind, n: Int): String = context.getString(
    when (kind) {
        TrackKind.VIDEO -> R.string.info_track_video
        TrackKind.AUDIO -> R.string.info_track_audio
        TrackKind.SUBTITLE -> R.string.info_track_subtitle
        TrackKind.OTHER -> R.string.info_track_other
    },
    n,
)

/** Media info (12.7): container, every track, and tags, with "Copy as text". */
@Composable
fun MediaInfoScreen(target: ToolTarget, onBack: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    LaunchedEffect(target.uri) {
        info = withContext(Dispatchers.IO) { runCatching { MediaInfoReader.read(context, target.uri, target.headers) }.getOrNull() }
    }
    ToolScaffold(
        stringResource(R.string.tool_media_info), onBack,
        trailing = {
            TroyIconButton(Icons.Filled.ContentCopy, stringResource(R.string.info_copy), onClick = {
                val i = info ?: return@TroyIconButton
                val text = i.asText({ infoLabel(context, it) }, { k, n -> trackLabel(context, k, n) })
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText(target.title, text))
                Toast.makeText(context, R.string.info_copied, Toast.LENGTH_SHORT).show()
            })
        },
    ) {
        val i = info
        if (i == null) {
            ToolCard { Text(stringResource(R.string.tool_reading), style = Troy.type.bodySmall, color = c.text3) }
            return@ToolScaffold
        }
        ToolCard {
            SectionLabel(stringResource(R.string.info_general))
            i.general.forEach { (k, v) -> InfoLine(infoLabel(context, k), v) }
        }
        val counts = mutableMapOf<TrackKind, Int>()
        i.tracks.forEach { t ->
            val n = (counts[t.kind] ?: 0) + 1
            counts[t.kind] = n
            ToolCard {
                SectionLabel(trackLabel(context, t.kind, n))
                t.fields.forEach { (k, v) -> InfoLine(infoLabel(context, k), v) }
            }
        }
        if (i.tags.isNotEmpty()) {
            ToolCard {
                SectionLabel(stringResource(R.string.info_tags))
                i.tags.forEach { (k, v) -> InfoLine(infoLabel(context, k), v) }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val c = Troy.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = Troy.type.bodySmall, color = c.text3, modifier = Modifier.width(120.dp))
        Text(value, style = Troy.type.bodySmall.copy(textDirection = TextDirection.Content), color = c.text, modifier = Modifier.weight(1f))
    }
}
