package com.troy.orcaplayer.player.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel as P
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.MimeTypes

/** One decoder of this device, for the decoder check screen. */
data class DecoderRow(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val profiles: List<String>,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Highest frame rate at the largest size, or 0 when unknown. */
    val maxFps: Int,
)

data class DisplayCaps(val hdr: List<HdrKind>, val refreshRates: List<Float>, val modes: List<DisplayModeInfo>)

/** What this device can decode and show. Read once per screen; the codec list doesn't change at runtime. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoCaps {
    private val videoMimes = listOf(
        MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_VP9,
        MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_MPEG2, MimeTypes.VIDEO_H263, MimeTypes.VIDEO_MP4V,
    )

    @Volatile private var dvCache: Boolean? = null

    fun hasDolbyVisionDecoder(): Boolean = dvCache ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) }
        }
    }.getOrDefault(false).also { dvCache = it }

    fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("omx.ffmpeg."))
    }

    fun decoders(): List<DecoderRow> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { info ->
                info.supportedTypes.filter { t -> videoMimes.any { it.equals(t, ignoreCase = true) } }.mapNotNull { type ->
                    runCatching {
                        val caps = info.getCapabilitiesForType(type)
                        val v = caps.videoCapabilities
                        val w = v?.supportedWidths?.upper ?: 0
                        val h = v?.supportedHeights?.upper ?: 0
                        val fps = runCatching { v?.getSupportedFrameRatesFor(w, h)?.upper?.toInt() ?: 0 }.getOrDefault(0)
                        DecoderRow(
                            name = info.name,
                            mime = type.lowercase(),
                            hardware = isHardware(info),
                            profiles = caps.profileLevels.mapNotNull { profileName(type, it.profile) }.distinct(),
                            maxWidth = w,
                            maxHeight = h,
                            maxFps = fps,
                        )
                    }.getOrNull()
                }
            }
            .sortedWith(compareBy({ videoMimes.indexOfFirst { m -> m.equals(it.mime, true) } }, { !it.hardware }, { it.name }))
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    fun display(display: Display?): DisplayCaps {
        if (display == null) return DisplayCaps(emptyList(), emptyList(), emptyList())
        val hdr = mutableListOf<HdrKind>()
        runCatching {
            val types: IntArray = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            for (t in types) {
                when (t) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> hdr += HdrKind.DOLBY_VISION
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdr += HdrKind.HDR10
                    Display.HdrCapabilities.HDR_TYPE_HLG -> hdr += HdrKind.HLG
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> hdr += HdrKind.HDR10_PLUS
                }
            }
        }
        val modes = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        return DisplayCaps(hdr.distinct(), modes.map { it.refreshRate }.distinct().sorted(), modes)
    }

    fun displayIsHdr(display: Display?): Boolean = display(display).hdr.isNotEmpty()

    private fun profileName(mime: String, profile: Int): String? = when (mime.lowercase()) {
        MimeTypes.VIDEO_H264 -> when (profile) {
            P.AVCProfileBaseline -> "Baseline"
            P.AVCProfileMain -> "Main"
            P.AVCProfileHigh -> "High"
            P.AVCProfileHigh10 -> "High 10"
            P.AVCProfileConstrainedBaseline -> "Constrained Baseline"
            P.AVCProfileConstrainedHigh -> "Constrained High"
            else -> null
        }
        MimeTypes.VIDEO_H265 -> when (profile) {
            P.HEVCProfileMain -> "Main"
            P.HEVCProfileMain10 -> "Main 10"
            P.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
            P.HEVCProfileMain10HDR10Plus -> "Main 10 HDR10+"
            else -> null
        }
        MimeTypes.VIDEO_VP9 -> when (profile) {
            P.VP9Profile0 -> "Profile 0"
            P.VP9Profile2 -> "Profile 2"
            P.VP9Profile2HDR -> "Profile 2 HDR"
            P.VP9Profile2HDR10Plus -> "Profile 2 HDR10+"
            else -> null
        }
        MimeTypes.VIDEO_AV1 -> when (profile) {
            P.AV1ProfileMain8 -> "Main 8"
            P.AV1ProfileMain10 -> "Main 10"
            P.AV1ProfileMain10HDR10 -> "Main 10 HDR10"
            P.AV1ProfileMain10HDR10Plus -> "Main 10 HDR10+"
            else -> null
        }
        MimeTypes.VIDEO_DOLBY_VISION -> when (profile) {
            P.DolbyVisionProfileDvheDtr -> "Profile 4"
            P.DolbyVisionProfileDvheStn -> "Profile 5"
            P.DolbyVisionProfileDvheDth -> "Profile 6"
            P.DolbyVisionProfileDvheDtb -> "Profile 7"
            P.DolbyVisionProfileDvheSt -> "Profile 8"
            P.DolbyVisionProfileDvavSe -> "Profile 9"
            else -> null
        }
        else -> null
    }
}
