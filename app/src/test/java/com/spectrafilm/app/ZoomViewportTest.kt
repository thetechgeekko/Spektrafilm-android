/*
 * Spektrafilm for Android — unit tests for the Lightroom-zoom viewport math. GPLv3.
 *
 * The ROI overlay only registers against the proxy if the forward (image->view) and inverse
 * (view->image) transforms are exact inverses, and if the visible-region computation matches
 * the graphicsLayer transform. These are pure functions (no Android framework), so they run on
 * the plain JVM under :app:testDebugUnitTest.
 */
package com.spectrafilm.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomViewportTest {
    private val view = IntSize(1000, 1000)
    private val aspect = 1f // square image in square view → no letterbox

    @Test fun notZoomed_returnsNull() {
        assertNull(viewportRoiNormalized(view, 1f, Offset.Zero, aspect))
        // Just past fit but within the dead-zone is still treated as "not zoomed".
        assertNull(viewportRoiNormalized(view, 1.005f, Offset.Zero, aspect))
    }

    @Test fun zeroViewSize_returnsNull() {
        assertNull(viewportRoiNormalized(IntSize.Zero, 4f, Offset.Zero, aspect))
    }

    @Test fun zoom2x_centered_isHalfSizeCentered() {
        val roi = viewportRoiNormalized(view, 2f, Offset.Zero, aspect)
        assertNotNull(roi)
        roi!!
        assertEquals(0.5f, roi.cxN, 1e-4f)
        assertEquals(0.5f, roi.cyN, 1e-4f)
        assertEquals(0.5f, roi.wN, 1e-3f)
        assertEquals(0.5f, roi.hN, 1e-3f)
    }

    @Test fun zoom4x_centered_isQuarterSize() {
        val roi = viewportRoiNormalized(view, 4f, Offset.Zero, aspect)
        assertNotNull(roi)
        assertEquals(0.25f, roi!!.wN, 1e-3f)
        assertEquals(0.25f, roi.hN, 1e-3f)
    }

    @Test fun forwardInverse_roundTrip() {
        val scale = 3f
        val offset = Offset(40f, -25f)
        for (nx in listOf(0.1f, 0.5f, 0.9f)) {
            for (ny in listOf(0.2f, 0.5f, 0.8f)) {
                val v = imageNormToView(nx, ny, view, scale, offset, aspect)
                val back = mapViewToImageNorm(v, view, scale, offset, aspect)
                assertEquals("nx round-trip", nx, back.x, 1e-3f)
                assertEquals("ny round-trip", ny, back.y, 1e-3f)
            }
        }
    }

    @Test fun panRight_revealsLeftOfImage() {
        // graphicsLayer translationX > 0 moves content right → the viewport sees the LEFT part
        // of the image, so the ROI centre moves left of 0.5.
        val roi = viewportRoiNormalized(view, 2f, Offset(200f, 0f), aspect)
        assertNotNull(roi)
        assertTrue("centre should shift left under positive pan", roi!!.cxN < 0.5f)
    }

    @Test fun letterboxedWideImage_roiStaysInBounds() {
        // A 2:1 image in a square view is letterboxed top/bottom; the ROI must still clamp to
        // [0,1] and have positive extent.
        val wide = 2f
        val roi = viewportRoiNormalized(view, 3f, Offset(0f, 0f), wide)
        assertNotNull(roi)
        roi!!
        assertTrue(roi.cxN in 0f..1f && roi.cyN in 0f..1f)
        assertTrue(roi.wN > 0f && roi.wN <= 1f)
        assertTrue(roi.hN > 0f && roi.hN <= 1f)
    }
}
