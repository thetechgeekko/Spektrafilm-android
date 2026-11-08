/*
 * Spektrafilm for Android — zoom patch coverage regressions. GPLv3.
 */
package com.spectrafilm.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zooming out quickly from full zoom left the last sharp patch up as a small box over the soft
 * preview until the next settle. The patch is now drawn only while it covers the whole view.
 */
class RoiOverlayCoverageTest {
    private val view = IntSize(1000, 1000)

    @Test
    fun aPatchRenderedForTheViewCoversIt() {
        val roi = viewportRoiNormalized(view, 4f, Offset.Zero, 1f)!!
        val patch = RoiPatch.rectOf(RoiPatch.box(roi, 4000, 4000), 4000, 4000)
        assertTrue(overlayCoversViewport(patch, roi))
    }

    @Test
    fun zoomingOutPastThePatchHidesIt() {
        val roi = viewportRoiNormalized(view, 8f, Offset.Zero, 1f)!!
        val patch = RoiPatch.rectOf(RoiPatch.box(roi, 4000, 4000), 4000, 4000)
        val zoomedOut = viewportRoiNormalized(view, 2f, Offset.Zero, 1f)
        assertFalse(overlayCoversViewport(patch, zoomedOut))
    }

    @Test
    fun panningOffThePatchHidesIt() {
        val roi = viewportRoiNormalized(view, 8f, Offset.Zero, 1f)!!
        val patch = RoiPatch.rectOf(RoiPatch.box(roi, 4000, 4000), 4000, 4000)
        val panned = viewportRoiNormalized(view, 8f, Offset(2000f, 0f), 1f)
        assertFalse(overlayCoversViewport(patch, panned))
    }

    @Test
    fun atFitNothingIsCovered() {
        assertFalse(overlayCoversViewport(RoiRect(0.5f, 0.5f, 1f, 1f), null))
    }
}
