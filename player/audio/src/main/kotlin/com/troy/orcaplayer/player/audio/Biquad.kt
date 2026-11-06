package com.troy.orcaplayer.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A second-order IIR filter (RBJ audio EQ cookbook), transposed direct form II, with separate
 * state per channel. Coefficients can change while playing; the state is kept so there are no clicks.
 */
class Biquad(channels: Int) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    /** True when the filter passes the signal unchanged, so it can be skipped. */
    var bypass = true
        private set

    fun process(x: Float, ch: Int): Float {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
        bypass = false
    }

    fun setIdentity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
        bypass = true
    }

    fun peaking(sampleRate: Int, freq: Double, q: Double, gainDb: Double) {
        if (gainDb == 0.0 || freq >= sampleRate * 0.45) return setIdentity()
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * freq / sampleRate
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun lowShelf(sampleRate: Int, freq: Double, gainDb: Double, slope: Double = 1.0) = shelf(sampleRate, freq, gainDb, slope, low = true)

    fun highShelf(sampleRate: Int, freq: Double, gainDb: Double, slope: Double = 1.0) = shelf(sampleRate, freq, gainDb, slope, low = false)

    private fun shelf(sampleRate: Int, freq: Double, gainDb: Double, slope: Double, low: Boolean) {
        if (gainDb == 0.0 || freq >= sampleRate * 0.45) return setIdentity()
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * freq / sampleRate
        val c = cos(w)
        val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val k = 2 * sqrt(a) * alpha
        if (low) {
            set(
                a * ((a + 1) - (a - 1) * c + k),
                2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - k),
                (a + 1) + (a - 1) * c + k,
                -2 * ((a - 1) + (a + 1) * c),
                (a + 1) + (a - 1) * c - k,
            )
        } else {
            set(
                a * ((a + 1) + (a - 1) * c + k),
                -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - k),
                (a + 1) - (a - 1) * c + k,
                2 * ((a - 1) - (a + 1) * c),
                (a + 1) - (a - 1) * c - k,
            )
        }
    }

    /** Magnitude response in dB at [freq], for tests and the EQ curve drawing. */
    fun responseDb(sampleRate: Int, freq: Double): Double {
        val w = 2 * PI * freq / sampleRate
        val cr1 = cos(w); val ci1 = -sin(w)
        val cr2 = cos(2 * w); val ci2 = -sin(2 * w)
        val nr = b0 + b1 * cr1 + b2 * cr2
        val ni = b1 * ci1 + b2 * ci2
        val dr = 1 + a1 * cr1 + a2 * cr2
        val di = a1 * ci1 + a2 * ci2
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20 * kotlin.math.log10(mag)
    }
}
