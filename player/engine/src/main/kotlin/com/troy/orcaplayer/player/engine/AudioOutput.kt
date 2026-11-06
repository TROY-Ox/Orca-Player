package com.troy.orcaplayer.player.engine

import android.content.Context
import android.media.AudioManager
import android.os.Build

/** How audio leaves the app right now, for the info overlay and the equalizer notice. */
enum class AudioOutputMode { NORMAL, PASSTHROUGH, SPATIAL }

/** Reasons the engine changed the audio path on its own; the player shows them as a short notice. */
enum class EngineNotice { DECODED_FOR_EFFECTS, DECODED_FOR_SPEED }

object Spatial {
    /** True when Android's spatializer (Android 13+) is available and turned on for this output. */
    fun systemActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 32) return false
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching { am.spatializer.isAvailable && am.spatializer.isEnabled }.getOrDefault(false)
    }
}
