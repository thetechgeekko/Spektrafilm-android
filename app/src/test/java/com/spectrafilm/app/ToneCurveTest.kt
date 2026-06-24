/*
 * Spektrafilm for Android — unit tests for the point tone-curve editor math. GPLv3.
 *
 * ToneCurveMath is the pure model behind the curve UI: the editable point ops (add/move/remove,
 * with end points pinned in x and interior points clamped between neighbours) and the on-screen
 * sampler — a faithful port of the engine's Fritsch–Carlson monotone-cubic bake. No Android
 * framework, so it runs on the plain JVM under :app:testDebugUnitTest.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.TONE_CURVE_MAX_POINTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveTest {

    // ---- model -------------------------------------------------------------

    @Test fun effectiveSurfacesEndpointsForShortLists() {
        assertEquals(listOf(0f to 0f, 1f to 1f), ToneCurveMath.effective(emptyList()))
        assertEquals(listOf(0f to 0f, 1f to 1f), ToneCurveMath.effective(listOf(0.5f to 0.5f)))
        val three = listOf(0f to 0f, 0.5f to 0.6f, 1f to 1f)
        assertEquals(three, ToneCurveMath.effective(three))
    }

    @Test fun isIdentityForOnDiagonalPoints() {
        assertTrue(ToneCurveMath.isIdentity(emptyList()))
        assertTrue(ToneCurveMath.isIdentity(listOf(0f to 0f, 1f to 1f)))
        assertTrue(!ToneCurveMath.isIdentity(listOf(0f to 0.1f, 1f to 1f)))
    }

    @Test fun addInsertsSortedAndPinsEdges() {
        val out = ToneCurveMath.add(emptyList(), 0.3f, 0.7f)
        assertEquals(3, out.size)
        assertEquals(0.3f, out[1].first, 1e-4f)
        assertEquals(0.7f, out[1].second, 1e-4f)
        // x at/over the edges is reserved for the end points → ignored.
        assertEquals(2, ToneCurveMath.add(emptyList(), 0f, 0.5f).size)
        assertEquals(2, ToneCurveMath.add(emptyList(), 1f, 0.5f).size)
    }

    @Test fun addRejectsNearDuplicateAndRespectsCap() {
        val three = listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f)
        assertEquals(3, ToneCurveMath.add(three, 0.5f, 0.9f).size)   // on an existing x
        assertEquals(3, ToneCurveMath.add(three, 0.505f, 0.9f).size) // within MIN_GAP
        val full = List(TONE_CURVE_MAX_POINTS) { i -> (i / (TONE_CURVE_MAX_POINTS - 1f)) to 0f }
        assertEquals(TONE_CURVE_MAX_POINTS, ToneCurveMath.add(full, 0.51f, 0.5f).size)
    }

    @Test fun movePinsEndpointXAndClampsY() {
        val lo = ToneCurveMath.move(listOf(0f to 0f, 1f to 1f), 0, 0.4f, 1.5f)
        assertEquals(0f, lo[0].first, 1e-4f)   // x pinned to 0
        assertEquals(1f, lo[0].second, 1e-4f)  // y clamped to 1
        val hi = ToneCurveMath.move(listOf(0f to 0f, 1f to 1f), 1, 0.6f, -0.2f)
        assertEquals(1f, hi[1].first, 1e-4f)   // x pinned to 1
        assertEquals(0f, hi[1].second, 1e-4f)  // y clamped to 0
    }

    @Test fun moveClampsInteriorBetweenNeighbours() {
        val pts = listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f)
        val right = ToneCurveMath.move(pts, 1, 0.99f, 0.5f)
        assertTrue("must stay left of the right neighbour", right[1].first <= 1f - ToneCurveMath.MIN_GAP + 1e-4f)
        val left = ToneCurveMath.move(pts, 1, -0.5f, 0.5f)
        assertTrue("must stay right of the left neighbour", left[1].first >= ToneCurveMath.MIN_GAP - 1e-4f)
    }

    @Test fun removeDropsInteriorOnly() {
        val pts = listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f)
        assertEquals(listOf(0f to 0f, 1f to 1f), ToneCurveMath.remove(pts, 1))
        assertEquals(pts, ToneCurveMath.remove(pts, 0))  // end points are kept
        assertEquals(pts, ToneCurveMath.remove(pts, 2))
    }

    // ---- sampler -----------------------------------------------------------

    @Test fun identitySamplesToRamp() {
        val ramp = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        assertArrayEquals(ramp, ToneCurveMath.sample(emptyList(), 5))
        assertArrayEquals(ramp, ToneCurveMath.sample(listOf(0f to 0f, 1f to 1f), 5))
    }

    @Test fun curvePassesThroughControlPoints() {
        // Hermite interpolation passes through its data points; with n = 1001 the control x's
        // land on exact sample indices.
        val y = ToneCurveMath.sample(listOf(0f to 0f, 0.5f to 0.25f, 1f to 1f), 1001)
        assertEquals(0f, y[0], 1e-3f)
        assertEquals(0.25f, y[500], 1e-3f)   // x = 0.5
        assertEquals(1f, y[1000], 1e-3f)
    }

    @Test fun extrapolatesFlatPastEndPoints() {
        val y = ToneCurveMath.sample(listOf(0.25f to 0.4f, 0.75f to 0.6f), 1001)
        assertEquals(0.4f, y[0], 1e-3f)     // x = 0    (< first x)
        assertEquals(0.4f, y[100], 1e-3f)   // x = 0.1  (< first x)
        assertEquals(0.6f, y[900], 1e-3f)   // x = 0.9  (> last x)
        assertEquals(0.6f, y[1000], 1e-3f)  // x = 1
    }

    @Test fun monotoneCurveStaysNonDecreasing() {
        val y = ToneCurveMath.sample(listOf(0f to 0f, 0.25f to 0.1f, 0.75f to 0.9f, 1f to 1f), 200)
        for (k in 1 until y.size) {
            assertTrue("sample must be non-decreasing at $k", y[k] >= y[k - 1] - 1e-4f)
        }
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-4f)
    }
}
