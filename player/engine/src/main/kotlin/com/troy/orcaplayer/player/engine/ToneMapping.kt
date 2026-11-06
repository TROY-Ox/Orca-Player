package com.troy.orcaplayer.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.SingleColorLut
import kotlin.math.pow

/**
 * The app's own GPU tone mapping, an advanced option for screens where the system makes HDR video
 * look washed out. A 3D colour table applies the chosen curve, then a brightness adjustment.
 */
enum class ToneMapMethod {
    SYSTEM, REINHARD, FILMIC;

    companion object {
        fun fromKey(key: String) = when (key) {
            "reinhard" -> REINHARD
            "filmic" -> FILMIC
            else -> SYSTEM
        }
    }
}

@OptIn(UnstableApi::class)
object ToneMapping {
    private const val SIZE = 33

    /** Effects for [method] with [brightness] from -1 to 1, or none for the system tone mapping. */
    fun effects(method: ToneMapMethod, brightness: Float): List<Effect> {
        if (method == ToneMapMethod.SYSTEM) return emptyList()
        val list = mutableListOf<Effect>(SingleColorLut.createFromCube(cube(method)))
        if (brightness != 0f) list += Brightness(brightness.coerceIn(-1f, 1f))
        return list
    }

    private fun curve(method: ToneMapMethod, x: Double): Double = when (method) {
        ToneMapMethod.REINHARD -> {
            // Extended Reinhard with white point 4.
            val w = 4.0
            x * (1 + x / (w * w)) / (1 + x)
        }
        ToneMapMethod.FILMIC -> {
            // Hable "Uncharted 2" filmic curve, normalised to white point 11.2.
            fun h(v: Double): Double {
                val a = 0.15; val b = 0.50; val c = 0.10; val d = 0.20; val e = 0.02; val f = 0.30
                return ((v * (a * v + c * b) + d * e) / (v * (a * v + b) + d * f)) - e / f
            }
            h(x * 2.0) / h(11.2)
        }
        ToneMapMethod.SYSTEM -> x
    }

    private fun cube(method: ToneMapMethod): Array<Array<IntArray>> {
        val exposure = 2.2
        return Array(SIZE) { r ->
            Array(SIZE) { g ->
                IntArray(SIZE) { b ->
                    val rgb = listOf(r, g, b).map { (it.toDouble() / (SIZE - 1)).pow(2.2) * exposure }
                    val luma = 0.2627 * rgb[0] + 0.6780 * rgb[1] + 0.0593 * rgb[2]
                    val mapped = curve(method, luma)
                    val scale = if (luma > 1e-6) mapped / luma else 1.0
                    // Map by luminance to keep hues, with a small saturation lift against the washed-out look.
                    val out = rgb.map { ch ->
                        val v = (luma * scale + (ch - luma) * scale * 1.1).coerceIn(0.0, 1.0)
                        (v.pow(1 / 2.2) * 255 + 0.5).toInt().coerceIn(0, 255)
                    }
                    (0xFF shl 24) or (out[0] shl 16) or (out[1] shl 8) or out[2]
                }
            }
        }
    }
}
