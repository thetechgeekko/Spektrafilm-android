/*
 * Spektrafilm for Android — unit tests for the CLF (Common LUT Format) writer. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pins the CLF v3 structure, the LUT3D Array dim + ordering (samples written in CubeLut order, which is
 * CLF's blue-fastest), float formatting (locale-independent '.'), entry count, and XML escaping.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClfWriterTest {

    /** A 2³ identity-ish LUT with distinct, known samples so ordering is checkable. */
    private fun lut2(): CubeLut {
        val rgb = FloatArray(2 * 2 * 2 * 3) { it * 0.01f }  // 0.00, 0.01, 0.02, … distinct per slot
        return CubeLut(2, rgb)
    }

    @Test
    fun structure_isClfV3WithLut3d() {
        val s = ClfWriter.write(lut2(), title = "My Look")
        assertTrue(s.startsWith("<?xml version=\"1.0\""))
        assertTrue("ProcessList v3", s.contains("<ProcessList ") && s.contains("compCLFversion=\"3.0\""))
        assertTrue("named", s.contains("name=\"My Look\""))
        assertTrue("LUT3D 32f trilinear", s.contains("<LUT3D ") &&
            s.contains("interpolation=\"trilinear\"") && s.contains("inBitDepth=\"32f\"") && s.contains("outBitDepth=\"32f\""))
        assertTrue("dim N N N 3", s.contains("<Array dim=\"2 2 2 3\">"))
        assertTrue("closes", s.contains("</LUT3D>") && s.trimEnd().endsWith("</ProcessList>"))
    }

    @Test
    fun array_hasOneRowPerEntry_inCubeOrder() {
        val lut = lut2()
        val s = ClfWriter.write(lut)
        // pull the data rows between <Array ...> and </Array>
        val body = s.substringAfter("<Array").substringAfter(">").substringBefore("</Array>")
        val rows = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("one row per N³ entry", 2 * 2 * 2, rows.size)
        // first + last rows match CubeLut.rgb in order (no axis reshuffle)
        assertEquals("0.000000 0.010000 0.020000", rows.first())
        val k = (2 * 2 * 2 - 1) * 3
        assertEquals(
            "${"%.6f".format(java.util.Locale.US, lut.rgb[k])} " +
                "${"%.6f".format(java.util.Locale.US, lut.rgb[k + 1])} " +
                "%.6f".format(java.util.Locale.US, lut.rgb[k + 2]),
            rows.last(),
        )
    }

    @Test
    fun floats_useDotDecimal_regardlessOfLocale() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)  // a comma-decimal locale
            val s = ClfWriter.write(lut2())
            assertTrue("dot decimals", s.contains("0.010000"))
            assertTrue("no comma decimals", !s.contains("0,010000"))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test
    fun title_isXmlEscaped() {
        val s = ClfWriter.write(lut2(), title = "A & B <test>")
        assertTrue(s.contains("name=\"A &amp; B &lt;test&gt;\""))
        assertTrue("no raw ampersand in the name", !s.contains("name=\"A & B"))
    }
}
