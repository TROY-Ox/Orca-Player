package com.troy.orcaplayer.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.troy.orcaplayer.player.audio.AudioChain
import java.nio.ByteBuffer

/**
 * Runs the app's software audio chain inside ExoPlayer, for every source alike. Takes 16-bit or
 * float PCM and outputs the same encoding. It never sees passthrough audio: the sink skips
 * processors for bitstreams.
 */
@OptIn(UnstableApi::class)
internal class FxAudioProcessor(private val chain: AudioChain) : BaseAudioProcessor() {
    private var scratch = FloatArray(0)
    private var encoding = C.ENCODING_INVALID
    private var channels = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        encoding = enc
        channels = inputAudioFormat.channelCount
        chain.configure(inputAudioFormat.sampleRate, channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val samples = bytes / bytesPerSample
        if (scratch.size < samples) scratch = FloatArray(samples)
        val buf = scratch
        val start = inputBuffer.position()
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until samples) buf[i] = inputBuffer.getFloat(start + i * 4)
        } else {
            for (i in 0 until samples) buf[i] = inputBuffer.getShort(start + i * 2) / 32768f
        }
        inputBuffer.position(inputBuffer.limit())
        chain.process(buf, samples / channels.coerceAtLeast(1))
        val out = replaceOutputBuffer(samples * bytesPerSample)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until samples) out.putFloat(buf[i])
        } else {
            for (i in 0 until samples) {
                val v = (buf[i] * 32767f).toInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        }
        out.flip()
    }

    override fun onReset() {
        chain.reset()
        encoding = C.ENCODING_INVALID
        channels = 0
    }
}
