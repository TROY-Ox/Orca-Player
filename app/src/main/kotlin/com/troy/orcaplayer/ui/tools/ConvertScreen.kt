package com.troy.orcaplayer.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.tools.CompressJob
import com.troy.orcaplayer.tools.ConvertJob
import com.troy.orcaplayer.tools.Encoders
import com.troy.orcaplayer.tools.ExtractJob
import com.troy.orcaplayer.tools.MediaInfo
import com.troy.orcaplayer.tools.MediaInfoReader
import com.troy.orcaplayer.tools.ToolMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ConvertMode { EXTRACT, CONVERT, COMPRESS }

private const val AUDIO_BPS = 128_000
private val Heights = listOf(0, 2160, 1440, 1080, 720, 480, 360)

/**
 * Audio extractor (12.4), converter (12.5) and compressor (12.6). All three run as background jobs
 * with Media3 Transformer on the device's own encoders. Formats that need FFmpeg are listed as
 * unavailable rather than hidden.
 */
@Composable
fun ConvertScreen(target: ToolTarget, mode: ConvertMode, onBack: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var jobId by rememberSaveable { mutableStateOf<String?>(null) }
    val startJob = rememberJobStarter { jobId = it.id }
    LaunchedEffect(target.uri) {
        info = withContext(Dispatchers.IO) { runCatching { MediaInfoReader.read(context, target.uri, target.headers) }.getOrNull() }
    }
    val hevc = remember { Encoders.supports("video/hevc") }
    val title = stringResource(
        when (mode) {
            ConvertMode.EXTRACT -> R.string.tool_audio_extractor
            ConvertMode.CONVERT -> R.string.tool_converter
            ConvertMode.COMPRESS -> R.string.tool_compressor
        },
    )
    // Converter choices.
    var codec by rememberSaveable { mutableStateOf<String?>(null) }
    var height by rememberSaveable { mutableIntStateOf(0) }
    var fps by rememberSaveable { mutableIntStateOf(0) }
    var keepAudio by rememberSaveable { mutableStateOf(true) }
    // Compressor choice: share of the original size, in percent.
    var percent by rememberSaveable { mutableIntStateOf(50) }

    ToolScaffold(title, onBack) {
        ToolCard {
            Text(target.title, style = Troy.type.body, color = c.text, maxLines = 2)
            val i = info
            if (i == null) {
                Text(stringResource(R.string.tool_reading), style = Troy.type.bodySmall, color = c.text3)
            } else {
                val parts = buildList {
                    if (i.durationMs > 0) add(ToolMath.formatTime(i.durationMs).substringBefore('.'))
                    if (i.sizeBytes > 0) add(MediaInfoReader.size(i.sizeBytes))
                    if (i.videoHeight > 0) add("${i.videoHeight}p")
                }
                Text(parts.joinToString(" · "), style = Troy.type.bodySmall, color = c.text2)
            }
        }
        when (mode) {
            ConvertMode.EXTRACT -> ToolCard {
                SectionLabel(stringResource(R.string.convert_format))
                ChoiceRow { TroyChip("M4A (AAC)", true, {}) }
                Text(stringResource(R.string.extract_info), style = Troy.type.bodySmall, color = c.text2)
                Text(stringResource(R.string.convert_ffmpeg_formats, "MP3, FLAC, Opus, WAV"), style = Troy.type.bodySmall, color = c.text3)
                TroyButton(stringResource(R.string.extract_start), {
                    startJob(ExtractJob(target.uri.toString(), target.title, target.headers))
                }, kind = ButtonKind.PRIMARY)
            }
            ConvertMode.CONVERT -> ToolCard {
                SectionLabel(stringResource(R.string.convert_format))
                ChoiceRow { TroyChip("MP4", true, {}) }
                Text(stringResource(R.string.convert_ffmpeg_formats, "MKV, WebM, GIF"), style = Troy.type.bodySmall, color = c.text3)
                SectionLabel(stringResource(R.string.convert_codec))
                ChoiceRow {
                    TroyChip(stringResource(R.string.convert_keep), codec == null, { codec = null })
                    TroyChip("H.264", codec == "video/avc", { codec = "video/avc" })
                    if (hevc) TroyChip("H.265", codec == "video/hevc", { codec = "video/hevc" })
                }
                SectionLabel(stringResource(R.string.convert_resolution))
                ChoiceRow {
                    val srcH = info?.videoHeight ?: 0
                    Heights.filter { it == 0 || srcH == 0 || it < srcH }.forEach { h ->
                        TroyChip(if (h == 0) stringResource(R.string.convert_keep) else "${h}p", height == h, { height = h })
                    }
                }
                SectionLabel(stringResource(R.string.convert_frame_rate))
                ChoiceRow {
                    listOf(0, 60, 30, 25, 24).forEach { f ->
                        TroyChip(if (f == 0) stringResource(R.string.convert_keep) else "$f fps", fps == f, { fps = f })
                    }
                }
                SectionLabel(stringResource(R.string.convert_audio))
                ChoiceRow {
                    TroyChip(stringResource(R.string.convert_keep_audio), keepAudio, { keepAudio = true })
                    TroyChip(stringResource(R.string.convert_remove_audio), !keepAudio, { keepAudio = false })
                }
                if (codec == null && height == 0 && fps == 0) {
                    Text(stringResource(R.string.convert_copy_info), style = Troy.type.bodySmall, color = c.text2)
                }
                TroyButton(stringResource(R.string.convert_start), {
                    startJob(
                        ConvertJob(
                            target.uri.toString(), target.title,
                            videoMime = codec,
                            height = height.takeIf { it > 0 },
                            frameRate = fps.takeIf { it > 0 },
                            keepAudio = keepAudio,
                            headers = target.headers,
                        ),
                    )
                }, kind = ButtonKind.PRIMARY)
            }
            ConvertMode.COMPRESS -> ToolCard {
                val i = info
                SectionLabel(stringResource(R.string.compress_target))
                ChoiceRow {
                    listOf(75, 50, 30, 20).forEach { p ->
                        TroyChip(stringResource(R.string.compress_percent, formatNumber(p)), percent == p, { percent = p })
                    }
                }
                SectionLabel(stringResource(R.string.convert_resolution))
                ChoiceRow {
                    val srcH = i?.videoHeight ?: 0
                    Heights.filter { it == 0 || srcH == 0 || it < srcH }.forEach { h ->
                        TroyChip(if (h == 0) stringResource(R.string.convert_keep) else "${h}p", height == h, { height = h })
                    }
                }
                val canEstimate = i != null && i.durationMs > 0 && i.sizeBytes > 0
                if (canEstimate) {
                    val targetBytes = i.sizeBytes * percent / 100
                    val vbps = ToolMath.videoBitrateFor(targetBytes, i.durationMs, AUDIO_BPS)
                    val est = ToolMath.estimateBytes(vbps, AUDIO_BPS, i.durationMs)
                    Text(
                        stringResource(R.string.compress_estimate, MediaInfoReader.size(est), MediaInfoReader.bitrate(vbps.toLong())),
                        style = Troy.type.bodySmall, color = c.text2,
                    )
                    TroyButton(stringResource(R.string.compress_start), {
                        startJob(CompressJob(target.uri.toString(), target.title, vbps, height.takeIf { it > 0 }, target.headers))
                    }, kind = ButtonKind.PRIMARY)
                } else if (i != null) {
                    TroyAlert(stringResource(R.string.compress_no_size), AlertKind.WARN, Modifier.fillMaxWidth())
                }
            }
        }
        JobProgressCard(jobId)
    }
}
