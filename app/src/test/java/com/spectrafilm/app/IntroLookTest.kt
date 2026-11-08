/*
 * Spektrafilm for Android — the intro's illustrative film looks. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class IntroLookTest {

    /** Applies a 4x5 colour matrix (0..255 offsets) to an RGB triple in 0..255. */
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3) { row -> m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4] }

    @Test
    fun neutralSettings_areIdentity() {
        val m = lookMatrix(saturation = 1f, contrast = 1f).values
        val out = apply(m, 200f, 90f, 30f)
        assertEquals(200f, out[0], 1e-3f)
        assertEquals(90f, out[1], 1e-3f)
        assertEquals(30f, out[2], 1e-3f)
        assertEquals(1f, m[18], 0f)
    }

    @Test
    fun blackAndWhite_givesEqualChannels() {
        val m = IntroLook.HP5.matrix!!.values
        val out = apply(m, 220f, 120f, 40f)
        assertEquals(out[0], out[1], 1e-3f)
        assertEquals(out[1], out[2], 1e-3f)
    }

    @Test
    fun contrast_pivotsOnMidGrey() {
        val m = lookMatrix(saturation = 1f, contrast = 1.5f).values
        val out = apply(m, 127.5f, 127.5f, 127.5f)
        out.forEach { assertEquals(127.5f, it, 1e-3f) }
    }

    @Test
    fun digital_hasNoFilter() {
        assertEquals(null, IntroLook.DIGITAL.matrix)
    }
}
