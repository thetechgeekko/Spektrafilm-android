/*
 * Spektrafilm for Android — unit tests for the local-adjustment compositor. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Proves the masking SEAM end-to-end: a radial mask + an exposure delta brightens inside the mask and
 * leaves the outside untouched, blends by alpha, and is a strict no-op when nothing is active.
 */
package com.spectrafilm.app.masks

import com.spectrafilm.engine.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskCompositorTest {

    private val W = 8
    private val H = 8

    /** A flat mid-gray [W]×[H] RGB buffer (all channels = [v]). */
    private fun grayBuf(v: Float = 0.5f): ByteBuffer {
        val b = ByteBuffer.allocateDirect(W * H * 3 * 4).order(ByteOrder.nativeOrder())
        val f = b.asFloatBuffer()
        for (i in 0 until W * H * 3) f.put(i, v)
        return b
    }

    private fun px(b: ByteBuffer, x: Int, y: Int): Float =
        b.order(ByteOrder.nativeOrder()).asFloatBuffer().get((y * W + x) * 3)

    private fun chan(b: ByteBuffer, x: Int, y: Int, c: Int): Float =
        b.order(ByteOrder.nativeOrder()).asFloatBuffer().get((y * W + x) * 3 + c)

    /** A flat [W]×[H] buffer with a single (possibly colored) RGB value. */
    private fun colorBuf(r: Float, g: Float, bl: Float): ByteBuffer {
        val b = ByteBuffer.allocateDirect(W * H * 3 * 4).order(ByteOrder.nativeOrder())
        val f = b.asFloatBuffer()
        var i = 0
        while (i < W * H * 3) { f.put(i, r); f.put(i + 1, g); f.put(i + 2, bl); i += 3 }
        return b
    }

    private fun spreadAt(b: ByteBuffer, x: Int, y: Int): Float {
        val f = b.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val k = (y * W + x) * 3
        val r = f.get(k); val g = f.get(k + 1); val bl = f.get(k + 2)
        return maxOf(r, maxOf(g, bl)) - minOf(r, minOf(g, bl))
    }

    private fun radialAdj(ev: Float) = radialAdj(TierADelta(exposureEv = ev))

    private fun radialAdj(delta: TierADelta) = LocalAdjustment(
        Mask(listOf(Mask.Component(BlendMode.ADD, MaskComponent.Radial(0.5f, 0.5f, 0.3f, 0.3f, 0.5f)))),
        delta,
    )

    @Test
    fun exposure_brightensInsideMask_leavesOutside() {
        val b = grayBuf(0.5f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true, listOf(radialAdj(1f)))
        assertTrue("center brightened", px(b, 4, 4) > 0.6f)   // +1 EV inside the mask
        assertEquals("corner untouched", 0.5f, px(b, 0, 0), 1e-4f)
    }

    @Test
    fun negativeExposure_darkensInsideMask() {
        val b = grayBuf(0.5f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true, listOf(radialAdj(-1f)))
        assertTrue("center darkened", px(b, 4, 4) < 0.4f)
        assertEquals("corner untouched", 0.5f, px(b, 0, 0), 1e-4f)
    }

    @Test
    fun emptyOrNoOp_isByteIdentical() {
        val original = 0.5f
        val empty = grayBuf(original)
        MaskCompositor.applyInPlace(empty, W, H, ColorSpace.SRGB, true, emptyList())
        assertEquals(original, px(empty, 4, 4), 0f)

        val zeroEv = grayBuf(original)
        MaskCompositor.applyInPlace(zeroEv, W, H, ColorSpace.SRGB, true, listOf(radialAdj(0f)))
        assertEquals(original, px(zeroEv, 4, 4), 0f)  // 0 EV → no-op

        // A non-empty mask whose alpha is ~0 everywhere (off-frame, tiny) also costs nothing.
        val offFrame = LocalAdjustment(
            Mask(listOf(Mask.Component(BlendMode.ADD, MaskComponent.Radial(5f, 5f, 0.01f, 0.01f, 0.5f)))),
            TierADelta(exposureEv = 2f),
        )
        val miss = grayBuf(original)
        MaskCompositor.applyInPlace(miss, W, H, ColorSpace.SRGB, true, listOf(offFrame))
        assertEquals(original, px(miss, 4, 4), 0f)
    }

    @Test
    fun blendsByAlpha_partialAtEdge() {
        val b = grayBuf(0.5f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true, listOf(radialAdj(1f)))
        val center = px(b, 4, 4)
        // A feather-zone pixel (partial alpha) is brightened, but less than the fully-covered center.
        val edge = px(b, 5, 4)
        assertTrue("edge partially adjusted", edge > 0.5f && edge < center)
    }

    @Test
    fun stacksMultipleAdjustments() {
        // Two stacked +0.5 EV exposures compose toward roughly +1 EV at the shared center.
        val one = grayBuf(0.5f).also {
            MaskCompositor.applyInPlace(it, W, H, ColorSpace.SRGB, true, listOf(radialAdj(1f)))
        }
        val two = grayBuf(0.5f).also {
            MaskCompositor.applyInPlace(it, W, H, ColorSpace.SRGB, true, listOf(radialAdj(0.5f), radialAdj(0.5f)))
        }
        assertEquals(px(one, 4, 4), px(two, 4, 4), 0.02f)
    }

    @Test
    fun saturation_widensInsideMask_leavesOutside() {
        val b = colorBuf(0.6f, 0.4f, 0.3f)              // a warm, mildly saturated fill
        val origSpread = 0.6f - 0.3f
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(saturation = 80f))))
        assertTrue("center more saturated", spreadAt(b, 4, 4) > origSpread)
        assertEquals("corner untouched (R)", 0.6f, px(b, 0, 0), 1e-4f)  // outside the mask
    }

    @Test
    fun contrast_pushesInsideMask_leavesOutside() {
        // 0.3 is below the tone-curve pivot (~0.46), so +contrast darkens it inside the mask.
        val b = grayBuf(0.3f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(contrast = 100f))))
        assertTrue("center darkened (below pivot)", px(b, 4, 4) < 0.3f)
        assertEquals("corner untouched", 0.3f, px(b, 0, 0), 1e-4f)
    }

    @Test
    fun luminanceRange_limitsAdjustmentToTones() {
        // +1 EV limited to highlights (luma 0.5..1): a dark fill is gated out, a bright fill changes.
        val adj = radialAdj(TierADelta(exposureEv = 1f)).let {
            it.copy(mask = it.mask.copy(luminanceRange = LuminanceRange(lumMin = 0.5f, feather = 0.05f)))
        }
        val dark = grayBuf(0.3f)
        MaskCompositor.applyInPlace(dark, W, H, ColorSpace.SRGB, true, listOf(adj))
        assertEquals("dark tone gated out of the range", 0.3f, px(dark, 4, 4), 1e-3f)

        val bright = grayBuf(0.7f)
        MaskCompositor.applyInPlace(bright, W, H, ColorSpace.SRGB, true, listOf(adj))
        assertTrue("bright tone (in range) brightened", px(bright, 4, 4) > 0.7f)
    }

    @Test
    fun colorRange_limitsAdjustmentToColor() {
        // +1 EV limited to a red target: a red fill brightens; a blue fill (wrong color) is gated out.
        val adj = radialAdj(TierADelta(exposureEv = 1f)).let {
            it.copy(mask = it.mask.copy(
                colorRange = ColorRange(0.8f, 0.15f, 0.15f, tolerance = 0.18f, feather = 0.06f)))
        }
        val red = colorBuf(0.8f, 0.15f, 0.15f)
        MaskCompositor.applyInPlace(red, W, H, ColorSpace.SRGB, true, listOf(adj))
        assertTrue("matching red brightened", px(red, 4, 4) > 0.8f)         // R channel pushed up by +1 EV

        val blue = colorBuf(0.15f, 0.15f, 0.8f)
        MaskCompositor.applyInPlace(blue, W, H, ColorSpace.SRGB, true, listOf(adj))
        assertEquals("non-matching blue gated out", 0.15f, px(blue, 4, 4), 1e-3f)  // R channel untouched
    }

    @Test
    fun linearMask_graduatedExposure() {
        // A vertical gradient (top → bottom) + exposure = a graduated filter: the bottom brightens to
        // full effect, the top (the gradient's start) is left essentially untouched.
        val adj = LocalAdjustment(
            Mask(listOf(Mask.Component(BlendMode.ADD, MaskComponent.Linear(0.5f, 0f, 0.5f, 1f)))),
            TierADelta(exposureEv = 1f),
        )
        val b = grayBuf(0.5f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true, listOf(adj))
        val top = px(b, 4, 0)
        val bottom = px(b, 4, 7)
        assertTrue("bottom (full gradient) brightened", bottom > 0.6f)
        assertTrue("top (gradient start) ~untouched", top < 0.55f)
        assertTrue("ramps: bottom brighter than top", bottom > top)
    }

    @Test
    fun hue_shiftsColorInsideMask_leavesOutside() {
        val b = colorBuf(0.7f, 0.3f, 0.2f)              // a saturated warm fill
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(hue = 120f))))
        // inside the mask the hue rotated → the channel balance changed meaningfully
        val moved = abs(chan(b, 4, 4, 0) - 0.7f) + abs(chan(b, 4, 4, 1) - 0.3f) + abs(chan(b, 4, 4, 2) - 0.2f)
        assertTrue("hue shifted inside the mask", moved > 0.05f)
        // outside the mask is byte-identical
        assertEquals("corner untouched (R)", 0.7f, chan(b, 0, 0, 0), 1e-4f)
        assertEquals("corner untouched (G)", 0.3f, chan(b, 0, 0, 1), 1e-4f)
        assertEquals("corner untouched (B)", 0.2f, chan(b, 0, 0, 2), 1e-4f)
    }

    @Test
    fun hue_grayStaysNeutral() {
        // rotating the hue of a neutral gray is a no-op (Oklab a=b=0) — no color cast introduced
        val b = grayBuf(0.5f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(hue = 90f))))
        assertEquals(0.5f, chan(b, 4, 4, 0), 3e-3f)
        assertEquals(0.5f, chan(b, 4, 4, 1), 3e-3f)
        assertEquals(0.5f, chan(b, 4, 4, 2), 3e-3f)
    }

    @Test
    fun whites_brightenHighlightsInsideMask_leavesOutside() {
        val b = grayBuf(0.6f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(whites = 80f))))
        assertTrue("highlight brightened inside the mask", px(b, 4, 4) > 0.6f)
        assertEquals("corner untouched", 0.6f, px(b, 0, 0), 1e-4f)
    }

    @Test
    fun blacks_crushShadowsInsideMask_leavesOutside() {
        val b = grayBuf(0.3f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(blacks = -80f))))
        assertTrue("shadow deepened inside the mask", px(b, 4, 4) < 0.3f)
        assertEquals("corner untouched", 0.3f, px(b, 0, 0), 1e-4f)
    }

    @Test
    fun temp_warmsInsideMask_leavesOutside() {
        val b = grayBuf(0.5f)
        MaskCompositor.applyInPlace(b, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(temp = 60f))))
        // inside the mask a neutral gray is warmed (R lifted over B); outside untouched
        assertTrue("warmed inside (R > B)", chan(b, 4, 4, 0) > chan(b, 4, 4, 2))
        assertEquals("corner untouched (R)", 0.5f, chan(b, 0, 0, 0), 1e-4f)
        assertEquals("corner untouched (B)", 0.5f, chan(b, 0, 0, 2), 1e-4f)
    }

    @Test
    fun whitesBlacks_anchorTheOppositeEnd() {
        // whites+ keeps the black point anchored; blacks− keeps the white point anchored.
        val black = grayBuf(0f)
        MaskCompositor.applyInPlace(black, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(whites = 100f))))
        assertEquals("black stays black under whites+", 0f, px(black, 4, 4), 1e-3f)

        val white = grayBuf(1f)
        MaskCompositor.applyInPlace(white, W, H, ColorSpace.SRGB, true,
            listOf(radialAdj(TierADelta(blacks = -100f))))
        assertEquals("white stays white under blacks−", 1f, px(white, 4, 4), 1e-3f)
    }
}
