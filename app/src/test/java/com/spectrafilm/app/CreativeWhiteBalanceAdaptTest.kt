/*
 * Spektrafilm for Android — unit tests for the absolute D50→CCT adaptation (85-filter core). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * adaptD50ToCct(targetCct) is the engine behind "balance to film stock": a Bradford CAT from the D50
 * working white to an absolute target white. Applied to a neutral ProPhoto pixel it must yield that
 * target white's ProPhoto coordinates — warm (R>B) for a tungsten target, cool (B>R) for daylight,
 * and identity at D50.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativeWhiteBalanceAdaptTest {

    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        m[0] * r + m[1] * g + m[2] * b,
        m[3] * r + m[4] * g + m[5] * b,
        m[6] * r + m[7] * g + m[8] * b,
    )

    @Test
    fun identityAtD50() {
        val m = CreativeWhiteBalance.adaptD50ToCct(5003.0)
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in 0..8) assertEquals("entry $i", id[i], m[i], 1e-4f)
    }

    @Test
    fun warmsToTungsten() {
        // Balancing the D50 input down to a tungsten stock's ~2856 K reference warms a neutral surface:
        // its ProPhoto coordinates come out R > G > B (an amber/85-filter shift).
        val c = apply(CreativeWhiteBalance.adaptD50ToCct(2856.0), 1f, 1f, 1f)
        assertTrue("R should exceed B (warm): ${c.toList()}", c[0] > c[2])
        assertTrue("R should exceed G: ${c.toList()}", c[0] > c[1])
        assertTrue("G should exceed B: ${c.toList()}", c[1] > c[2])
        assertTrue("warm shift should be strong: R/B=${c[0] / c[2]}", c[0] / c[2] > 1.2f)
    }

    @Test
    fun coolsToD65() {
        // A cooler-than-D50 target (D65) tilts a neutral the other way: B > R.
        val c = apply(CreativeWhiteBalance.adaptD50ToCct(6504.0), 1f, 1f, 1f)
        assertTrue("B should exceed R (cool): ${c.toList()}", c[2] > c[0])
    }

    @Test
    fun d55IsSmallAndCool() {
        // Daylight stocks (D55, 5503 K) sit only ~500 K from the D50 working white — D55 is in fact a hair
        // COOLER than D50, so a neutral tilts a little toward blue (B>R), and only modestly. This is much
        // weaker than the tungsten warm, which is why the app treats daylight stocks as a no-op
        // (isMeaningful is false for them) rather than nudging an already-neutral render.
        val c = apply(CreativeWhiteBalance.adaptD50ToCct(5503.0), 1f, 1f, 1f)
        assertTrue("D55 should tilt cool (B>R): ${c.toList()}", c[2] > c[0])
        assertTrue("D55 tilt should stay modest: B/R=${c[2] / c[0]}", c[2] / c[0] < 1.2f)
    }
}
