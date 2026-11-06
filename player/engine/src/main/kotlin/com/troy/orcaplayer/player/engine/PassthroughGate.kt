package com.troy.orcaplayer.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Says "not supported" for bitstream formats (Dolby, DTS, TrueHD) unless passthrough is allowed,
 * so ExoPlayer decodes them to PCM and the effects can run.
 */
@OptIn(UnstableApi::class)
internal class PassthroughGate(sink: AudioSink, private val allowed: () -> Boolean) : ForwardingAudioSink(sink) {
    private fun blocked(format: Format) = !allowed() && format.sampleMimeType != MimeTypes.AUDIO_RAW

    override fun supportsFormat(format: Format): Boolean = !blocked(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (blocked(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
}
