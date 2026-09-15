/*
 * Spektrafilm for Android — unit tests for the engine crop-box mapping. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * engineCropBox must cut the SAME pixels the engine's crop stage does
 * (runtime/stages/crop_resize.cpp::crop_image); it feeds the compare viewer's and the
 * press-and-hold peek's before frame (#254). Each case is hand-derived from that function's
 * arithmetic: NumPy round (half to even), the "shift back inside" overflow rule, and the
 * negative-start slice semantics for a box larger than the image.
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
    fun `a box taller than the image degenerates the way a NumPy negative-start slice does`() {
        // size 1.0 of the long side asks for 1000 rows of a 600-row image: the overflow rule sets
        // y0 = 600 - 1000 = -400, which NumPy reads as row 200; [200:600] is 400 rows. The engine
        // renders that degenerate crop, so the before frame must cut the same 400 rows.
        val box = engineCropBox(1000, 600, 0.5f to 0.5f, 1f to 1f)
        assertEquals(0, box.x0)
        assertEquals(1000, box.width)
        assertEquals(200, box.y0)
        assertEquals(400, box.height)
    }
}
