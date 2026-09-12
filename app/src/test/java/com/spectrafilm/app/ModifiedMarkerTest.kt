package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marker that says a parameter is away from its neutral default.
 *
 * It drives the value pill's fill AND whether double-tap-to-reset is offered, so a wrong
 * answer here is visible on every slider in the app at once. The case worth pinning is the
 * decoded one: an interactive edit always arrives pre-snapped, but a value read back from a
 * preset or a restored session does not, and a default that misses the grid by a float hair
 * would mark a parameter as changed with no way for the user to clear it.
 */
class ModifiedMarkerTest {

    private val unit = 0f..1f

    @Test
    fun aParameterSittingOnItsDefaultIsNotMarked() {
        assertFalse(isModifiedFromDefault(value = 0.5f, default = 0.5f, range = unit, step = 0.1f))
    }

    @Test
    fun aParameterMovedOffItsDefaultIsMarked() {
        assertTrue(isModifiedFromDefault(value = 0.7f, default = 0.5f, range = unit, step = 0.1f))
    }

    @Test
    fun aParameterWithNoDeclaredDefaultIsNeverMarked() {
        // Absence of a neutral is not evidence of neutrality — there is nothing to be away from.
        assertFalse(isModifiedFromDefault(value = 999f, default = null, range = unit, step = 0.1f))
    }

    @Test
    fun aSubStepDifferenceIsNotAChange() {
        // The regression this function exists to prevent: 0.30000001 decoded against a 0.3
        // default is the SAME position on a 0.1 grid, and must not latch the marker on.
        val decoded = 0.3f + 1e-7f
        assertFalse(isModifiedFromDefault(value = decoded, default = 0.3f, range = unit, step = 0.1f))
    }

    @Test
    fun anOffGridDefaultStillClearsOnceTheValueSnapsToIt() {
        // Presets may carry a default that is not on the control's own step grid (Portra 160
        // ships camera EV 0.30 against a 0.25 step). Both sides snap, so the two agree.
        val step = 0.25f
        val range = -5f..5f
        assertFalse(isModifiedFromDefault(value = 0.25f, default = 0.30f, range = range, step = step))
    }

    @Test
    fun aContinuousParameterComparesExactly() {
        // step = 0 means no grid; snap is then identity and only a real difference marks.
        assertFalse(isModifiedFromDefault(value = 0.123456f, default = 0.123456f, range = unit, step = 0f))
        assertTrue(isModifiedFromDefault(value = 0.123457f, default = 0.123456f, range = unit, step = 0f))
    }

    @Test
    fun aValueOutsideTheRangeIsJudgedAfterClamping() {
        // snap clamps, so a value that can only reach the endpoint is not "changed" from a
        // default that also clamps there.
        assertFalse(isModifiedFromDefault(value = 5f, default = 9f, range = unit, step = 0.1f))
    }
}
