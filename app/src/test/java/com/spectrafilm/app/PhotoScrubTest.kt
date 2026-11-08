package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** A sideways drag on the photo, read as a long slider over the last control. */
class PhotoScrubTest {
    @Test
    fun theFullWidthSweepsTheWholeRange() {
        assertEquals(10f, scrubValue(-10f, 1000f, 1000f, -10f..10f, 0f), 1e-4f)
        assertEquals(0f, scrubValue(0f, 0f, 1000f, -10f..10f, 0f), 1e-4f)
        assertEquals(5f, scrubValue(0f, 250f, 1000f, -10f..10f, 0f), 1e-4f)
        assertEquals(-5f, scrubValue(0f, -250f, 1000f, -10f..10f, 0f), 1e-4f)
    }

    @Test
    fun itSnapsToTheStepAndClamps() {
        // 0.3 of the way from 0 is 6.0 on a 20-wide range; a quarter-stop step keeps it there,
        // and 6.1 would snap down to 6.0.
        assertEquals(6f, scrubValue(0f, 305f, 1000f, -10f..10f, 0.25f), 1e-4f)
        assertEquals(10f, scrubValue(8f, 5000f, 1000f, -10f..10f, 0.25f), 1e-4f)
        assertEquals(-10f, scrubValue(-8f, -5000f, 1000f, -10f..10f, 0.25f), 1e-4f)
    }

    @Test
    fun aZeroWidthPhotoLeavesTheValueAlone() {
        assertEquals(3f, scrubValue(3f, 400f, 0f, -10f..10f, 0f), 1e-4f)
    }
}
