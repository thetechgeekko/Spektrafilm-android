/*
 * Spektrafilm for Android — unit tests for the Expressive slider track maths. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderTrackTest {
    @Test
    fun anchorIsCentredDefaultWhenStrictlyInsideRange() {
        assertEquals(0f, sliderAnchor(0f, -1f..1f), 0f)
        assertEquals(6500f, sliderAnchor(6500f, 2000f..12000f), 0f)
    }

    @Test
    fun anchorFallsBackToStartWhenDefaultIsAtEdgeOrMissing() {
        assertEquals(0f, sliderAnchor(0f, 0f..1f), 0f)
        assertEquals(0f, sliderAnchor(1f, 0f..1f), 0f)
        assertEquals(-2f, sliderAnchor(null, -2f..2f), 0f)
        assertEquals(0f, sliderAnchor(5f, 0f..1f), 0f)
    }

    @Test
    fun fractionMapsAndClamps() {
        assertEquals(0.5f, sliderFraction(0f, -1f..1f), 1e-6f)
        assertEquals(0f, sliderFraction(-3f, -1f..1f), 0f)
        assertEquals(1f, sliderFraction(3f, -1f..1f), 0f)
    }

    @Test
    fun fractionOfEmptyRangeIsZero() {
        assertEquals(0f, sliderFraction(1f, 1f..1f), 0f)
    }
}
