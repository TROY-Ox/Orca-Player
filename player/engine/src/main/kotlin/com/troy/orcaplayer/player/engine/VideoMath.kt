package com.troy.orcaplayer.player.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/** HDR formats as shown to the user. */
enum class HdrKind { SDR, HDR10, HDR10_PLUS, HLG, DOLBY_VISION }

/** Dolby Vision details read from a codecs string such as "dvhe.05.06" or "dvh1.08.07". */
object DolbyVision {
    private val prefixes = listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

    fun isDolbyVision(codecs: String?): Boolean = codecs?.lowercase()?.let { c -> prefixes.any { c.startsWith(it) } } == true

    fun profileOf(codecs: String?): Int? {
        if (!isDolbyVision(codecs)) return null
        return codecs!!.split('.').getOrNull(1)?.trim()?.toIntOrNull()
    }

    /**
     * Profile 5 has no HDR10 or HLG base layer: without a Dolby Vision decoder it plays with wrong
     * green and purple colours. Profiles 7, 8 and 9 fall back to their base layer.
     */
    fun hasNoFallback(profile: Int?): Boolean = profile == 5 || profile == 4
}

/**
 * Ranks versions of the same title for the stream picker. Higher is better. On devices without Dolby
 * Vision, DV-only streams (profile 5) go below HDR10 and SDR versions.
 */
object StreamRanking {
    fun score(hdr: HdrKind, dvProfile: Int?, height: Int, deviceHasDolbyVision: Boolean, displayHdr: Boolean): Int {
        val resolution = (height.coerceIn(0, 4320) / 10)
        if (hdr == HdrKind.DOLBY_VISION && !deviceHasDolbyVision && DolbyVision.hasNoFallback(dvProfile)) return -1000 + resolution
        val hdrBonus = when {
            !displayHdr -> 0
            hdr == HdrKind.DOLBY_VISION && deviceHasDolbyVision -> 40
            hdr == HdrKind.DOLBY_VISION -> 25 // plays its base layer
            hdr == HdrKind.HDR10_PLUS -> 30
            hdr == HdrKind.HDR10 -> 25
            hdr == HdrKind.HLG -> 20
            else -> 0
        }
        return resolution + hdrBonus
    }
}

/** One display mode: physical size and refresh rate. */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/** Chooses the display mode for frame-rate matching (24, 25, 30, 50 or 60 fps, also on 120 Hz screens). */
object FrameRateMatch {
    /**
     * Picks a mode with the current resolution whose refresh rate is a whole multiple of [fps]
     * (23.976 counts as 24 only when the screen offers no exact 23.976 mode). Prefers the exact rate,
     * then the highest multiple. Returns null when the current mode already fits or nothing does.
     */
    fun pick(fps: Float, modes: List<DisplayModeInfo>, current: DisplayModeInfo): DisplayModeInfo? {
        if (fps < 10f || fps > 125f) return null
        val sameSize = modes.filter { it.width == current.width && it.height == current.height }
        fun multiple(rate: Float, tol: Float): Int? {
            val m = (rate / fps).roundToInt()
            if (m < 1) return null
            return if (abs(rate - fps * m) <= tol * m) m else null
        }
        // Exact first (within 0.01 Hz per multiple), then loose (24 on 23.976 and the reverse).
        for (tol in floatArrayOf(0.01f, 0.05f)) {
            val fits = sameSize.mapNotNull { mode -> multiple(mode.refreshRate, tol)?.let { mode to it } }
            if (fits.isEmpty()) continue
            if (fits.any { it.first.id == current.id }) return null
            return fits.maxBy { it.first.refreshRate }.first
        }
        return null
    }
}
