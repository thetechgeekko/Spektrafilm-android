/*
 * Spektrafilm for Android — mask-geometry gesture math. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The pure, JVM-testable core of the draw-on-the-preview mask overlay: hit-test which handle a press
 * grabbed, and turn a drag into updated normalized geometry. No Compose / Android types here (the
 * composable passes plain pixels + normalized deltas), so the error-prone coordinate logic is unit
 * tested without a device — only the rendering + gesture wiring in MaskGeometryOverlay need the screen.
 *
 * Geometry stays NORMALIZED (0..1), exactly as [MaskComponent] stores it, so one edit drives the draft,
 * the zoom ROI and the export identically (resolution-independent), like the rest of the mask model.
 */
package com.spectrafilm.app.masks

import kotlin.math.hypot

object MaskGesture {

    /** Which part of a shape a press grabbed. RX/RY = the radial's right/bottom radius handles. */
    enum class Handle { NONE, MOVE, RX, RY, P0, P1 }

    /** Min radius / the handle hit radius as a fraction of the shorter canvas side. */
    private const val MIN_RADIUS = 0.02f
    private const val HIT_FRAC = 0.06f

    /**
     * Hit-test which [Handle] a press at pixel ([px],[py]) in a [canvasW]×[canvasH] box grabbed, for
     * [shape]. A handle wins if the press is within the hit radius; otherwise MOVE (drag the whole
     * shape). The radial's handles sit at its right (cx+rx, cy) and bottom (cx, cy+ry) edges; the
     * linear's at its two endpoints. (Radial [MaskComponent.Radial.angleDeg] is treated as 0 in v1.)
     */
    fun pick(shape: MaskComponent, px: Float, py: Float, canvasW: Int, canvasH: Int): Handle {
        if (canvasW <= 0 || canvasH <= 0) return Handle.MOVE
        val w = canvasW.toFloat(); val h = canvasH.toFloat()
        val tol = HIT_FRAC * minOf(w, h)
        fun near(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by) <= tol
        return when (shape) {
            is MaskComponent.Radial -> when {
                near(px, py, (shape.cx + shape.rx) * w, shape.cy * h) -> Handle.RX
                near(px, py, shape.cx * w, (shape.cy + shape.ry) * h) -> Handle.RY
                else -> Handle.MOVE
            }
            is MaskComponent.Linear -> when {
                near(px, py, shape.x0 * w, shape.y0 * h) -> Handle.P0
                near(px, py, shape.x1 * w, shape.y1 * h) -> Handle.P1
                else -> Handle.MOVE
            }
        }
    }

    /**
     * Apply a normalized drag ([dx],[dy] in 0..1 of the canvas) for the active [handle], returning the
     * updated [shape]. MOVE translates the whole shape; RX/RY resize the radial (clamped to [MIN_RADIUS]);
     * P0/P1 move a linear endpoint. All positions clamped to [0,1].
     */
    fun applyDrag(shape: MaskComponent, handle: Handle, dx: Float, dy: Float): MaskComponent = when (shape) {
        is MaskComponent.Radial -> when (handle) {
            Handle.MOVE -> shape.copy(cx = c01(shape.cx + dx), cy = c01(shape.cy + dy))
            Handle.RX -> shape.copy(rx = (shape.rx + dx).coerceIn(MIN_RADIUS, 1f))
            Handle.RY -> shape.copy(ry = (shape.ry + dy).coerceIn(MIN_RADIUS, 1f))
            else -> shape
        }
        is MaskComponent.Linear -> when (handle) {
            Handle.P0 -> shape.copy(x0 = c01(shape.x0 + dx), y0 = c01(shape.y0 + dy))
            Handle.P1 -> shape.copy(x1 = c01(shape.x1 + dx), y1 = c01(shape.y1 + dy))
            Handle.MOVE -> shape.copy(
                x0 = c01(shape.x0 + dx), y0 = c01(shape.y0 + dy),
                x1 = c01(shape.x1 + dx), y1 = c01(shape.y1 + dy),
            )
            else -> shape
        }
    }

    private fun c01(v: Float) = v.coerceIn(0f, 1f)
}
