package com.troy.orcaplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLayoutEditsTest {
    private val base = PlayerLayout.Default

    @Test
    fun movesAControlToAnotherSlot() {
        val l = PlayerLayoutEdits.moveToSlot(base, PlayerControl.SPEED, ControlSlot.TOP_END)
        val speed = l.controls.single { it.control == PlayerControl.SPEED }
        assertEquals(ControlSlot.TOP_END, speed.slot)
        assertEquals(PlayerControl.SPEED, l.controls.last { it.slot == ControlSlot.TOP_END }.control)
        assertEquals(base.controls.size, l.controls.size)
    }

    @Test
    fun shiftsWithinItsSlotOnly() {
        val center = { l: PlayerLayout -> l.controls.filter { it.slot == ControlSlot.CENTER }.map { it.control } }
        val l = PlayerLayoutEdits.shift(base, PlayerControl.PLAY_PAUSE, -1)
        assertEquals(listOf(PlayerControl.PREVIOUS, PlayerControl.PLAY_PAUSE, PlayerControl.REWIND, PlayerControl.FORWARD, PlayerControl.NEXT), center(l))
        // The first control of a slot can't move earlier.
        assertEquals(base, PlayerLayoutEdits.shift(base, PlayerControl.PREVIOUS, -1))
    }

    @Test
    fun completesOldLayouts() {
        val old = PlayerLayout(listOf(PlayerControlConfig(PlayerControl.PLAY_PAUSE, ControlSlot.CENTER)))
        val l = PlayerLayoutEdits.complete(old)
        assertEquals(PlayerControl.entries.size, l.controls.size)
        assertTrue(l.controls.single { it.control == PlayerControl.SPEED }.visible)
        assertFalse(l.controls.single { it.control == PlayerControl.EQUALIZER }.visible)
    }
}
