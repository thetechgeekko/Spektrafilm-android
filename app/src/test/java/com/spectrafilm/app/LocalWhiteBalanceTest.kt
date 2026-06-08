/*
 * Spektrafilm for Android — unit tests for the per-mask local white balance. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards the output-space chromatic adaptation: neutral is an exact identity (every output space),
 * temp warms/cools and tint pushes magenta/green in the right direction, applied to a neutral gray.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWhiteBalanceTest {

    /** Apply a row-major 3x3 to an RGB triple. */
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) = floatArrayOf(
        m[0] * r + m[1] * g + m[2] * b,
        m[3] * r + m[4] * g + m[5] * b,
        m[6] * r + m[7] * g + m[8] * b,
    )

    @Test
    fun neutralIsExactIdentity_everyOutputSpace() {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (cs in ColorSpace.values()) {
            val m = LocalWhiteBalance.matrix(cs, 0f, 0f)
            for (i in 0 until 9) assertEquals("identity @$cs[$i]", id[i], m[i], 1e-5f)
        }
    }

    @Test
    fun tempWarmsAndCoolsAGray() {
        val warm = apply(LocalWhiteBalance.matrix(ColorSpace.SRGB, 60f, 0f), 1f, 1f, 1f)
        assertTrue("temp+ warms: R > B", warm[0] > warm[2])
        val cool = apply(LocalWhiteBalance.matrix(ColorSpace.SRGB, -60f, 0f), 1f, 1f, 1f)
        assertTrue("temp− cools: B > R", cool[2] > cool[0])
    }

    @Test
    fun tintPushesMagentaAndGreen() {
        val magenta = apply(LocalWhiteBalance.matrix(ColorSpace.SRGB, 0f, 60f), 1f, 1f, 1f)
        assertTrue("tint+ is magenta: G is the smallest", magenta[1] < magenta[0] && magenta[1] < magenta[2])
        val green = apply(LocalWhiteBalance.matrix(ColorSpace.SRGB, 0f, -60f), 1f, 1f, 1f)
        assertTrue("tint− is green: G is the largest", green[1] > green[0] && green[1] > green[2])
    }

    @Test
    fun worksForAWideSpace_proPhoto() {
        // ProPhoto is D50-native: neutral identity + temp still warms.
        val id = LocalWhiteBalance.matrix(ColorSpace.PROPHOTO, 0f, 0f)
        assertEquals(1f, id[0], 1e-5f); assertEquals(0f, id[1], 1e-5f)
        val warm = apply(LocalWhiteBalance.matrix(ColorSpace.PROPHOTO, 50f, 0f), 1f, 1f, 1f)
        assertTrue("temp+ warms in ProPhoto too", warm[0] > warm[2])
    }
}
