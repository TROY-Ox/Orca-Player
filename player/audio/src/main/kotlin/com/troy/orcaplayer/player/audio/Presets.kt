package com.troy.orcaplayer.player.audio

import com.troy.orcaplayer.core.model.EqPreset

/** Built-in presets. Their names are translated by the app from the ID. */
object Presets {
    const val FLAT = "flat"
    const val BASS_BOOST = "bass_boost"
    const val TREBLE_BOOST = "treble_boost"
    const val VOCAL = "vocal"
    const val MOVIE = "movie"
    /** Replaces the old "Night Mode" name, so it isn't confused with the night mode tool. */
    const val LATE_NIGHT = "late_night"
    const val CUSTOM = "custom"

    val builtIn: List<EqPreset> = listOf(
        EqPreset(FLAT, "Flat", List(10) { 0f }, builtIn = true),
        EqPreset(BASS_BOOST, "Bass Boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), bassDb = 3f, builtIn = true),
        EqPreset(TREBLE_BOOST, "Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3f, 5f, 6f), builtIn = true),
        EqPreset(VOCAL, "Vocal", listOf(-2f, -2f, -1f, 0f, 2f, 3.5f, 3.5f, 2f, 0f, -1f), builtIn = true),
        EqPreset(MOVIE, "Movie", listOf(3f, 2.5f, 1f, 0f, 0f, 1.5f, 2.5f, 2f, 1.5f, 1f), virtualizer = 0.35f, builtIn = true),
        EqPreset(LATE_NIGHT, "Late Night", listOf(-3f, -2f, -1f, 0f, 1f, 2f, 2f, 1f, 0f, -1f), compressor = 0.8f, loudness = 0.4f, builtIn = true),
    )

    fun byId(id: String, custom: List<EqPreset>): EqPreset? = builtIn.firstOrNull { it.id == id } ?: custom.firstOrNull { it.id == id }
}
