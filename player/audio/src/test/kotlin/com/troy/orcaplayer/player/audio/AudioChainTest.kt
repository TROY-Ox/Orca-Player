package com.troy.orcaplayer.player.audio

import com.troy.orcaplayer.core.model.AudioFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioChainTest {
    private val rate = 48_000

    private fun sine(freq: Double, amp: Float, frames: Int, channels: Int = 2): FloatArray {
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (amp * sin(2 * PI * freq * f / rate)).toFloat()
            for (c in 0 until channels) out[f * channels + c] = v
        }
        return out
    }

    private fun rms(buf: FloatArray, from: Int = buf.size / 2): Double {
        var s = 0.0
        for (i in from until buf.size) s += buf[i] * buf[i].toDouble()
        return sqrt(s / (buf.size - from))
    }

    @Test
    fun flatChainLeavesSignalUnchanged() {
        val chain = AudioChain().apply { configure(rate, 2) }
        val input = sine(1000.0, 0.3f, 4800)
        val buf = input.copyOf()
        chain.process(buf, 4800)
        for (i in buf.indices) assertEquals(input[i], buf[i], 1e-5f)
        assertFalse(ChainConfig().isActive)
    }

    @Test
    fun peakingBandHitsItsGainAtCentre() {
        val b = Biquad(1)
        b.peaking(rate, 1000.0, 1.41, 6.0)
        assertEquals(6.0, b.responseDb(rate, 1000.0), 0.05)
        assertTrue(abs(b.responseDb(rate, 50.0)) < 0.3)
    }

    @Test
    fun shelvesBoostTheirSide() {
        val low = Biquad(1).apply { lowShelf(rate, 100.0, 6.0) }
        assertEquals(6.0, low.responseDb(rate, 20.0), 0.5)
        assertTrue(abs(low.responseDb(rate, 5000.0)) < 0.3)
        val high = Biquad(1).apply { highShelf(rate, 6000.0, -6.0) }
        assertEquals(-6.0, high.responseDb(rate, 20000.0), 0.6)
    }

    @Test
    fun limiterNeverLetsPeaksThrough() {
        val chain = AudioChain().apply { configure(rate, 2) }
        chain.config = ChainConfig(fx = AudioFx(preampDb = 12f), boost = 3f, limiter = LimiterStrength.MEDIUM)
        val buf = sine(200.0, 0.9f, 9600)
        chain.process(buf, 9600)
        val ceiling = AudioChain.dbToGain(LimiterStrength.MEDIUM.ceilingDb)
        assertTrue(buf.all { abs(it) <= ceiling + 1e-6f })
        assertTrue(ChainConfig(boost = 1.5f).isActive)
    }

    @Test
    fun compressorEvensOutLoudAndQuiet() {
        fun gainFor(amp: Float): Double {
            val chain = AudioChain().apply { configure(rate, 2) }
            chain.config = ChainConfig(fx = AudioFx(compressor = 1f), limiter = LimiterStrength.SOFT)
            val buf = sine(500.0, amp, 24_000)
            chain.process(buf, 24_000)
            return rms(buf) / (amp / sqrt(2.0))
        }
        // The loud signal is turned down more than the quiet one.
        assertTrue(gainFor(0.9f) < gainFor(0.05f))
    }

    @Test
    fun virtualizerOnlyWidensStereo() {
        val chain = AudioChain().apply { configure(rate, 2) }
        chain.config = ChainConfig(fx = AudioFx(virtualizer = 1f))
        // Mono content (L == R) has no side signal, so only the level changes.
        val buf = sine(440.0, 0.3f, 4800)
        chain.process(buf, 4800)
        for (f in 0 until 4800) assertEquals(buf[f * 2], buf[f * 2 + 1], 1e-6f)
    }

    @Test
    fun presetsHaveTenBands() {
        Presets.builtIn.forEach { assertEquals(10, it.bands.size) }
        assertEquals(Presets.LATE_NIGHT, Presets.byId("late_night", emptyList())?.id)
    }
}
