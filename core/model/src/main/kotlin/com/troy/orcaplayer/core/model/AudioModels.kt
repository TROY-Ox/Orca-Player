package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable

/** Number of graphic EQ bands: 31 Hz to 16 kHz, one octave apart. */
const val EQ_BANDS = 10

/**
 * The user's audio effect values. Gains are in dB; the amounts are 0..1.
 * [preset] names the preset these values came from ("custom" once a value is moved by hand).
 */
@Serializable
data class AudioFx(
    val enabled: Boolean = true,
    val preset: String = "flat",
    val bands: List<Float> = List(EQ_BANDS) { 0f },
    val preampDb: Float = 0f,
    val bassDb: Float = 0f,
    val virtualizer: Float = 0f,
    val loudness: Float = 0f,
    val compressor: Float = 0f,
    /** Extra volume in percent, 100 = none. Only used while "Enable extra volume" is on. */
    val boostPercent: Int = 100,
) {
    /** True when the effects leave the sound unchanged (boost is checked separately). */
    val isNeutral: Boolean
        get() = !enabled || (
            bands.all { it == 0f } && preampDb == 0f && bassDb == 0f &&
                virtualizer == 0f && loudness == 0f && compressor == 0f
            )

    fun withPreset(p: EqPreset): AudioFx = copy(
        preset = p.id,
        bands = p.bands.take(EQ_BANDS).let { it + List(EQ_BANDS - it.size) { 0f } },
        preampDb = p.preampDb,
        bassDb = p.bassDb,
        virtualizer = p.virtualizer,
        loudness = p.loudness,
        compressor = p.compressor,
    )
}

/** A named preset. Built-in presets have [builtIn] set and a fixed ID; their names are translated by the app. */
@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val bands: List<Float>,
    val preampDb: Float = 0f,
    val bassDb: Float = 0f,
    val virtualizer: Float = 0f,
    val loudness: Float = 0f,
    val compressor: Float = 0f,
    val builtIn: Boolean = false,
)

/**
 * Everything the equalizer stores for one profile: current values, the user's presets, and the
 * values saved for single videos and folders (applied automatically when they play).
 */
@Serializable
data class AudioStore(
    val current: AudioFx = AudioFx(),
    val custom: List<EqPreset> = emptyList(),
    val perMedia: Map<String, AudioFx> = emptyMap(),
    val perFolder: Map<String, AudioFx> = emptyMap(),
    val boostWarned: Boolean = false,
) {
    fun withMedia(key: String, fx: AudioFx) = copy(perMedia = bounded(perMedia, key, fx))
    fun withFolder(key: String, fx: AudioFx) = copy(perFolder = bounded(perFolder, key, fx))

    companion object {
        const val MAX_SAVED = 500

        private fun bounded(map: Map<String, AudioFx>, key: String, fx: AudioFx): Map<String, AudioFx> {
            val next = LinkedHashMap(map)
            next.remove(key)
            next[key] = fx
            while (next.size > MAX_SAVED) next.remove(next.keys.first())
            return next
        }
    }
}
