/*
 * Spektrafilm for Android — unit tests for the eyedropper sampling math. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pins the normalized-tap → pixel-index mapping (clamped) and the ARGB → 0..1 decode, so the eyedropper
 * samples the right pixel and feeds the color-range gate a value in its own encoded domain. (The actual
 * Bitmap.getPixel read is in the composable; only this math is unit tested.)
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PixelSampleTest {

    @Test
    fun pixel_mapsAndClamps() {
        assertEquals(0 to 0, PixelSample.pixel(0f, 0f, 100, 80))
        assertEquals(50 to 40, PixelSample.pixel(0.5f, 0.5f, 100, 80))
        // nx=1.0 → 100 would be out of range; clamp to the last column/row
        assertEquals(99 to 79, PixelSample.pixel(1f, 1f, 100, 80))
        // out-of-range taps clamp into the image
        assertEquals(0 to 0, PixelSample.pixel(-0.2f, -0.2f, 100, 80))
        assertEquals(99 to 79, PixelSample.pixel(1.5f, 1.5f, 100, 80))
    }

    @Test
    fun rgb01_decodesArgb() {
        val (r1, g1, b1) = PixelSample.rgb01(0xFFFF0000.toInt())   // opaque red
        assertEquals(1f, r1, 1e-6f); assertEquals(0f, g1, 1e-6f); assertEquals(0f, b1, 1e-6f)

        val (r2, g2, b2) = PixelSample.rgb01(0xFF0080FF.toInt())   // r=0, g=128, b=255
        assertEquals(0f, r2, 1e-6f)
        assertEquals(128f / 255f, g2, 1e-6f)
        assertEquals(1f, b2, 1e-6f)

        // alpha is ignored
        val (r3, _, _) = PixelSample.rgb01(0x00FFFFFF)
        assertEquals(1f, r3, 1e-6f)
    }
}
