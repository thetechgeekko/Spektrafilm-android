/*
 * Spektrafilm for Android — unit tests for the Saturation/Vibrance Oklab grade. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards the post-engine chroma grade: the strict no-op default, the gray-stays-neutral invariant
 * (which must hold for EVERY output space), and the saturation/vibrance direction + endpoints. Pure
 * JVM (ByteBuffer + Math), no engine touched.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGradeTest {

    private fun buf(vararg rgb: Float): ByteBuffer {
        val b = ByteBuffer.allocateDirect(rgb.size * 4).order(ByteOrder.nativeOrder())
        val f = b.asFloatBuffer()
        for (v in rgb) f.put(v)
        return b
    }

    private fun read(b: ByteBuffer, i: Int): Float = b.order(ByteOrder.nativeOrder()).asFloatBuffer().get(i)

    private fun spread(b: ByteBuffer): Float {
        val r = read(b, 0); val g = read(b, 1); val bl = read(b, 2)
        return max(r, max(g, bl)) - min(r, min(g, bl))
    }

    @Test
    fun isActive_onlyWhenNonZero() {
        assertFalse(ColorGrade.isActive(0f, 0f))
        assertTrue(ColorGrade.isActive(1f, 0f))
        assertTrue(ColorGrade.isActive(0f, -1f))
    }

    @Test
    fun zeroGrade_isByteIdenticalNoOp() {
        val original = floatArrayOf(0.2f, 0.6f, 0.35f, 0.9f, 0.1f, 0.4f)
        val b = buf(*original)
        ColorGrade.applyInPlace(b, 2, 1, ColorSpace.SRGB, cctfEncoded = true, saturation = 0f, vibrance = 0f)
        for (i in original.indices) assertEquals(original[i], read(b, i), 0f)  // exact
    }

    @Test
    fun gray_staysNeutral_inEveryColorSpace() {
        // The key invariant: neutral (v,v,v) -> a=b=0 in Oklab for any primaries, so it must survive
        // any chroma scale unchanged in EVERY output space (and with CCTF on or off).
        for (cs in ColorSpace.entries) {
            for (cctf in booleanArrayOf(true, false)) {
                val b = buf(0.5f, 0.5f, 0.5f)
                ColorGrade.applyInPlace(b, 1, 1, cs, cctf, saturation = 80f, vibrance = -50f)
                val r = read(b, 0); val g = read(b, 1); val bl = read(b, 2)
                assertEquals("gray R==G in $cs cctf=$cctf", r, g, 2e-3f)
                assertEquals("gray G==B in $cs cctf=$cctf", g, bl, 2e-3f)
                assertEquals("gray level preserved in $cs cctf=$cctf", 0.5f, r, 5e-3f)
            }
        }
    }

    @Test
    fun positiveSaturation_increasesChroma_negativeDecreases() {
        val src = floatArrayOf(0.6f, 0.4f, 0.3f)  // a warm, mildly saturated pixel
        val before = spread(buf(*src))
        val up = buf(*src).also { ColorGrade.applyInPlace(it, 1, 1, ColorSpace.SRGB, true, 60f, 0f) }
        val down = buf(*src).also { ColorGrade.applyInPlace(it, 1, 1, ColorSpace.SRGB, true, -60f, 0f) }
        assertTrue("sat+ widens channel spread", spread(up) > before)
        assertTrue("sat- narrows channel spread", spread(down) < before)
    }

    @Test
    fun fullNegativeSaturation_isGrayscale() {
        val b = buf(0.7f, 0.2f, 0.1f)
        ColorGrade.applyInPlace(b, 1, 1, ColorSpace.SRGB, true, -100f, 0f)
        val r = read(b, 0); val g = read(b, 1); val bl = read(b, 2)
        assertEquals(r, g, 3e-3f)
        assertEquals(g, bl, 3e-3f)
    }

    @Test
    fun vibrance_favoursMutedOverSaturated() {
        // A near-neutral (muted) pixel should be pushed more by +vibrance than an already-vivid one.
        val muted = floatArrayOf(0.52f, 0.48f, 0.50f)
        val vivid = floatArrayOf(0.85f, 0.12f, 0.10f)
        val ratioMuted = run {
            val b = buf(*muted); val s0 = spread(b)
            ColorGrade.applyInPlace(b, 1, 1, ColorSpace.SRGB, true, 0f, 100f); spread(b) / s0
        }
        val ratioVivid = run {
            val b = buf(*vivid); val s0 = spread(b)
            ColorGrade.applyInPlace(b, 1, 1, ColorSpace.SRGB, true, 0f, 100f); spread(b) / s0
        }
        // The chroma multiplier f = 1+vib·exp(-C/C0) is larger for low-chroma, so the muted pixel's
        // spread grows by a larger factor than the already-vivid one.
        assertTrue("muted grows", ratioMuted > 1f)
        assertTrue("vibrance favours muted", ratioMuted > ratioVivid)
    }

    @Test
    fun outputStaysInRange() {
        // An extreme push must still clamp into [0,1] (no NaN/overflow from gamut excursions).
        val b = buf(0.95f, 0.05f, 0.02f, 0.1f, 0.9f, 0.85f)
        ColorGrade.applyInPlace(b, 2, 1, ColorSpace.SRGB, true, 100f, 100f)
        for (i in 0 until 6) {
            val v = read(b, i)
            assertTrue("in range: $v", v in 0f..1f && !v.isNaN())
        }
    }
}
