/*
 * Spektrafilm for Android — mouse-wheel / trackpad zoom math tests. GPLv3.
 */
package com.spectrafilm.app

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelZoomTest {

    private val eps = 1e-3f

    @Test
    fun wheelUpZoomsInAndWheelDownZoomsOut() {
        val (inScale, _) = wheelZoomTransform(1f, Offset.Zero, Offset.Zero, Offset(0f, -1f), 10f)
        assertEquals(1.25f, inScale, eps)
        val (outScale, _) = wheelZoomTransform(2f, Offset.Zero, Offset.Zero, Offset(0f, 1f), 10f)
        assertEquals(1.6f, outScale, eps)
    }

    @Test
    fun zoomIsClampedAndFitResetsThePan() {
        val (minScale, minOffset) = wheelZoomTransform(1.1f, Offset(40f, 40f), Offset.Zero, Offset(0f, 5f), 10f)
        assertEquals(1f, minScale, eps)
        assertEquals(Offset.Zero, minOffset)
        val (maxScale, _) = wheelZoomTransform(7.9f, Offset.Zero, Offset.Zero, Offset(0f, -5f), 10f)
        assertEquals(8f, maxScale, eps)
    }

    @Test
    fun theImagePointUnderThePointerStaysPut() {
        // Content point under the pointer: (pointer - offset) / scale, relative to centre.
        val scale = 2f
        val offset = Offset(30f, -20f)
        val pointer = Offset(100f, 50f)
        val before = (pointer - offset) / scale
        val (ns, no) = wheelZoomTransform(scale, offset, pointer, Offset(0f, -1f), 10f)
        val after = (pointer - no) / ns
        assertEquals(before.x, after.x, eps)
        assertEquals(before.y, after.y, eps)
    }

    @Test
    fun horizontalScrollPansOnlyWhileZoomed() {
        val (s, o) = wheelZoomTransform(2f, Offset.Zero, Offset.Zero, Offset(1f, 0f), 10f)
        assertEquals(2f, s, eps)
        assertEquals(-10f, o.x, eps)
        val (fitScale, fitOffset) = wheelZoomTransform(1f, Offset.Zero, Offset.Zero, Offset(1f, 0f), 10f)
        assertEquals(1f, fitScale, eps)
        assertTrue(fitOffset == Offset.Zero)
    }

    @Test
    fun keyboardZoomStepsLikeOneWheelNotchAboutTheCentre() {
        val (inScale, inOffset) = keyboardZoomTransform(2f, Offset(10f, 0f), ViewerZoom.IN)
        assertEquals(2.5f, inScale, eps)
        assertEquals(12.5f, inOffset.x, eps) // offset scales with zoom about the centre
        val (outScale, _) = keyboardZoomTransform(2.5f, Offset.Zero, ViewerZoom.OUT)
        assertEquals(2f, outScale, eps)
        val (fitScale, fitOffset) = keyboardZoomTransform(5f, Offset(40f, 40f), ViewerZoom.FIT)
        assertEquals(1f, fitScale, eps)
        assertEquals(Offset.Zero, fitOffset)
    }
}
