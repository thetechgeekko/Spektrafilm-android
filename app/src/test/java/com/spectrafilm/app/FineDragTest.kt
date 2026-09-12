package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fine-adjust drag on a slider's value pill.
 *
 * A slider track spans the whole parameter range in about 320dp, so one finger resolves
 * roughly 1/300 of that range at best — a fingertip cannot be placed more precisely than the
 * 48dp target Material specifies because of it. Several parameters here need finer than that.
 * The pill drag exists to provide it, and these pin the two properties that make it useful:
 * a defined sensitivity, and accumulation that does not round sub-step motion away.
 */
class FineDragTest {

    private val trackPx = 320f * 3f // 320dp at a 3.0x density, the test device's scale

    @Test
    fun oneTrackWidthMovesExactlyATenthOfTheRange() {
        val range = 0f..100f
        val moved = fineDragAdvance(0f, trackPx, 100f, trackPx, range)
        assertEquals(100f / FINE_DRAG_DIVISOR, moved, 1e-4f)
    }

    @Test
    fun itIsTenTimesFinerThanTheTrackItself() {
        // The contract that makes it worth having: the same travel that would sweep the whole
        // parameter on the track moves a tenth of it here.
        val range = 0f..1f
        val onTrack = 1f
        val onPill = fineDragAdvance(0f, trackPx, 1f, trackPx, range)
        assertEquals(onTrack / 10f, onPill, 1e-5f)
    }

    @Test
    fun draggingBackwardsSubtracts() {
        val range = -1f..1f
        val moved = fineDragAdvance(0f, -trackPx, 2f, trackPx, range)
        assertEquals(-0.2f, moved, 1e-4f)
    }

    @Test
    fun subStepMotionAccumulatesInsteadOfBeingRoundedAway() {
        // 40 tiny events that would each snap back to 0 if the value were re-derived from the
        // snapped result every frame. Accumulating in value space is what makes the drag work
        // at all on a stepped parameter.
        val range = 0f..100f
        var v = 0f
        repeat(40) { v = fineDragAdvance(v, 4f, 100f, trackPx, range) }
        val expected = 40f * 4f * (100f / FINE_DRAG_DIVISOR / trackPx)
        assertEquals(expected, v, 1e-3f)
        assertTrue("40 small drags must move the value off zero", v > 0f)
    }

    @Test
    fun itCannotLeaveTheRange() {
        val range = 0.5f..2f
        assertEquals(2f, fineDragAdvance(1f, trackPx * 100f, 1.5f, trackPx, range), 1e-5f)
        assertEquals(0.5f, fineDragAdvance(1f, -trackPx * 100f, 1.5f, trackPx, range), 1e-5f)
    }

    @Test
    fun degenerateInputsAreInertRatherThanExplosive() {
        val range = 0f..1f
        // A zero span or an unmeasured track must not divide by zero or produce NaN.
        assertEquals(0.4f, fineDragAdvance(0.4f, 500f, 0f, trackPx, range), 1e-6f)
        assertEquals(0.4f, fineDragAdvance(0.4f, 500f, 1f, 0f, range), 1e-6f)
    }

    @Test
    fun aValueStartingOutsideTheRangeIsBroughtBackIn() {
        val range = 0f..1f
        assertEquals(1f, fineDragAdvance(5f, 0f, 0f, trackPx, range), 1e-6f)
    }
}
