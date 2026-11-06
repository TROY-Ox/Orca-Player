package com.troy.orcaplayer.core.design.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/** A theme spring: damping ratio and response in seconds (section 5 of the theme). */
@Immutable
data class TroySpring(val damping: Float, val response: Float) {
    /** stiffness = (2π / response)² */
    val stiffness: Float get() = SpringMath.stiffness(response)
}

object SpringMath {
    fun stiffness(response: Float): Float {
        val w = (2 * PI / response).toFloat()
        return w * w
    }

    /** Momentum projection: projected = y + (v/1000) * 0.998 / (1 - 0.998). v in px/s. */
    fun project(position: Float, velocity: Float, decelerationRate: Float = 0.998f): Float =
        position + (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

    /** Rubber-band past an edge: over*dim*0.55 / (dim + 0.55*|over|). */
    fun rubber(over: Float, dimension: Float): Float =
        over * dimension * 0.55f / (dimension + 0.55f * abs(over))
}

object TroySprings {
    val Default = TroySpring(1.0f, 0.4f)
    val Toggle = TroySpring(0.8f, 0.3f)
    val Sheet = TroySpring(0.8f, 0.3f)
    val Move = TroySpring(1.0f, 0.4f)
    val Materialize = TroySpring(1.0f, 0.55f)

    /** Lite tier caps toggles and panels at response 0.25. */
    const val LITE_RESPONSE_CAP = 0.25f
}

/** Motion settings for the current device and accessibility state. */
@Immutable
data class TroyMotion(
    val reduceMotion: Boolean = false,
    val lite: Boolean = false,
) {
    fun resolve(s: TroySpring): TroySpring = if (lite) s.copy(response = min(s.response, TroySprings.LITE_RESPONSE_CAP)) else s

    fun <T> spec(s: TroySpring = TroySprings.Default): AnimationSpec<T> =
        if (reduceMotion) snap() else resolve(s).let { spring(dampingRatio = it.damping, stiffness = it.stiffness) }

    fun <T> springSpec(s: TroySpring = TroySprings.Default, visibilityThreshold: T? = null): SpringSpec<T> =
        resolve(s).let { spring(dampingRatio = it.damping, stiffness = it.stiffness, visibilityThreshold = visibilityThreshold) }

    /** Blur on materialize: only on Android 12+ outside the lite tier and without reduce motion. */
    val allowBlur: Boolean get() = !lite && !reduceMotion

    /** Stagger between panels that materialize together. */
    val staggerMillis: Int get() = if (reduceMotion) 0 else 70
}
