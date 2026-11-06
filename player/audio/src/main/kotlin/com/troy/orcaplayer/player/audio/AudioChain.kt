package com.troy.orcaplayer.player.audio

import com.troy.orcaplayer.core.model.AudioFx
import com.troy.orcaplayer.core.model.EQ_BANDS
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** How hard the output limiter holds peaks. */
enum class LimiterStrength(val ceilingDb: Float, val releaseMs: Float) {
    SOFT(-0.5f, 200f), MEDIUM(-1f, 90f), STRONG(-2f, 45f);

    companion object {
        fun fromKey(key: String) = when (key) {
            "soft" -> SOFT
            "strong" -> STRONG
            else -> MEDIUM
        }
    }
}

/** Everything the chain needs: the user's effect values plus switches from Settings and the device. */
data class ChainConfig(
    val fx: AudioFx = AudioFx(),
    /** Software gain above 100%, as a factor (1 = none). */
    val boost: Float = 1f,
    val normalize: Boolean = false,
    /** Quiet-recording mode: normalisation plus noise reduction and a gentle hiss filter. Experimental. */
    val quietRecording: Boolean = false,
    val limiter: LimiterStrength = LimiterStrength.MEDIUM,
    /** False while the system spatializer is active: then the app's own virtualizer is off. */
    val virtualizerAllowed: Boolean = true,
) {
    /** True when the chain would change the sound. Passthrough is only possible when this is false. */
    val isActive: Boolean get() = !fx.isNeutral || boost > 1f || normalize || quietRecording
}

/**
 * The one software audio chain: noise reduction (quiet mode), pre-amp, 10-band EQ, bass boost,
 * virtualizer, compressor (dynamic range, Late Night, normalisation), loudness, volume boost, limiter.
 *
 * Works on interleaved float samples in -1..1. Not thread safe: [process] runs on the audio thread,
 * [config] may be set from any thread and is picked up at the start of the next buffer.
 */
class AudioChain {
    @Volatile var config: ChainConfig = ChainConfig()

    private var applied: ChainConfig? = null
    private var sampleRate = 0
    private var channels = 0

    private var eq: Array<Biquad> = emptyArray()
    private var bass = Biquad(1)
    private var hiss = Biquad(1)
    private var rumble = Biquad(1)

    // Gains are ramped over one buffer when they change, so moving a slider never clicks.
    private var gainNow = 1f
    private var makeupNow = 1f

    private var compEnv = 0f
    private var agcEnv = 0f
    private var agcGain = 1f
    private var gateEnv = 0f
    private var limGain = 1f

