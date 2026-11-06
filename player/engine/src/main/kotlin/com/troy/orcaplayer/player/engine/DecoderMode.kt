package com.troy.orcaplayer.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/** Decoder modes, using MX Player's names. */
enum class DecoderMode(val key: String) {
    /** Device hardware decoders only. */
    HW("hw"),
    /** Hardware first, with automatic per-track fallback to software. Default. */
    HW_PLUS("hw_plus"),
    /** Software decoders only. */
    SW("sw"),
    ;

    companion object {
        val Default = HW_PLUS
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: Default

        /** MX Player's "decode_mode" extra: 1 = HW, 2 = SW, 4 = HW+. */
        fun fromMxExtra(value: Int): DecoderMode? = when (value) {
            1 -> HW
            2 -> SW
            4 -> HW_PLUS
            else -> null
        }
    }
}

@OptIn(UnstableApi::class)
internal object DecoderSelectors {
    private fun ordered(mime: String, secure: Boolean, tunneling: Boolean): List<MediaCodecInfo> =
        MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)

    /** Hardware codecs first, then software ones (HW+ falls back through this list). */
    val hardwareFirst = MediaCodecSelector { mime, secure, tunneling ->
        val all = ordered(mime, secure, tunneling)
        all.filter { it.hardwareAccelerated && !it.softwareOnly } + all.filter { !it.hardwareAccelerated || it.softwareOnly }
    }

    val hardwareOnly = MediaCodecSelector { mime, secure, tunneling ->
        ordered(mime, secure, tunneling).filter { it.hardwareAccelerated && !it.softwareOnly }
    }

    val softwareOnly = MediaCodecSelector { mime, secure, tunneling ->
        ordered(mime, secure, tunneling).filter { it.softwareOnly || !it.hardwareAccelerated }
    }

    fun forMode(mode: DecoderMode): MediaCodecSelector = when (mode) {
        DecoderMode.HW -> hardwareOnly
        DecoderMode.HW_PLUS -> hardwareFirst
        DecoderMode.SW -> softwareOnly
    }
}
