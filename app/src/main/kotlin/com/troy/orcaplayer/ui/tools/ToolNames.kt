package com.troy.orcaplayer.ui.tools

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ModeNight
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.model.ToolId

@StringRes
fun ToolId.nameRes(): Int = when (this) {
    ToolId.CLIP_CUTTER -> R.string.tool_clip_cutter
    ToolId.SCREENSHOT -> R.string.tool_screenshot
    ToolId.SUBTITLE_EDITOR -> R.string.tool_subtitle_editor
    ToolId.AUDIO_EXTRACTOR -> R.string.tool_audio_extractor
    ToolId.CONVERTER -> R.string.tool_converter
    ToolId.COMPRESSOR -> R.string.tool_compressor
    ToolId.MEDIA_INFO -> R.string.tool_media_info
    ToolId.EQUALIZER -> R.string.tool_equalizer
    ToolId.AB_REPEAT -> R.string.tool_ab_repeat
    ToolId.SLEEP_TIMER -> R.string.tool_sleep_timer
    ToolId.NIGHT_MODE -> R.string.tool_night_mode
    ToolId.VOLUME_BOOST -> R.string.tool_volume_boost
    ToolId.QUIET_MODE -> R.string.tool_quiet_mode
    ToolId.PER_VIDEO -> R.string.tool_per_video
    ToolId.DOWNLOAD -> R.string.tool_download
    ToolId.SKIP_MARKS -> R.string.tool_skip_marks
    ToolId.DECODER_CHECK -> R.string.dc_title
    ToolId.URL_PLAYER -> R.string.tool_url_player
}

@StringRes
fun ToolId.summaryRes(): Int = when (this) {
    ToolId.CLIP_CUTTER -> R.string.tool_clip_cutter_summary
    ToolId.SCREENSHOT -> R.string.tool_screenshot_summary
    ToolId.SUBTITLE_EDITOR -> R.string.tool_subtitle_editor_summary
    ToolId.AUDIO_EXTRACTOR -> R.string.tool_audio_extractor_summary
    ToolId.CONVERTER -> R.string.tool_converter_summary
    ToolId.COMPRESSOR -> R.string.tool_compressor_summary
    ToolId.MEDIA_INFO -> R.string.tool_media_info_summary
    ToolId.EQUALIZER -> R.string.tool_equalizer_summary
    ToolId.AB_REPEAT -> R.string.tool_ab_repeat_summary
    ToolId.SLEEP_TIMER -> R.string.tool_sleep_timer_summary
    ToolId.NIGHT_MODE -> R.string.tool_night_mode_summary
    ToolId.VOLUME_BOOST -> R.string.tool_volume_boost_summary
    ToolId.QUIET_MODE -> R.string.tool_quiet_mode_summary
    ToolId.PER_VIDEO -> R.string.tool_per_video_summary
    ToolId.DOWNLOAD -> R.string.tool_download_summary
    ToolId.SKIP_MARKS -> R.string.tool_skip_marks_summary
    ToolId.DECODER_CHECK -> R.string.dc_summary
    ToolId.URL_PLAYER -> R.string.tool_url_player_summary
}

fun ToolId.icon(): ImageVector = when (this) {
    ToolId.CLIP_CUTTER -> Icons.Filled.ContentCut
    ToolId.SCREENSHOT -> Icons.Filled.PhotoCamera
    ToolId.SUBTITLE_EDITOR -> Icons.Filled.Subtitles
    ToolId.AUDIO_EXTRACTOR -> Icons.Filled.AudioFile
    ToolId.CONVERTER -> Icons.Filled.SwapHoriz
    ToolId.COMPRESSOR -> Icons.Filled.Compress
    ToolId.MEDIA_INFO -> Icons.Filled.Info
    ToolId.EQUALIZER -> Icons.Filled.Equalizer
    ToolId.AB_REPEAT -> Icons.Filled.Repeat
    ToolId.SLEEP_TIMER -> Icons.Filled.Timer
    ToolId.NIGHT_MODE -> Icons.Filled.ModeNight
    ToolId.VOLUME_BOOST -> Icons.AutoMirrored.Filled.VolumeUp
    ToolId.QUIET_MODE -> Icons.AutoMirrored.Filled.VolumeDown
    ToolId.PER_VIDEO -> Icons.Filled.Tune
    ToolId.DOWNLOAD -> Icons.Filled.Download
    ToolId.SKIP_MARKS -> Icons.Filled.FastForward
    ToolId.DECODER_CHECK -> Icons.Filled.Memory
    ToolId.URL_PLAYER -> Icons.Filled.Link
}

/** Bedtime is used by the sleep-timer badge in the player. */
val SleepBadgeIcon: ImageVector get() = Icons.Filled.Bedtime
