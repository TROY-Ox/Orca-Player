package com.troy.orcaplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.player.engine.DecoderRow
import com.troy.orcaplayer.player.engine.DisplayCaps
import com.troy.orcaplayer.player.engine.VideoCaps
import com.troy.orcaplayer.ui.player.hdrLabel
import com.troy.orcaplayer.ui.shell.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun mimeLabel(mime: String): String = when (mime) {
    "video/avc" -> "H.264 (AVC)"
    "video/hevc" -> "H.265 (HEVC)"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/dolby-vision" -> "Dolby Vision"
    "video/mpeg2" -> "MPEG-2"
    "video/3gpp" -> "H.263"
    "video/mp4v-es" -> "MPEG-4"
    else -> mime
}

/** Which codecs, profiles, sizes and frame rates this device decodes, and what its screen supports. */
@Composable
fun DecoderCheckScreen(onBack: () -> Unit) {
    val c = Troy.colors
    val view = LocalView.current
    var rows by remember { mutableStateOf<List<DecoderRow>?>(null) }
    var display by remember { mutableStateOf<DisplayCaps?>(null) }
    LaunchedEffect(Unit) {
        display = VideoCaps.display(view.display)
        rows = withContext(Dispatchers.Default) { VideoCaps.decoders() }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.dc_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                val d = display
                Card {
                    SectionLabel(stringResource(R.string.dc_screen))
                    Line(
                        stringResource(R.string.dc_hdr_formats),
                        d?.hdr?.takeIf { it.isNotEmpty() }?.joinToString(", ") { hdrLabel(it, null) } ?: stringResource(R.string.dc_none),
                    )
                    Line(
                        stringResource(R.string.dc_refresh),
                        d?.refreshRates?.map { formatRate(it) }?.joinToString(", ") ?: "",
                    )
                }
            }
            val list = rows
            if (list == null) {
                item { Text(stringResource(R.string.dc_loading), style = Troy.type.body, color = c.text2, modifier = Modifier.padding(16.dp)) }
            } else {
                list.groupBy { it.mime }.forEach { (mime, decoders) ->
                    item(key = mime) {
                        Card {
                            SectionLabel(mimeLabel(mime))
                            decoders.forEach { r ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(r.name, style = Troy.type.mono.copy(textDirection = TextDirection.Ltr), color = c.text, modifier = Modifier.weight(1f))
                                        Text(
                                            stringResource(if (r.hardware) R.string.dc_hardware else R.string.dc_software),
                                            style = Troy.type.label,
                                            color = if (r.hardware) c.ok else c.text3,
                                        )
                                    }
                                    if (r.maxWidth > 0) {
                                        Text(
                                            stringResource(
                                                R.string.dc_max,
                                                formatNumber(r.maxWidth) + "×" + formatNumber(r.maxHeight),
                                                if (r.maxFps > 0) formatNumber(r.maxFps) else "–",
                                            ),
                                            style = Troy.type.bodySmall,
                                            color = c.text2,
                                        )
                                    }
                                    if (r.profiles.isNotEmpty()) Text(r.profiles.joinToString(" · "), style = Troy.type.bodySmall, color = c.text3)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatRate(hz: Float): String = formatNumber((hz * 100).toInt() / 100f) + " Hz"

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Troy.type.body, color = Troy.colors.text2, modifier = Modifier.weight(1f))
        Text(value, style = Troy.type.body, color = Troy.colors.text)
    }
}
