/*
 * Spektrafilm for Android — tests for slider numeric-entry parsing. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Covers parseSliderInput (Widgets.kt): the pure parse/clamp/snap behind the
 * Lightroom-style "tap the number to type a value" affordance on EnhancedSlider.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliderInputTest {

    private val r = 0f..10f
    private val rNeg = -100f..100f

    @Test
    fun parsesPlainDecimal() {
        assertEquals(1.5f, parseSliderInput("1.5", r, 0f)!!, 1e-6f)
    }

    @Test
    fun toleratesWhitespacePlusAndComma() {
        assertEquals(3f, parseSliderInput("  +3 ", r, 0f)!!, 1e-6f)
        assertEquals(2.5f, parseSliderInput("2,5", r, 0f)!!, 1e-6f)
    }

    @Test
    fun acceptsNegativeWithinRange() {
        assertEquals(-4f, parseSliderInput("-4", rNeg, 0f)!!, 1e-6f)
    }

    @Test
    fun clampsToRange() {
        assertEquals(10f, parseSliderInput("99", r, 0f)!!, 1e-6f)
        assertEquals(0f, parseSliderInput("-99", r, 0f)!!, 1e-6f)
    }

    @Test
    fun snapsToStep() {
        assertEquals(2f, parseSliderInput("1.6", r, 1f)!!, 1e-6f)
        assertEquals(1f, parseSliderInput("1.2", r, 1f)!!, 1e-6f)
    }

    @Test
    fun rejectsNonNumbers() {
        assertNull(parseSliderInput("", r, 0f))
        assertNull(parseSliderInput("abc", r, 0f))
        assertNull(parseSliderInput("NaN", r, 0f))
        assertNull(parseSliderInput("Infinity", r, 0f))
    }
}
