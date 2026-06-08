/*
 * Spektrafilm for Android — unit tests for the mask-geometry gesture math. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The error-prone part of the draw-on-the-preview overlay is the coordinate hit-testing + drag math;
 * these pin it down without a device (the composable only renders + forwards gestures to this).
 */
package com.spectrafilm.app.masks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskGestureTest {

    private val radial = MaskComponent.Radial(0.5f, 0.5f, 0.3f, 0.3f, 0.5f)
    private val linear = MaskComponent.Linear(0.2f, 0.5f, 0.8f, 0.5f)

    @Test
    fun pick_radial_handlesVsMove() {
        // 100x100 canvas: center (50,50), RX handle (80,50), RY handle (50,80).
        assertEquals(MaskGesture.Handle.RX, MaskGesture.pick(radial, 80f, 50f, 100, 100))
        assertEquals(MaskGesture.Handle.RY, MaskGesture.pick(radial, 50f, 80f, 100, 100))
        assertEquals(MaskGesture.Handle.MOVE, MaskGesture.pick(radial, 50f, 50f, 100, 100))  // center → move
        assertEquals(MaskGesture.Handle.MOVE, MaskGesture.pick(radial, 5f, 5f, 100, 100))     // far → move
    }

    @Test
    fun pick_linear_endpointsVsMove() {
        // p0 (20,50), p1 (80,50).
        assertEquals(MaskGesture.Handle.P0, MaskGesture.pick(linear, 20f, 50f, 100, 100))
        assertEquals(MaskGesture.Handle.P1, MaskGesture.pick(linear, 80f, 50f, 100, 100))
        assertEquals(MaskGesture.Handle.MOVE, MaskGesture.pick(linear, 50f, 50f, 100, 100))   // midpoint → move
    }

    @Test
    fun pick_degenerateCanvas_isMove() {
        assertEquals(MaskGesture.Handle.MOVE, MaskGesture.pick(radial, 0f, 0f, 0, 0))
    }

    @Test
    fun applyDrag_radial_moveResizeClamp() {
        val moved = MaskGesture.applyDrag(radial, MaskGesture.Handle.MOVE, 0.1f, -0.1f) as MaskComponent.Radial
        assertEquals(0.6f, moved.cx, 1e-5f); assertEquals(0.4f, moved.cy, 1e-5f)

        val widerX = MaskGesture.applyDrag(radial, MaskGesture.Handle.RX, 0.1f, 0f) as MaskComponent.Radial
        assertEquals(0.4f, widerX.rx, 1e-5f); assertEquals(0.3f, widerX.ry, 1e-5f)  // ry untouched

        val tallerY = MaskGesture.applyDrag(radial, MaskGesture.Handle.RY, 0f, 0.1f) as MaskComponent.Radial
        assertEquals(0.4f, tallerY.ry, 1e-5f)

        // resize clamps to the minimum radius; move clamps the center into [0,1]
        val tiny = MaskGesture.applyDrag(radial, MaskGesture.Handle.RX, -1f, 0f) as MaskComponent.Radial
        assertEquals(0.02f, tiny.rx, 1e-5f)
        val pinned = MaskGesture.applyDrag(radial, MaskGesture.Handle.MOVE, 0.9f, 0f) as MaskComponent.Radial
        assertEquals(1f, pinned.cx, 1e-5f)
    }

    @Test
    fun applyDrag_linear_endpointsAndTranslate() {
        val p0 = MaskGesture.applyDrag(linear, MaskGesture.Handle.P0, 0.1f, 0.1f) as MaskComponent.Linear
        assertEquals(0.3f, p0.x0, 1e-5f); assertEquals(0.6f, p0.y0, 1e-5f)
        assertEquals(0.8f, p0.x1, 1e-5f); assertEquals(0.5f, p0.y1, 1e-5f)   // p1 untouched

        val p1 = MaskGesture.applyDrag(linear, MaskGesture.Handle.P1, -0.1f, 0f) as MaskComponent.Linear
        assertEquals(0.7f, p1.x1, 1e-5f); assertEquals(0.2f, p1.x0, 1e-5f)   // p0 untouched

        // MOVE translates both endpoints together
        val moved = MaskGesture.applyDrag(linear, MaskGesture.Handle.MOVE, 0.05f, 0.1f) as MaskComponent.Linear
        assertEquals(0.25f, moved.x0, 1e-5f); assertEquals(0.6f, moved.y0, 1e-5f)
        assertEquals(0.85f, moved.x1, 1e-5f); assertEquals(0.6f, moved.y1, 1e-5f)
    }

    @Test
    fun applyDrag_noneHandle_isNoOp() {
        assertTrue(MaskGesture.applyDrag(radial, MaskGesture.Handle.NONE, 0.2f, 0.2f) === radial)
    }
}