    // Level meter, read by the UI.
    @Volatile var peakLevel: Float = 0f
        private set
    @Volatile var rmsLevel: Float = 0f
        private set

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        eq = Array(EQ_BANDS) { Biquad(channels) }
        bass = Biquad(channels)
        hiss = Biquad(channels)
        rumble = Biquad(channels)
        applied = null
        reset()
    }

    fun reset() {
        eq.forEach { it.reset() }
        bass.reset(); hiss.reset(); rumble.reset()
        compEnv = 0f; agcEnv = 0f; agcGain = 1f; gateEnv = 0f; limGain = 1f
        peakLevel = 0f; rmsLevel = 0f
    }

    private fun apply(c: ChainConfig) {
        val fx = c.fx
        val on = fx.enabled
        for (i in 0 until EQ_BANDS) {
            val g = if (on) fx.bands.getOrElse(i) { 0f }.toDouble() else 0.0
            eq[i].peaking(sampleRate, BAND_FREQS[i], 1.41, g)
        }
        bass.lowShelf(sampleRate, 100.0, if (on) fx.bassDb.toDouble() else 0.0)
        if (c.quietRecording) {
            hiss.highShelf(sampleRate, 6000.0, -6.0)
            rumble.lowShelf(sampleRate, 60.0, -6.0)
        } else {
            hiss.setIdentity()
            rumble.setIdentity()
        }
        applied = c
    }

    /** Processes [frames] interleaved frames of [buf] in place. */
    fun process(buf: FloatArray, frames: Int) {
        if (channels == 0 || frames == 0) return
        val c = config
        if (c !== applied) apply(c)
        val fx = c.fx
        val on = fx.enabled
        val ch = channels
        val n = frames * ch

        val targetGain = dbToGain(if (on) fx.preampDb else 0f) * c.boost
        val compAmount = if (on) fx.compressor else 0f
        val loud = if (on) fx.loudness else 0f
        val targetMakeup = dbToGain(loud * 9f + compAmount * 6f)
        val width = if (on && c.virtualizerAllowed && ch == 2) fx.virtualizer else 0f
        val normalize = c.normalize || c.quietRecording
        val gate = c.quietRecording

        // Compressor: threshold falls and ratio rises with the amount.
        val compOn = compAmount > 0f
        val thresholdDb = -10f - 22f * compAmount
        val ratio = 1f + 5f * compAmount
        val compAttack = coef(5f)
        val compRelease = coef(150f)
        // Normalisation: slow levelling toward -20 dBFS, between -6 and +12 dB.
        val agcCoef = coef(2500f)
        val gateAttack = coef(2f)
        val gateRelease = coef(120f)
        val ceiling = dbToGain(c.limiter.ceilingDb)
        val limRelease = coef(c.limiter.releaseMs)

        val gainStep = (targetGain - gainNow) / frames
        val makeupStep = (targetMakeup - makeupNow) / frames

        var peak = 0f
        var sumSq = 0.0
        var i = 0
        while (i < n) {
            // Filters per channel
            var frameMax = 0f
            for (k in 0 until ch) {
                var x = buf[i + k]
                if (!hiss.bypass) x = hiss.process(x, k)
                if (!rumble.bypass) x = rumble.process(x, k)
                x *= gainNow
                for (b in eq) if (!b.bypass) x = b.process(x, k)
                if (!bass.bypass) x = bass.process(x, k)
                buf[i + k] = x
                frameMax = max(frameMax, abs(x))
            }
            // Virtualizer: wider stereo through mid/side.
            if (width > 0f) {
                val l = buf[i]
                val r = buf[i + 1]
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * (1f + 1.6f * width)
                val norm = 1f / (1f + 0.35f * width)
                buf[i] = (mid + side) * norm
                buf[i + 1] = (mid - side) * norm
                frameMax = max(abs(buf[i]), abs(buf[i + 1]))
            }
            var g = 1f
            // Noise gate (quiet mode): gently lowers what is below -50 dBFS.
            if (gate) {
                gateEnv = if (frameMax > gateEnv) gateAttack * gateEnv + (1 - gateAttack) * frameMax else gateRelease * gateEnv + (1 - gateRelease) * frameMax
                val envDb = gainToDb(gateEnv)
                if (envDb < -50f) g *= dbToGain(((envDb + 50f) * 1f).coerceAtLeast(-18f))
            }
            if (compOn) {
                compEnv = if (frameMax > compEnv) compAttack * compEnv + (1 - compAttack) * frameMax else compRelease * compEnv + (1 - compRelease) * frameMax
                val envDb = gainToDb(compEnv)
                if (envDb > thresholdDb) g *= dbToGain((thresholdDb - envDb) * (1f - 1f / ratio))
            }
            if (normalize) {
                agcEnv = agcCoef * agcEnv + (1 - agcCoef) * frameMax * frameMax
                val rmsDb = gainToDb(sqrt(agcEnv) * 1.414f)
                val want = if (rmsDb < -60f) 0f else (-20f - rmsDb).coerceIn(-6f, 12f)
                val wantGain = dbToGain(want)
                agcGain += (wantGain - agcGain) * 0.0005f
                g *= agcGain
            }
            g *= makeupNow
            // Limiter: instant attack, smooth release; nothing leaves above the ceiling.
            val level = frameMax * g
            val need = if (level > ceiling) ceiling / level else 1f
            limGain = if (need < limGain) need else limRelease * limGain + (1 - limRelease) * min(1f, need)
            val total = g * limGain
            for (k in 0 until ch) {
                var y = buf[i + k] * total
                if (y > ceiling) y = ceiling else if (y < -ceiling) y = -ceiling
                buf[i + k] = y
                val a = abs(y)
                if (a > peak) peak = a
                sumSq += (y * y).toDouble()
            }
            gainNow += gainStep
            makeupNow += makeupStep
            i += ch
        }
        gainNow = targetGain
        makeupNow = targetMakeup
        rmsLevel = sqrt(sumSq / n).toFloat()
        // The meter falls slowly so it is readable.
        peakLevel = max(peak, peakLevel * 0.85f)
    }

    private fun coef(ms: Float): Float = exp(-1f / (ms * 0.001f * sampleRate)).toFloat()

    companion object {
        val BAND_FREQS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

        fun dbToGain(db: Float): Float = 10f.pow(db / 20f)
        fun gainToDb(g: Float): Float = if (g <= 1e-9f) -180f else 20f * log10(g)
    }
}
