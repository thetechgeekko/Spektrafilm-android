/*
 * Spektrafilm for Android — unit tests for the spatial (Class-S) mask primitive. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pins the box-blur (constant in → constant out, edge-clamped, smooths/reduces variance, radius<1 =
 * no-op copy) and the Clarity midtone weight. The compositor wires these onto the output luma.
 */
package com.spectrafilm.app.masks

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskSpatialTest {

    @Test
    fun blurOfConstantIsConstant() {
        val w = 8; val h = 6
        val src = FloatArray(w * h) { 0.42f }
        val b = MaskSpatial.blur(src, w, h, 3f)
        for (v in b) assertEquals(0.42f, v, 1e-4f)
    }

    @Test
    fun radiusBelowOneIsANoOpCopy() {
        val src = FloatArray(9) { it.toFloat() }
        val b = MaskSpatial.blur(src, 3, 3, 0.4f)
        assertArrayEquals(src, b, 0f)
        assertNotSame("must not return the input instance", src, b)
    }

    @Test
    fun blurSmoothsASpike() {
        val w = 9; val h = 9
        val src = FloatArray(w * h)
        src[4 * w + 4] = 1f  // single bright pixel
        val b = MaskSpatial.blur(src, w, h, 2f)
        assertTrue("center reduced", b[4 * w + 4] < 1f)
        assertTrue("a neighbor raised", b[4 * w + 5] > 0f)
        assertTrue("peak decreased", b.max() < 1f)
    }

    @Test
    fun blurReducesVariance() {
        val w = 16; val h = 16
        val src = FloatArray(w * h) { if (((it / w) + (it % w)) % 2 == 0) 1f else 0f }
        val b = MaskSpatial.blur(src, w, h, 2f)
        fun variance(a: FloatArray): Float {
            val m = a.average().toFloat()
            return a.map { (it - m) * (it - m) }.average().toFloat()
        }
        assertTrue("blur reduces variance", variance(b) < variance(src))
    }

    @Test
    fun midtoneWeightPeaksAtMidtoneAndZeroAtExtremes() {
        assertEquals(1f, MaskSpatial.midtoneWeight(0.5f), 1e-4f)
        assertEquals(0f, MaskSpatial.midtoneWeight(0f), 1e-4f)
        assertEquals(0f, MaskSpatial.midtoneWeight(1f), 1e-4f)
        assertTrue(MaskSpatial.midtoneWeight(0.5f) > MaskSpatial.midtoneWeight(0.2f))
    }
}
