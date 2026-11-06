package com.troy.orcaplayer.core.design

import com.troy.orcaplayer.core.design.motion.SpringMath
import com.troy.orcaplayer.core.design.motion.TroyMotion
import com.troy.orcaplayer.core.design.motion.TroySprings
import org.junit.Assert.assertEquals
import org.junit.Test

class SpringMathTest {
    @Test
    fun stiffnessMatchesPrompt() {
        assertEquals(247f, SpringMath.stiffness(0.4f), 1f)
        assertEquals(439f, SpringMath.stiffness(0.3f), 1f)
        assertEquals(130f, SpringMath.stiffness(0.55f), 1f)
    }

    @Test
    fun liteCapsResponse() {
        assertEquals(0.25f, TroyMotion(lite = true).resolve(TroySprings.Materialize).response, 0.0001f)
        assertEquals(0.55f, TroyMotion().resolve(TroySprings.Materialize).response, 0.0001f)
    }

    @Test
    fun rubberBandIsSmallerThanOverscroll() {
        val r = SpringMath.rubber(100f, 800f)
        assert(r in 0f..100f)
    }
}
