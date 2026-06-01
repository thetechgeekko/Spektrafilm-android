/*
 * Spektrafilm for Android — unit tests for the .cube LUT parser. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * CubeLut.parse is pure text->float logic (no Android, no GL), so it runs on the
 * plain JVM via `:app:testDebugUnitTest`. It parses the same .cube text the engine's
 * SpektraEngine.bakeCubeLut emits, which the GPU preview path trilinearly samples.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CubeLutTest {

    /** Minimal valid 2^3 cube with a comment, TITLE and DOMAIN lines to ignore. */
    private val cube2 = """
        # Spektrafilm look
        TITLE "demo"
        DOMAIN_MIN 0.0 0.0 0.0
        DOMAIN_MAX 1.0 1.0 1.0
        LUT_3D_SIZE 2
        0.0 0.0 0.0
        1.0 0.0 0.0
        0.0 1.0 0.0
        1.0 1.0 0.0
        0.0 0.0 1.0
        1.0 0.0 1.0
        0.0 1.0 1.0
        1.0 1.0 1.0
    """.trimIndent()

    @Test fun parsesValidCube() {
        val lut = CubeLut.parse(cube2)
        assertNotNull(lut)
        lut!!
        assertEquals(2, lut.size)
        assertEquals(2 * 2 * 2 * 3, lut.rgb.size)
        // First triple (0,0,0) and last triple (1,1,1), in file order.
        assertEquals(0f, lut.rgb[0], 0f)
        assertEquals(1f, lut.rgb[lut.rgb.size - 1], 0f)
        // Second triple is (1,0,0): index 3..5.
        assertEquals(1f, lut.rgb[3], 0f)
        assertEquals(0f, lut.rgb[4], 0f)
    }

    @Test fun toleratesBlankLinesAndWhitespace() {
        val messy = "LUT_3D_SIZE 2\n\n  0 0 0 \n1 0 0\n  0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n\n"
        val lut = CubeLut.parse(messy)
        assertNotNull(lut)
        assertEquals(24, lut!!.rgb.size)
    }

    @Test fun rejectsWrongCount() {
        // LUT_3D_SIZE 2 needs 8 triples; supply 7.
        val short = "LUT_3D_SIZE 2\n" + (1..7).joinToString("\n") { "0 0 0" }
        assertNull(CubeLut.parse(short))
    }

    @Test fun rejectsMissingSizeHeader() {
        assertNull(CubeLut.parse("0 0 0\n1 1 1\n"))
    }

    @Test fun rejectsGarbage() {
        assertNull(CubeLut.parse("not a cube file at all"))
        assertNull(CubeLut.parse(""))
    }
}
