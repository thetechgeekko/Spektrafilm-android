package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The slider detent (interaction rule: sticky default with a tick, ticks at the ends). */
class SliderDetentTest {
    private val range = -2f..2f // width 4: the detent is +-0.06

    @Test
    fun valuesNearTheDefaultStickToIt() {
        assertEquals(0f, detent(0.05f, 0f, range))
        assertEquals(0f, detent(-0.055f, 0f, range))
        assertEquals(0.07f, detent(0.07f, 0f, range))
    }

    @Test
    fun noDefaultOrAnOutOfRangeDefaultMeansNoDetent() {
        assertEquals(0.01f, detent(0.01f, null, range))
        assertEquals(1.99f, detent(1.99f, 5f, range))
    }

    @Test
    fun zonesAreDefaultEndOrFree() {
        assertEquals(SliderZone.DEFAULT, detentZone(0f, 0f, range))
        assertEquals(SliderZone.END, detentZone(-2f, 0f, range))
        assertEquals(SliderZone.END, detentZone(2f, 0f, range))
        assertEquals(SliderZone.FREE, detentZone(1f, 0f, range))
        // A default that sits at an end is reported as the default.
        assertEquals(SliderZone.DEFAULT, detentZone(-2f, -2f, range))
    }
}
