/*
 * Spektrafilm for Android — grid-snapped zoom (ROI) patch. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiPatchTest {
    private val w = 2048
    private val h = 1536

    private fun viewport(x0: Float, y0: Float, vw: Float, vh: Float) =
        RoiRect((x0 + vw / 2f) / w, (y0 + vh / 2f) / h, vw / w, vh / h)

    @Test
    fun patchContainsViewportPlusMargin() {
        val roi = viewport(600f, 400f, 512f, 384f)
        val b = RoiPatch.box(roi, w, h)
        assertTrue(b.x0 <= 600 - 51)
        assertTrue(b.y0 <= 400 - 38)
        assertTrue(b.x0 + b.width >= 600 + 512 + 51)
        assertTrue(b.y0 + b.height >= 400 + 384 + 38)
        // ...but not wildly bigger: margin (10% a side) plus at most one grid step a side.
        assertTrue(b.width <= 512 * 1.2f + 2 * 512f / 8 + 2)
    }

    @Test
    fun aSmallPanGivesTheSamePatch() {
        val a = RoiPatch.box(viewport(600f, 400f, 512f, 384f), w, h)
        val b = RoiPatch.box(viewport(601.5f, 399.3f, 512f, 384f), w, h)
        assertEquals(a, b)
    }

    @Test
    fun patchIsClampedToTheFrame() {
        val b = RoiPatch.box(viewport(0f, 0f, 400f, 300f), w, h)
        assertEquals(0, b.x0)
        assertEquals(0, b.y0)
        val c = RoiPatch.box(viewport(w - 400f, h - 300f, 400f, 300f), w, h)
        assertEquals(w, c.x0 + c.width)
        assertEquals(h, c.y0 + c.height)
    }

    @Test
    fun tinyViewportStillGetsTheMinimumEdge() {
        val b = RoiPatch.box(viewport(100f, 100f, 1f, 1f), w, h)
        assertTrue(b.width >= RoiPatch.MIN_EDGE_PX)
        assertTrue(b.height >= RoiPatch.MIN_EDGE_PX)
    }

    @Test
    fun rectOfRoundTripsTheBox() {
        val box = CropBox(100, 200, 400, 300)
        val r = RoiPatch.rectOf(box, w, h)
        assertEquals(300f / w, r.cxN, 1e-6f)
        assertEquals(350f / h, r.cyN, 1e-6f)
        assertEquals(400f / w, r.wN, 1e-6f)
        assertEquals(300f / h, r.hN, 1e-6f)
    }

    @Test
    fun neededScaleCapsAtOneAndShrinksWideViews() {
        assertEquals(1f, RoiPatch.neededScale(viewport(0f, 0f, 800f, 600f), w, h, 1600), 0f)
        assertEquals(0.8f, RoiPatch.neededScale(viewport(0f, 0f, 2000f, 1500f), w, h, 1600), 1e-6f)
    }

    @Test
    fun quantizedStepIsAQuarterOctavePower() {
        assertEquals(1f, RoiPatch.quantizedStep(0.3f), 0f)
        assertEquals(64f, RoiPatch.quantizedStep(64f), 1e-3f)
        // 70 rounds down to 2^(6.0) = 64 (next step up is 2^6.25 = 76.1).
        assertEquals(64f, RoiPatch.quantizedStep(70f), 1e-3f)
    }

    private fun record(roi: RoiRect, key: Int = 7) = RoiPatchRecord(
        renderKey = key,
        srcW = w,
        srcH = h,
        box = RoiPatch.box(roi, w, h),
        scale = RoiPatch.neededScale(roi, w, h, 1600),
    )

    @Test
    fun recordCoversTheSameViewAndASmallPan() {
        val roi = viewport(600f, 400f, 512f, 384f)
        val r = record(roi)
        assertTrue(r.covers(roi, 7, 1600))
        assertTrue(r.covers(viewport(620f, 410f, 512f, 384f), 7, 1600))
    }

    @Test
    fun recordDoesNotCoverAnotherEditOrALargePan() {
        val roi = viewport(600f, 400f, 512f, 384f)
        val r = record(roi)
        assertFalse(r.covers(roi, 8, 1600))
        assertFalse(r.covers(viewport(900f, 400f, 512f, 384f), 7, 1600))
    }

    @Test
    fun recordDoesNotCoverAZoomInThatNeedsMoreDetail() {
        // Rendered at scale 0.8 for a wide view; zooming to a 512px view needs scale 1.
        val wide = viewport(0f, 0f, 2000f, 1500f)
        val r = record(wide)
        assertFalse(r.covers(viewport(700f, 500f, 512f, 384f), 7, 1600))
    }

    @Test
    fun recordAtTheFrameEdgeCoversAViewTouchingThatEdge() {
        val roi = viewport(0f, 0f, 400f, 300f)
        assertTrue(record(roi).covers(roi, 7, 1600))
    }

    @Test
    fun trackerReusesOnlyTheLiveOverlay() {
        val roi = viewport(600f, 400f, 512f, 384f)
        val t = RoiPatchTracker()
        val overlay = Any()
        t.shownOverlay = overlay
        t.shown = record(roi)
        assertEquals("shown", t.reuseReason(roi, 7, overlay, null, 1600))
        // Overlay cleared or replaced elsewhere: no reuse.
        assertEquals(null, t.reuseReason(roi, 7, null, null, 1600))
        assertEquals(null, t.reuseReason(roi, 7, Any(), null, 1600))
    }
}
