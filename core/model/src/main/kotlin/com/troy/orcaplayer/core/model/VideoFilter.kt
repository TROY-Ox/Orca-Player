package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Picture adjustments run on the GPU (20.6). Every value is 0 when neutral; ranges are -1..1
 * except [hue] in degrees (-180..180) and [sharpness] 0..1.
 */
@Serializable
data class VideoFilter(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val gamma: Float = 0f,
    val hue: Float = 0f,
    val sharpness: Float = 0f,
) {
    val isNeutral: Boolean get() = this == VideoFilter()

    /** 2^(-gamma): 0 is linear, positive values lift dark tones. */
    val gammaExponent: Double get() = Math.pow(2.0, -gamma.toDouble().coerceIn(-1.0, 1.0))

    fun clamped() = VideoFilter(
        brightness.coerceIn(-1f, 1f), contrast.coerceIn(-1f, 1f), saturation.coerceIn(-1f, 1f),
        gamma.coerceIn(-1f, 1f), hue.coerceIn(-180f, 180f), sharpness.coerceIn(0f, 1f),
    )
}

@Serializable
data class FilterPreset(val name: String, val filter: VideoFilter)

/** The filter in use and saved presets, per profile. */
@Serializable
data class FilterStore(
    val current: VideoFilter = VideoFilter(),
    val presets: List<FilterPreset> = BuiltIn,
    /** Filters stay off for HDR video, which they would change to SDR colours. */
    val skipHdr: Boolean = true,
) {
    fun withPreset(p: FilterPreset) = copy(presets = presets.filterNot { it.name == p.name } + p)
    fun withoutPreset(name: String) = copy(presets = presets.filterNot { it.name == name })

    companion object {
        /** Starting presets; names are keys the UI translates. */
        val BuiltIn = listOf(
            FilterPreset("@vivid", VideoFilter(contrast = 0.1f, saturation = 0.25f)),
            FilterPreset("@cinema", VideoFilter(contrast = 0.15f, saturation = -0.1f, gamma = -0.1f)),
            FilterPreset("@bright", VideoFilter(brightness = 0.1f, gamma = 0.25f)),
            FilterPreset("@anime", VideoFilter(saturation = 0.15f, sharpness = 0.6f)),
        )
    }
}
