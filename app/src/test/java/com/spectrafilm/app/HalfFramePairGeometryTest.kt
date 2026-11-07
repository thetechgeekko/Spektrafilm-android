/*
 * Spektrafilm for Android -- half-frame pair layout. GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfFramePairGeometryTest {
    @Test fun uprightFramesStandSideBySideOnAThreeByFourGate() {
        val l = HalfFramePairGeometry.layout(3000, 4000, 3000, 4000)
        assertFalse(l.stacked)
        assertEquals(BorderRect(0, 0, 3000, 4000), l.first)
        // A frame line of about 1 mm on a 24 mm frame: 4000 / 24 = 167 px.
        assertEquals(BorderRect(3167, 0, 6167, 4000), l.second)
        assertEquals(6167, l.width)
        assertEquals(4000, l.height)
    }

    @Test fun twoLevelFramesStackOneAboveTheOther() {
        val l = HalfFramePairGeometry.layout(4000, 3000, 4000, 3000)
        assertTrue(l.stacked)
        assertEquals(BorderRect(0, 0, 4000, 3000), l.first)
        assertEquals(BorderRect(0, 3167, 4000, 6167), l.second)
        assertEquals(4000, l.width)
        assertEquals(6167, l.height)
    }

    @Test fun aLevelFrameBesideAnUprightOneIsCutUprightAtTheCenter() {
        val l = HalfFramePairGeometry.layout(6000, 4000, 3000, 4000)
        assertFalse(l.stacked)
        // 6000 x 4000 cut to 3:4 keeps the full height, 3000 wide, centered.
        assertEquals(BorderRect(1500, 0, 4500, 4000), l.firstSource)
        assertEquals(BorderRect(0, 0, 3000, 4000), l.secondSource)
    }

    @Test fun theSmallerFrameSetsTheSizeSoNeitherIsEnlarged() {
        val l = HalfFramePairGeometry.layout(3000, 4000, 1500, 2000)
        assertEquals(2000, l.height)
        assertEquals(1500, l.first.width)
        assertEquals(1500, l.second.width)
        assertEquals(l.first.height, l.second.height)
    }

    @Test fun cutsAreTheLargestCenteredGateAndFramesNeverOverlap() {
        val sizes = listOf(1 to 1, 7 to 3, 3 to 7, 4000 to 3000, 3001 to 4003, 12000 to 500)
        for ((aw, ah) in sizes) for ((bw, bh) in sizes) {
            val l = HalfFramePairGeometry.layout(aw, ah, bw, bh)
            for ((cut, w, h) in listOf(Triple(l.firstSource, aw, ah), Triple(l.secondSource, bw, bh))) {
                assertTrue(cut.left >= 0 && cut.top >= 0 && cut.right <= w && cut.bottom <= h)
                assertTrue(cut.width >= 1 && cut.height >= 1)
                assertTrue(cut.width == w || cut.height == h)
            }
            assertTrue(l.first.width == l.second.width && l.first.height == l.second.height)
            if (l.stacked) assertTrue(l.second.top > l.first.bottom) else assertTrue(l.second.left > l.first.right)
            assertEquals(l.width, maxOf(l.first.right, l.second.right))
            assertEquals(l.height, maxOf(l.first.bottom, l.second.bottom))
        }
    }
}
