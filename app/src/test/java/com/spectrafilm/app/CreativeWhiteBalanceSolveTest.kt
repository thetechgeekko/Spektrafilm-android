/*
 * Spektrafilm for Android — unit tests for the gray-point WB solver. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The eyedropper math: solveNeutral(sampled neutral) must return a (temp,tint) that, applied via the
 * public matrix(), drives that pixel's chroma to ~0 — and be a no-op on an already-neutral sample.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CreativeWhiteBalanceSolveTest {

    private fun applyWb(temp: Float, tint: Float, r: Float, g: Float, b: Float): FloatArray {
        val m = CreativeWhiteBalance.matrix(temp, tint)
        return floatArrayOf(
            m[0] * r + m[1] * g + m[2] * b,
            m[3] * r + m[4] * g + m[5] * b,
            m[6] * r + m[7] * g + m[8] * b,
        )
    }

    /** Scale-invariant chroma: channel spread around the mean, relative to the mean. 0 = neutral. */
    private fun chroma(c: FloatArray): Float {
        val m = (c[0] + c[1] + c[2]) / 3f
        if (m <= 1e-6f) return 0f
        return (abs(c[0] - m) + abs(c[1] - m) + abs(c[2] - m)) / m
    }

    @Test
    fun identityOnNeutral() {
        val (t, ti) = CreativeWhiteBalance.solveNeutral(0.5f, 0.5f, 0.5f)
        assertEquals(0f, t, 1.0f)
        assertEquals(0f, ti, 1.0f)
    }

    @Test
    fun coolsAWarmCast() {
        val r = 0.62f; val g = 0.55f; val b = 0.42f   // warm/yellow
        val before = chroma(floatArrayOf(r, g, b))
        val (t, ti) = CreativeWhiteBalance.solveNeutral(r, g, b)
        val after = chroma(applyWb(t, ti, r, g, b))
        assertTrue("warm cast should solve to a cooling WB (temp<0), got $t", t < 0f)
        assertTrue("chroma should drop sharply: before=$before after=$after", after < before * 0.3f)
    }

    @Test
    fun addsMagentaForAGreenCast() {
        val (t, ti) = CreativeWhiteBalance.solveNeutral(0.5f, 0.62f, 0.5f)
        assertTrue("green cast should solve to magenta tint (>0), got $ti", ti > 0f)
        val after = chroma(applyWb(t, ti, 0.5f, 0.62f, 0.5f))
        assertTrue("near neutral after, got $after", after < 0.06f)
    }

    @Test
    fun neutralizesVariousCasts() {
        val casts = listOf(
            floatArrayOf(0.70f, 0.52f, 0.45f),
            floatArrayOf(0.45f, 0.52f, 0.70f),
            floatArrayOf(0.50f, 0.60f, 0.52f),
            floatArrayOf(0.55f, 0.50f, 0.62f),
        )
        for (c in casts) {
            val (t, ti) = CreativeWhiteBalance.solveNeutral(c[0], c[1], c[2])
            val after = chroma(applyWb(t, ti, c[0], c[1], c[2]))
            assertTrue("cast ${c.toList()} → after chroma $after", after < 0.08f)
        }
    }
}
