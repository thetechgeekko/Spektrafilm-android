/*
 * Spektrafilm for Android — export-scale render cap. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.LinearImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPixelCapTest {

    @Test
    fun capScalesWithRamAndNeverDropsBelowTwelveMegapixels() {
        // SM-S948W reports 11,623,403,520 bytes: a 50 MP frame must not fit, ~27 MP must.
        val phone = engineRenderPixelCap(11_623_403_520L)
        assertTrue(phone in 26_000_000L..29_000_000L)
        assertTrue(6120L * 8160L > phone)
        // An "8 GB" phone (~7.5 GiB reported) still renders an 18 MP frame.
        assertTrue(engineRenderPixelCap(8_053_063_680L) > 18_000_000L)
        assertEquals(ENGINE_RENDER_MIN_PIXELS, engineRenderPixelCap(0L))
        assertEquals(ENGINE_RENDER_MIN_PIXELS, engineRenderPixelCap(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun reductionKeepsAspectAndStaysUnderTheCap() {
        assertNull(reducedToFit(4000, 3000, 12_000_000L))
        val (w, h) = requireNotNull(reducedToFit(6120, 8160, 27_000_000L))
        assertTrue(w.toLong() * h <= 27_000_000L)
        assertTrue(w.toLong() * h > 26_500_000L)
        assertEquals(6120.0 / 8160.0, w.toDouble() / h, 0.001)
    }

    @Test
    fun areaDownscaleIsTheExactMeanOfEachCoveredArea() {
        // 3x3 -> 2x2 covers 1.5x1.5 source pixels per output pixel.
        val src = image(3, 3) { x, y -> (y * 3 + x).toFloat() }
        val out = src.areaDownscaled(2, 2)
        val px = read(out)
        // Top-left: pixels 0,1 (x) and rows 0,1 with half weights on the middle column/row.
        // sum = 1*0 + .5*1 + 1*3... computed as the area integral / 2.25.
        val expectedTopLeft = (0f + 0.5f * 1f + 0.5f * 3f + 0.25f * 4f) / 2.25f
        assertEquals(expectedTopLeft, px[0], 1e-5f)
        // A flat field stays exactly flat, so no energy is lost or added at the edges.
        val flat = image(7, 5) { _, _ -> 0.75f }.areaDownscaled(3, 2)
        read(flat).forEach { assertEquals(0.75f, it, 1e-6f) }
        // Overall mean is preserved for a non-integer ratio.
        val ramp = image(10, 7) { x, y -> (x + 2 * y).toFloat() }
        val small = ramp.areaDownscaled(4, 3)
        assertEquals(read(ramp).average(), read(small).average(), 1e-4)
    }

    private fun image(w: Int, h: Int, value: (Int, Int) -> Float): LinearImage {
        val buf = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) repeat(3) { buf.putFloat(value(x, y)) }
        buf.flip()
        return LinearImage(buf, w, h)
    }

    private fun read(img: LinearImage): FloatArray = img.acquireDataLease().use { lease ->
        val f = lease.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        FloatArray(f.remaining()).also { f.get(it) }
    }
}
