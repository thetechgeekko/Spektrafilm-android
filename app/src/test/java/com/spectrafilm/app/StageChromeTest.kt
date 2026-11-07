package com.spectrafilm.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stage row/rail tap rule and the film-balance retarget used by the look previews. */
class StageChromeTest {

    @Test
    fun tappingAClosedStageOpensItsFirstGroup() {
        assertEquals(Category.PRESETS, stageTapTarget(Stage.LOOK, null))
        assertEquals(Category.SIMULATION, stageTapTarget(Stage.FILM, Category.PAPER))
    }

    @Test
    fun tappingTheOpenStageClosesThePanel() {
        assertNull(stageTapTarget(Stage.FILM, Category.GRAIN))
        assertNull(stageTapTarget(Stage.FINISH, Category.TONE_CURVE))
    }

    @Test
    fun noBalanceChangeNeedsNoRetarget() {
        assertNull(filmBalanceRetarget(null, null))
        val m = floatArrayOf(1.1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0.9f)
        assertNull(filmBalanceRetarget(m, m.copyOf()))
    }

    @Test
    fun retargetUndoesTheCurrentBalanceAndAppliesTheNewOne() {
        val from = floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0.5f)
        val to = floatArrayOf(1f, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 1f)
        val r = filmBalanceRetarget(from, to)!!
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 2f), r, 1e-6f)
        // Balance off in the look: only the current balance is undone.
        val off = filmBalanceRetarget(from, null)!!
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 2f), off, 1e-6f)
    }
}
