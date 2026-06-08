/*
 * Spektrafilm for Android — unit tests for the mask data model + rasterization. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The masking keystone is built foundation-first: these guard the pure geometry (linear/radial
 * shapes), the fold identities (union/intersect/subtract), invert/opacity, and the
 * resolution-independence that lets one normalized mask drive draft → ROI → export.
 */
package com.spectrafilm.app.masks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskTest {

    private fun radial(cx: Float, cy: Float, r: Float, feather: Float = 0.5f) =
        Mask(listOf(Mask.Component(BlendMode.ADD, MaskComponent.Radial(cx, cy, r, r, feather))))

    @Test
    fun radial_fullAtCenter_zeroOutside() {
        val m = radial(0.5f, 0.5f, 0.3f)
        assertEquals(1f, m.alphaAt(0.5f, 0.5f), 1e-4f)   // dead center
        assertEquals(0f, m.alphaAt(0.0f, 0.0f), 1e-4f)   // far corner (well outside)
        assertEquals(0f, m.alphaAt(0.95f, 0.5f), 1e-4f)  // outside the radius
    }

    @Test
    fun radial_fallsOffMonotonically() {
        val m = radial(0.5f, 0.5f, 0.4f, feather = 0.6f)
        var prev = 1.01f
        var d = 0f
        while (d <= 0.4f) {
            val a = m.alphaAt(0.5f + d, 0.5f)
            assertTrue("non-increasing alpha at d=$d", a <= prev + 1e-5f)
            prev = a
            d += 0.02f
        }
    }

    @Test
    fun linear_rampsAlongAxis_flatPerpendicular() {
        // gradient from x=0.2 to x=0.8 across the frame
        val lin = MaskComponent.Linear(0.2f, 0.5f, 0.8f, 0.5f)
        val m = Mask(listOf(Mask.Component(BlendMode.ADD, lin)))
        assertEquals(0f, m.alphaAt(0.1f, 0.5f), 1e-4f)   // before start → 0
        assertEquals(1f, m.alphaAt(0.9f, 0.5f), 1e-4f)   // past end → 1
        assertEquals(0.5f, m.alphaAt(0.5f, 0.5f), 1e-3f) // midpoint → 0.5 (smoothstep(0.5)=0.5)
        // invariant perpendicular to the axis (same x, different y)
        assertEquals(m.alphaAt(0.5f, 0.1f), m.alphaAt(0.5f, 0.9f), 1e-5f)
        // monotonic increasing along the axis
        assertTrue(m.alphaAt(0.4f, 0.5f) < m.alphaAt(0.6f, 0.5f))
    }

    @Test
    fun fold_intersect_isProduct() {
        val a = MaskComponent.Radial(0.4f, 0.5f, 0.3f, 0.3f, 1f)
        val b = MaskComponent.Radial(0.6f, 0.5f, 0.3f, 0.3f, 1f)
        val union = Mask(listOf(Mask.Component(BlendMode.ADD, a), Mask.Component(BlendMode.ADD, b)))
        val inter = Mask(listOf(Mask.Component(BlendMode.ADD, a), Mask.Component(BlendMode.INTERSECT, b)))
        // at the overlap midpoint both contribute, so union >= either >= intersect
        val p = 0.5f to 0.5f
        assertTrue(union.alphaAt(p.first, p.second) >= inter.alphaAt(p.first, p.second))
        // intersect outside b's reach is 0 even if inside a
        assertEquals(0f, inter.alphaAt(0.15f, 0.5f), 1e-3f)
    }

    @Test
    fun fold_subtract_carvesOut() {
        val base = MaskComponent.Radial(0.5f, 0.5f, 0.4f, 0.4f, 1f)
        val hole = MaskComponent.Radial(0.5f, 0.5f, 0.15f, 0.15f, 0.3f)
        val m = Mask(listOf(Mask.Component(BlendMode.ADD, base), Mask.Component(BlendMode.SUBTRACT, hole)))
        // center is carved out (subtracted), the ring between hole and base stays selected
        assertTrue("center carved", m.alphaAt(0.5f, 0.5f) < 0.2f)
        assertTrue("ring kept", m.alphaAt(0.5f + 0.2f, 0.5f) > 0.3f)
    }

    @Test
    fun invert_and_opacity() {
        val m = radial(0.5f, 0.5f, 0.3f)
        val inv = m.copy(invert = true)
        assertEquals(1f - m.alphaAt(0.3f, 0.3f), inv.alphaAt(0.3f, 0.3f), 1e-5f)
        val half = m.copy(opacity = 0.5f)
        assertEquals(0.5f * m.alphaAt(0.5f, 0.5f), half.alphaAt(0.5f, 0.5f), 1e-5f)
    }

    @Test
    fun emptyMask_selectsNothing_invertSelectsAll() {
        val empty = Mask()
        assertEquals(0f, empty.alphaAt(0.5f, 0.5f), 0f)
        val global = Mask(invert = true)            // the degenerate "global adjustment" case
        assertEquals(1f, global.alphaAt(0.123f, 0.456f), 0f)
        assertEquals(0.4f, Mask(invert = true, opacity = 0.4f).alphaAt(0.5f, 0.5f), 1e-5f)
    }

    @Test
    fun rasterize_dimsAndResolutionIndependence() {
        val m = radial(0.5f, 0.5f, 0.3f)
        val small = MaskRaster.rasterize(m, 10, 10)
        val big = MaskRaster.rasterize(m, 40, 40)
        assertEquals(100, small.size)
        assertEquals(1600, big.size)
        // center pixel alpha is ~the same regardless of resolution (normalized geometry)
        val centerSmall = small[5 * 10 + 5]
        val centerBig = big[20 * 40 + 20]
        assertEquals(centerSmall, centerBig, 0.05f)
        assertTrue("center selected", centerBig > 0.8f)
        // a corner pixel is unselected
        assertEquals(0f, big[0], 1e-4f)
    }

    @Test
    fun rasterize_emptyIsAllZero() {
        val r = MaskRaster.rasterize(Mask(), 8, 8)
        assertTrue(r.all { it == 0f })
    }

    @Test
    fun coverage_probe() {
        assertEquals(0f, MaskRaster.coverage(Mask()), 1e-6f)
        assertEquals(1f, MaskRaster.coverage(Mask(invert = true)), 1e-6f)
        val c = MaskRaster.coverage(radial(0.5f, 0.5f, 0.25f))
        assertTrue("partial coverage", c > 0.02f && c < 0.5f)
    }

    @Test
    fun tierADelta_noOpFlag() {
        assertTrue(TierADelta().isNoOp)
        assertTrue(!TierADelta(exposureEv = 0.5f).isNoOp)
        assertTrue(!TierADelta(saturation = -20f).isNoOp)
    }

    @Test
    fun blendModeOrdinals_pinnedToCrsIntValues() {
        // crs:MaskBlendMode serializes as 0=Add, 1=Subtract, 2=Intersect — DO NOT reorder.
        assertEquals(0, BlendMode.ADD.ordinal)
        assertEquals(1, BlendMode.SUBTRACT.ordinal)
        assertEquals(2, BlendMode.INTERSECT.ordinal)
    }

    @Test
    fun perComponentInvert_flipsThatComponent() {
        val shape = MaskComponent.Radial(0.5f, 0.5f, 0.3f, 0.3f, 0.5f)
        val plain = Mask(listOf(Mask.Component(BlendMode.ADD, shape)))
        val inv = Mask(listOf(Mask.Component(BlendMode.ADD, shape, invert = true)))
        // crs:MaskInverted is per-component (before the fold): inverted = 1 − plain.
        assertEquals(1f - plain.alphaAt(0.5f, 0.5f), inv.alphaAt(0.5f, 0.5f), 1e-5f)  // center: 1→0
        assertEquals(1f - plain.alphaAt(0.05f, 0.05f), inv.alphaAt(0.05f, 0.05f), 1e-5f) // corner: 0→1
    }

    @Test
    fun perComponentValue_scalesStrength() {
        val shape = MaskComponent.Radial(0.5f, 0.5f, 0.3f, 0.3f, 0.5f)
        val full = Mask(listOf(Mask.Component(BlendMode.ADD, shape)))
        val half = Mask(listOf(Mask.Component(BlendMode.ADD, shape, value = 0.5f)))  // crs:MaskValue
        assertEquals(0.5f * full.alphaAt(0.5f, 0.5f), half.alphaAt(0.5f, 0.5f), 1e-5f)
    }

    @Test
    fun radialAngle_rotatesTheEllipse() {
        // A wide ellipse (rx≫ry): a point above the center is OUTSIDE unrotated but INSIDE at 90°.
        val wide = MaskComponent.Radial(0.5f, 0.5f, 0.35f, 0.08f, 0.3f, angleDeg = 0f)
        val rot = MaskComponent.Radial(0.5f, 0.5f, 0.35f, 0.08f, 0.3f, angleDeg = 90f)
        val px = 0.5f; val py = 0.5f + 0.2f                 // 0.2 above center
        assertEquals("outside the wide axis", 0f, wide.alphaAt(px, py), 1e-3f)
        assertTrue("inside once rotated 90°", rot.alphaAt(px, py) > 0.5f)
        // angle is a no-op at 0° (existing behavior preserved)
        assertEquals(wide.alphaAt(0.5f, 0.5f), rot.alphaAt(0.5f, 0.5f), 1e-5f)  // center same
    }

    @Test
    fun luminanceRange_trapezoidGate() {
        val r = LuminanceRange(lumMin = 0.4f, lumMax = 0.8f, feather = 0.1f)
        assertTrue(r.isActive)
        assertEquals("mid-range full", 1f, r.gate(0.6f), 1e-4f)
        assertEquals("below range", 0f, r.gate(0.1f), 1e-3f)
        assertEquals("above range", 0f, r.gate(0.95f), 1e-3f)
        val edge = r.gate(0.35f)                                // in the lower feather band
        assertTrue("feathered edge", edge > 0f && edge < 1f)
        // invert flips the gate
        val inv = r.copy(invert = true)
        assertEquals(0f, inv.gate(0.6f), 1e-4f)
        assertEquals(1f, inv.gate(0.1f), 1e-3f)
        // full range is a no-op
        assertTrue(!LuminanceRange().isActive)
    }
}
