package com.troy.orcaplayer.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Live playback details for the video info overlay. Collected on the device only; nothing is sent anywhere. */
data class PlaybackStats(
    val video: Format? = null,
    val audio: Format? = null,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    val droppedFrames: Int = 0,
    val bandwidthBps: Long = 0,
    val audioOutput: AudioOutputMode = AudioOutputMode.NORMAL,
    val outputChannels: Int = 0,
) {
    val hdr: HdrKind get() = video?.let(::hdrKindOf) ?: HdrKind.SDR
}

@OptIn(UnstableApi::class)
fun hdrKindOf(f: Format): HdrKind = when {
    f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || DolbyVision.isDolbyVision(f.codecs) -> HdrKind.DOLBY_VISION
    f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> HdrKind.HDR10
    f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> HdrKind.HLG
    else -> HdrKind.SDR
}

@OptIn(UnstableApi::class)
internal class StatsTracker(
    private val state: MutableStateFlow<PlaybackStats>,
    private val spatialOn: () -> Boolean,
    private val onVideoFormat: (Format) -> Unit,
) : AnalyticsListener {
    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        state.update { it.copy(video = format) }
        onVideoFormat(format)
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        state.update { it.copy(audio = format) }
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        state.update { it.copy(videoDecoder = decoderName) }
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        state.update { it.copy(audioDecoder = decoderName) }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        state.update { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
    }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        state.update { it.copy(bandwidthBps = bitrateEstimate) }
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        val pcm = audioTrackConfig.encoding in PCM_ENCODINGS
        val channels = Integer.bitCount(audioTrackConfig.channelConfig)
        val mode = when {
            !pcm -> AudioOutputMode.PASSTHROUGH
            channels > 2 && spatialOn() -> AudioOutputMode.SPATIAL
            else -> AudioOutputMode.NORMAL
        }
        state.update { it.copy(audioOutput = mode, outputChannels = channels) }
    }

    companion object {
        private val PCM_ENCODINGS = setOf(
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT,
        )
    }
}
