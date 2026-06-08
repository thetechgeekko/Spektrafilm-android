/*
 * Spektrafilm for Android — unit tests for the Contrast → tone-curve S-curve. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards the pure pivoted-S-curve generator + its composition under a user-drawn master curve. The
 * curve drives the engine's parity-gated master tone curve, so getting the math right here keeps the
 * Contrast slider faithful without touching the engine.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastCurveTest {

    private val eps = 1e-4f

    @Test
    fun gamma_isGeometricAroundOne() {
        assertEquals(1f, ContrastCurve.gamma(0f), eps)
        assertEquals(2f, ContrastCurve.gamma(100f), eps)
        assertEquals(0.5f, ContrastCurve.gamma(-100f), eps)
        // Symmetric in log: +50 and -50 are reciprocals.
        assertEquals(1f, ContrastCurve.gamma(50f) * ContrastCurve.gamma(-50f), 1e-3f)
    }

    @Test
    fun curve_pinsEndpointsAndPivot_forAnyContrast() {
        for (c in intArrayOf(-100, -40, 0, 25, 100)) {
            assertEquals(0f, ContrastCurve.curveAt(0f, c.toFloat()), eps)
            assertEquals(1f, ContrastCurve.curveAt(1f, c.toFloat()), eps)
            // Mid-gray (the pivot) is fixed → contrast never shifts overall brightness.
            assertEquals(ContrastCurve.PIVOT, ContrastCurve.curveAt(ContrastCurve.PIVOT, c.toFloat()), eps)
        }
    }

    @Test
    fun positiveContrast_makesAnSCurve() {
        // Below pivot darkens, above pivot brightens.
        assertTrue(ContrastCurve.curveAt(0.2f, 100f) < 0.2f)
        assertTrue(ContrastCurve.curveAt(0.8f, 100f) > 0.8f)
    }

    @Test
    fun negativeContrast_mutes() {
        // The "soften the punchy look" direction: shadows lift, highlights pull down.
        assertTrue(ContrastCurve.curveAt(0.2f, -100f) > 0.2f)
        assertTrue(ContrastCurve.curveAt(0.8f, -100f) < 0.8f)
    }

    @Test
    fun curve_isMonotonicIncreasing() {
        for (c in intArrayOf(-100, -50, 50, 100)) {
            var prev = -1f
            var x = 0f
            while (x <= 1f) {
                val y = ContrastCurve.curveAt(x, c.toFloat())
                assertTrue("monotonic at x=$x c=$c", y >= prev - eps)
                prev = y
                x += 0.02f
            }
        }
    }

    @Test
    fun points_emptyAtZero_validOtherwise() {
        assertTrue(ContrastCurve.points(0f).isEmpty())
        val pts = ContrastCurve.points(75f)
        assertTrue(pts.size in 2..16)
        // x strictly increasing, all within [0,1].
        for (i in pts.indices) {
            val (x, y) = pts[i]
            assertTrue(x in 0f..1f && y in 0f..1f)
            if (i > 0) assertTrue(pts[i - 1].first < x)
        }
    }

    @Test
    fun compose_isIdentityPassthroughAtZeroContrast() {
        val user = listOf(0f to 0f, 0.5f to 0.7f, 1f to 1f)
        assertEquals(user, ContrastCurve.composeMaster(user, 0f))
    }

    @Test
    fun compose_returnsPlainContrastWhenUserCurveIsIdentity() {
        assertEquals(ContrastCurve.points(60f), ContrastCurve.composeMaster(emptyList(), 60f))
        assertEquals(ContrastCurve.points(60f),
            ContrastCurve.composeMaster(listOf(0f to 0f, 1f to 1f), 60f))
    }

    @Test
    fun compose_appliesUserCurveAfterContrast() {
        // user curve = a simple brightening (lift midtones); composite final(x) = user(contrast(x)).
        val user = listOf(0f to 0f, 0.5f to 0.6f, 1f to 1f)
        val composite = ContrastCurve.composeMaster(user, 100f)
        // Endpoints stay pinned; the pivot maps through user(0.46).
        assertEquals(0f, composite.first().second, 2e-2f)
        assertEquals(1f, composite.last().second, 2e-2f)
        // At the pivot grid point, contrast(0.46)=0.46, so composite ≈ user(0.46) > 0.46 (user lifts).
        val pivotPt = composite.first { kotlin.math.abs(it.first - ContrastCurve.PIVOT) < 1e-3f }
        assertTrue("user lift applied at pivot", pivotPt.second > ContrastCurve.PIVOT)
    }
}
