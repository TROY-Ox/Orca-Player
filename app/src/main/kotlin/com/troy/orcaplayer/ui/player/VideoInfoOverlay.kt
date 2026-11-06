package com.troy.orcaplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.player.engine.AudioOutputMode
import com.troy.orcaplayer.player.engine.DolbyVision
import com.troy.orcaplayer.player.engine.HdrKind
import com.troy.orcaplayer.player.engine.PlaybackStats
import kotlin.math.roundToInt

fun hdrLabel(kind: HdrKind, codecs: String?): String = when (kind) {
    HdrKind.SDR -> "SDR"
    HdrKind.HDR10 -> "HDR10"
    HdrKind.HDR10_PLUS -> "HDR10+"
    HdrKind.HLG -> "HLG"
    HdrKind.DOLBY_VISION -> DolbyVision.profileOf(codecs)?.let { "Dolby Vision $it" } ?: "Dolby Vision"
}

@Composable
private fun bitrate(bps: Int): String? = bps.takeIf { it > 0 }?.let {
    if (it >= 1_000_000) formatNumber((it / 100_000) / 10f) + " Mbps" else formatNumber(it / 1000) + " kbps"
}

private fun codec(f: Format?): String? = f?.let { (f.codecs ?: f.sampleMimeType?.substringAfter('/'))?.uppercase() }

/** Resolution, frame rate, codec, bitrate, HDR, decoder, dropped frames, buffer and audio output. */
@Composable
fun VideoInfoOverlay(stats: PlaybackStats, bufferMs: Long, modifier: Modifier = Modifier) {
    val v = stats.video
    val a = stats.audio
    val rows = buildList {
        if (v != null && v.width > 0) add(stringResource(R.string.info_resolution) to "${formatNumber(v.width)}×${formatNumber(v.height)}")
        if (v != null && v.frameRate > 0) add(stringResource(R.string.info_frame_rate) to formatNumber((v.frameRate * 1000).roundToInt() / 1000f) + " fps")
        codec(v)?.let { add(stringResource(R.string.info_video) to it) }
        (bitrate(v?.bitrate ?: 0) ?: bitrate(v?.averageBitrate ?: 0))?.let { add(stringResource(R.string.info_bitrate) to it) }
        if (stats.bandwidthBps > 0) add(stringResource(R.string.info_bandwidth) to (bitrate(stats.bandwidthBps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) ?: ""))
        add(stringResource(R.string.info_hdr) to hdrLabel(stats.hdr, v?.codecs))
        stats.videoDecoder?.let { add(stringResource(R.string.info_decoder) to it) }
        add(stringResource(R.string.info_dropped) to formatNumber(stats.droppedFrames))
        add(stringResource(R.string.info_buffer) to formatNumber((bufferMs / 100) / 10f) + " s")
        if (a != null) {
            val ch = if (a.channelCount > 0) " · " + formatNumber(a.channelCount) + " ch" else ""
            val rate = if (a.sampleRate > 0) " · " + formatNumber(a.sampleRate / 1000f) + " kHz" else ""
            add(stringResource(R.string.info_audio) to (codec(a) ?: "") + ch + rate)
        }
        add(
            stringResource(R.string.info_output) to stringResource(
                when (stats.audioOutput) {
                    AudioOutputMode.NORMAL -> R.string.output_normal
                    AudioOutputMode.PASSTHROUGH -> R.string.output_passthrough
                    AudioOutputMode.SPATIAL -> R.string.output_spatial
                },
            ),
        )
    }
    Column(
        modifier.widthIn(max = 320.dp).glass(TroyShapes.Panel).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { (k, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(k, style = Troy.type.label, color = Troy.colors.text3)
                Text(value, style = Troy.type.mono, color = Troy.colors.text)
            }
        }
    }
}
