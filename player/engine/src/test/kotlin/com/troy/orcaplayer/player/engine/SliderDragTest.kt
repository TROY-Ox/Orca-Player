package com.troy.orcaplayer.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderDragTest {
    @Test fun slowDragMovesOneStepAtATime() {
        val d = SliderDrag(start = 50f, min = 0f, max = 100f, step = 1f)
        // 10 dp over 200 ms = 50 dp/s: one step.
        d.move(10f, 200)
        assertEquals(51f, d.value)
        assertTrue(d.tick)
    }

    @Test fun fastDragCrossesTheWholeRange() {
        val d = SliderDrag(start = 0f, min = 0f, max = 100f, step = 1f)
        repeat(10) { d.move(40f, 16) } // 2500 dp/s, 400 dp total
        assertEquals(100f, d.value)
    }

    @Test fun rateIsSmoothAndRising() {
        val d = SliderDrag(start = 0f, min = 0f, max = 100f, step = 1f)
        var last = 0f
        var speed = 0f
        while (speed <= 2000f) {
            val r = d.ratePerDp(speed)
            assertTrue(r >= last)
            assertTrue(r - last < 0.05f)
            last = r
            speed += 10f
        }
    }

    @Test fun clampsAndSnaps() {
        val d = SliderDrag(start = 3f, min = 0f, max = 15f, step = 1f)
        d.move(-500f, 16)
        assertEquals(0f, d.value)
    }

    @Test fun linearModeIsConstant() {
        val d = SliderDrag(0f, 0f, 100f, 1f, SliderConfig(linear = true))
        assertEquals(d.ratePerDp(10f), d.ratePerDp(3000f))
    }
}
