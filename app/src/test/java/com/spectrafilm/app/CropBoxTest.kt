/*
 * Spektrafilm for Android — unit tests for the engine crop-box mapping. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * engineCropBox must cut the SAME pixels the engine's crop stage does
 * (runtime/stages/crop_resize.cpp::crop_image); it feeds the compare viewer's and the
 * press-and-hold peek's before frame (#254). Each case is hand-derived from that function's
 * arithmetic: NumPy round (half to even), the "shift back inside" overflow rule, and the
 * short-side clip for a box larger than the image (upstream PR #71).
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CropBoxTest {

    @Test
    fun `an in-bounds box follows the crop_image arithmetic`() {
        // 1000x600: centre (0.5, 0.5) -> (500, 300); size is of the LONG side, so (0.4, 0.3) -> 400x300;
        // top-left = centre - size/2 = (300, 150).
        assertEquals(CropBox(300, 150, 400, 300), engineCropBox(1000, 600, 0.5f to 0.5f, 0.4f to 0.3f))
    }

    @Test
    fun `rounding is half to even like NumPy, not half up`() {
        // 1001 px: half the long side is 500.5 -> 500 under rint, 501 under Math.round.
        val box = engineCropBox(1001, 1001, 0.5f to 0.5f, 0.5f to 0.5f)
        assertEquals(500, box.width)
        assertEquals(500, box.height)
        assertEquals("rint(500 - 250)", 250, box.x0)
    }

    @Test
    fun `a box past the left or top edge is shifted in, not shrunk`() {
        assertEquals(CropBox(0, 0, 400, 300), engineCropBox(1000, 600, 0.05f to 0.05f, 0.4f to 0.3f))
    }

    @Test
    fun `a box past the right or bottom edge is shifted back`() {
        // centre (950, 570), top-left (750, 420): 750+400 > 1000 -> x0 = 600; 420+300 > 600 -> y0 = 300.
        assertEquals(CropBox(600, 300, 400, 300), engineCropBox(1000, 600, 0.95f to 0.95f, 0.4f to 0.3f))
    }

    @Test
    fun `a box taller than the image is clipped to the full height`() {
        // size 1.0 of the long side asks for 1000 rows of a 600-row image. The oracle let that go
        // to a NumPy negative-start slice (rows 200..600, 400 rows); the engine now clips the
        // extent to the image first (upstream PR #71), so the before frame is the whole height.
        assertEquals(CropBox(0, 0, 1000, 600), engineCropBox(1000, 600, 0.5f to 0.5f, 1f to 1f))
    }

    @Test
    fun `a full-height crop measured a few rows tall on the preview keeps the full height`() {
        // A 1:1 crop of a 6000x4000 frame chosen on a 640x427 preview asks for 427/640 of the
        // long side: round(6000 * 0.6671875) = 4003 rows of a 4000-row image. Unclipped, the
        // overflow rule set y0 = -3 and NumPy cut rows 3997..4000, a 3-row strip.
        val s = 427f / 640f
        val box = engineCropBox(6000, 4000, 0.5f to 0.5f, s to s)
        assertEquals(0, box.y0)
        assertEquals(4000, box.height)
        assertEquals(4003, box.width)
    }
}
