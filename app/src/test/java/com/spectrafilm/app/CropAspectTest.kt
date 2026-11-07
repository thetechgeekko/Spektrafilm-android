/*
 * Spektrafilm for Android — unit tests for the crop ratio presets. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropAspectTest {

    @Test
    fun fixedRatios_flipWithOrientation() {
        assertEquals(4f / 5f, CropAspect.R5_4.ratio(portrait = true)!!, 1e-6f)
        assertEquals(5f / 4f, CropAspect.R5_4.ratio(portrait = false)!!, 1e-6f)
        assertEquals(2f / 3f, CropAspect.R3_2.ratio(portrait = true)!!, 1e-6f)
        assertEquals(16f / 9f, CropAspect.R16_9.ratio(portrait = false)!!, 1e-6f)
        assertEquals(1f, CropAspect.SQUARE.ratio(portrait = true)!!, 0f)
    }

    @Test
    fun labels_readShortSideFirstInPortrait() {
        assertEquals("4:5", CropAspect.R5_4.label(portrait = true))
        assertEquals("5:4", CropAspect.R5_4.label(portrait = false))
        assertEquals("9:16", CropAspect.R16_9.label(portrait = true))
        assertEquals("2:3", CropAspect.R3_2.label(portrait = true))
    }

    @Test
    fun freeAndOriginal_haveNoFixedRatio() {
        assertNull(CropAspect.FREE.ratio(portrait = true))
        assertNull(CropAspect.ORIGINAL.ratio(portrait = false))
    }

    @Test
    fun everyRatioTheOldChipsOffered_isStillReachable() {
        // Old chips: 1:1 4:5 4:3 3:4 3:2 2:3 16:9 9:16.
        val reachable = CropAspect.entries.filter { it.isFixed }
            .flatMap { listOf(it.label(true), it.label(false)) }.toSet()
        listOf("1:1", "4:5", "4:3", "3:4", "3:2", "2:3", "16:9", "9:16").forEach {
            assertTrue("$it no longer reachable", it in reachable)
        }
    }

    @Test
    fun panoramicFilmGatesAreOffered() {
        assertEquals(65f / 24f, CropAspect.R65_24.ratio(portrait = false)!!, 1e-6f)
        assertEquals("24:65", CropAspect.R65_24.label(portrait = true))
        assertEquals(3f, CropAspect.R3_1.ratio(portrait = false)!!, 1e-6f)
        assertEquals("1:3", CropAspect.R3_1.label(portrait = true))
    }
}
