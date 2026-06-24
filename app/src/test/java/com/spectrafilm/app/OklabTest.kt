/*
 * Spektrafilm for Android — unit tests for the shared Oklab hue/chroma primitives. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards rotateHueLinear (the per-mask local Hue op): it must leave neutrals untouched, be a no-op at
 * 0°/360°, and rotate the chroma vector while preserving Oklab lightness and chroma magnitude.
 */
package com.spectrafilm.app

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklabTest {

    @Test
    fun rotateHue_grayIsNeutral() {
        val rgb = floatArrayOf(0.4f, 0.4f, 0.4f)   // achromatic → a=b=0 → rotation is identity
        Oklab.rotateHueLinear(rgb, 137f)
        assertEquals(0.4f, rgb[0], 1e-4f)
        assertEquals(0.4f, rgb[1], 1e-4f)
        assertEquals(0.4f, rgb[2], 1e-4f)
    }

    @Test
    fun rotateHue_zeroAndFullTurnAreNoOps() {
        val zero = floatArrayOf(0.6f, 0.2f, 0.1f)
        Oklab.rotateHueLinear(zero, 0f)
        assertEquals(0.6f, zero[0], 1e-4f); assertEquals(0.2f, zero[1], 1e-4f); assertEquals(0.1f, zero[2], 1e-4f)

        val full = floatArrayOf(0.55f, 0.3f, 0.15f)
        Oklab.rotateHueLinear(full, 360f)
        assertEquals(0.55f, full[0], 2e-3f); assertEquals(0.3f, full[1], 2e-3f); assertEquals(0.15f, full[2], 2e-3f)
    }

    @Test
    fun rotateHue_preservesLightnessAndChroma_changesColor() {
        val rgb = floatArrayOf(0.6f, 0.2f, 0.1f)
        val before = oklab(rgb)
        Oklab.rotateHueLinear(rgb, 90f)
        val after = oklab(rgb)
        assertEquals("L preserved", before[0], after[0], 2e-3f)
        assertEquals("chroma magnitude preserved",
            hypot(before[1], before[2]), hypot(after[1], after[2]), 2e-3f)
        assertTrue("the color actually changed",
            kotlin.math.abs(before[1] - after[1]) + kotlin.math.abs(before[2] - after[2]) > 0.02f)
    }

    /** Forward Ottosson linear-RGB → Oklab (L,a,b), for the assertions above. */
    private fun oklab(rgb: FloatArray): FloatArray {
        val l = 0.4122214708f * rgb[0] + 0.5363325363f * rgb[1] + 0.0514459929f * rgb[2]
        val m = 0.2119034982f * rgb[0] + 0.6806995451f * rgb[1] + 0.1073969566f * rgb[2]
        val s = 0.0883024619f * rgb[0] + 0.2817188376f * rgb[1] + 0.6299787005f * rgb[2]
        val l_ = Math.cbrt(l.toDouble()).toFloat()
        val m_ = Math.cbrt(m.toDouble()).toFloat()
        val s_ = Math.cbrt(s.toDouble()).toFloat()
        return floatArrayOf(
            0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
            1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
            0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_,
        )
    }
}
