/*
 * Spektrafilm for Android — unit tests for ACES gamut compression. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards the RGC shaper (identity below threshold, monotonic, bounded) and the per-pixel apply
 * (gray/achromatic preserved, saturated colors pulled toward neutral, amount=0 no-op, in range).
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamutCompressTest {

    @Test
    fun isActive_onlyAboveZero() {
        assertFalse(GamutCompress.isActive(0f))
        assertTrue(GamutCompress.isActive(50f))
    }

    @Test
    fun compress_identityBelowThreshold() {
        // All channel thresholds are >= 0.80, so a distance of 0.5 is untouched.
        for (ch in 0..2) assertEquals(0.5f, GamutCompress.compress(0.5f, ch), 1e-5f)
        for (ch in 0..2) assertEquals(0f, GamutCompress.compress(0f, ch), 1e-5f)
    }

    @Test
    fun compress_compressesAndIsMonotonicBeyondThreshold() {
        for (ch in 0..2) {
            // A large distance is pulled down (compressed) relative to its input.
            assertTrue("ch=$ch compresses", GamutCompress.compress(1.5f, ch) < 1.5f)
            // Monotonic increasing.
            var prev = -1f
            var d = 0f
            while (d <= 3f) {
                val c = GamutCompress.compress(d, ch)
                assertTrue("monotonic ch=$ch d=$d", c >= prev - 1e-5f)
                assertTrue("finite", !c.isNaN() && !c.isInfinite())
                prev = c
                d += 0.05f
            }
        }
    }

    @Test
    fun apply_amountZero_isNoOp() {
        val rgb = floatArrayOf(0.9f, 0.1f, 0.05f)
        val copy = rgb.copyOf()
        GamutCompress.apply(rgb, 0f)
        for (i in 0..2) assertEquals(copy[i], rgb[i], 0f)
    }

    @Test
    fun apply_grayUnchanged() {
        val rgb = floatArrayOf(0.5f, 0.5f, 0.5f)
        GamutCompress.apply(rgb, 100f)
        for (i in 0..2) assertEquals(0.5f, rgb[i], 1e-5f)
    }

    @Test
    fun apply_preservesAchromaticChannel() {
        // The brightest channel (= achromatic) has distance 0, so it is never moved.
        val rgb = floatArrayOf(0.95f, 0.1f, 0.02f)
        val achBefore = rgb[0]
        GamutCompress.apply(rgb, 100f)
        assertEquals(achBefore, rgb[0], 1e-5f)
    }

    @Test
    fun apply_pullsSaturatedChannelsTowardNeutral() {
        // A very saturated pixel: the dark channels (large distance) get lifted toward the achromatic.
        val rgb = floatArrayOf(0.95f, 0.05f, 0.0f)
        GamutCompress.apply(rgb, 100f)
        assertTrue("G lifted", rgb[1] > 0.05f)
        assertTrue("B lifted", rgb[2] > 0.0f)
        // ...but never past the achromatic axis (still <= max channel).
        assertTrue(rgb[1] <= rgb[0] && rgb[2] <= rgb[0])
    }

    @Test
    fun apply_amountScalesEffect() {
        val full = floatArrayOf(0.95f, 0.05f, 0.0f).also { GamutCompress.apply(it, 100f) }
        val half = floatArrayOf(0.95f, 0.05f, 0.0f).also { GamutCompress.apply(it, 50f) }
        // 50% sits between the original and the full compression.
        assertTrue(half[1] > 0.05f && half[1] < full[1])
    }
}
