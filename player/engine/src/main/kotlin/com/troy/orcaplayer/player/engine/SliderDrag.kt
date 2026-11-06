package com.troy.orcaplayer.player.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/** Settings for the speed-sensitive brightness and volume sliders (section 8). */
data class SliderConfig(
    /** 0.5 (gentle) to 2 (strong). Scales how far a fast drag goes. */
    val acceleration: Float = 1f,
    /** Distance in dp a very slow drag needs for one step. */
    val slowStepDp: Float = 10f,
    /** Finger speed (dp per second) at which the fastest rate is reached. */
    val fastSpeedDp: Float = 1400f,
    /** Below this speed (dp per second) every step gets a vibration tick. */
    val slowSpeedDp: Float = 160f,
    /** Fixed linear slider instead of acceleration. */
    val linear: Boolean = false,
    /** Linear mode and the fast end: dp needed to cross the whole range. */
    val fullRangeDp: Float = 260f,
)

/**
 * One drag on a slider. Feed it finger movement in dp and the time since the last event; it returns the
 * new value, already snapped to whole steps. The change per dp follows a smooth curve of the finger speed
 * (smoothstep), so there is no jump between slow and fast. The value is final when the finger lifts.
 */
class SliderDrag(
    start: Float,
    private val min: Float,
    private val max: Float,
    /** One step: 1 for brightness percent, or one system volume step. */
    private val step: Float,
    private val config: SliderConfig = SliderConfig(),
) {
    private var raw = start.coerceIn(min, max)
    private var speed = 0f

    var value: Float = snap(raw)
        private set

    /** True when the last move should give a vibration tick (a step was crossed in slow mode). */
    var tick: Boolean = false
        private set

    /** Upward movement is positive. Returns the snapped value. */
    fun move(dyDp: Float, dtMs: Long): Float {
        val dt = dtMs.coerceAtLeast(1L) / 1000f
        val instant = abs(dyDp) / dt
        // Light smoothing so one noisy event doesn't swing the rate.
        speed = if (speed == 0f) instant else speed * 0.6f + instant * 0.4f
        raw = (raw + dyDp * ratePerDp(speed)).coerceIn(min, max)
        val next = snap(raw)
        tick = next != value && speed < config.slowSpeedDp
        value = next
        return value
    }

    /** Units of value per dp of finger movement at [speedDp] dp per second. */
    fun ratePerDp(speedDp: Float): Float {
        val range = max - min
        val fastRate = range / config.fullRangeDp * config.acceleration.coerceIn(0.25f, 3f)
        if (config.linear) return range / config.fullRangeDp
        val slowRate = step / config.slowStepDp
        val t = smoothstep(config.slowSpeedDp * 0.5f, config.fastSpeedDp, speedDp)
        return slowRate + (fastRate * 2.5f - slowRate).coerceAtLeast(0f) * t
    }

    private fun snap(v: Float): Float = ((v - min) / step).roundToInt() * step + min

    companion object {
        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
