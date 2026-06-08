/*
 * Spektrafilm for Android — unit tests for the export-options model (export sheet §6a/§6b). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pins the resize maths (preserve aspect, never enlarge), the format-aware target long-edge (16-bit
 * always full-res, custom clamped) and the filename sanitiser.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportOptionsTest {

    private fun opts(
        format: ExportFormat = ExportFormat.JPEG,
        size: ExportSize = ExportSize.FULL,
        custom: Int = 2048,
    ) = ExportOptions(format, jpegQuality = 90, size = size, customLongEdge = custom, customName = "")

    @Test
    fun scaledDimensions_doesNotEnlarge() {
        assertEquals(1000 to 800, scaledDimensions(1000, 800, 2000))
        assertEquals(1000 to 800, scaledDimensions(1000, 800, 1000)) // exactly fits
    }

    @Test
    fun scaledDimensions_downscalesPreservingAspect() {
        assertEquals(2000 to 1500, scaledDimensions(4000, 3000, 2000))
        assertEquals(1500 to 2000, scaledDimensions(3000, 4000, 2000)) // portrait: long edge is height
    }

    @Test
    fun scaledDimensions_neverGoesBelowOne() {
        assertEquals(2 to 1, scaledDimensions(4000, 2, 2))
    }

    @Test
    fun targetLongEdge_bitmapFormats() {
        assertNull(opts(size = ExportSize.FULL).targetLongEdge())
        assertEquals(2048, opts(size = ExportSize.MEDIUM).targetLongEdge())
        assertEquals(3000, opts(size = ExportSize.CUSTOM, custom = 3000).targetLongEdge())
    }

    @Test
    fun targetLongEdge_customIsClamped() {
        assertEquals(ExportOptions.MIN_CUSTOM_EDGE, opts(size = ExportSize.CUSTOM, custom = 10).targetLongEdge())
        assertEquals(ExportOptions.MAX_CUSTOM_EDGE, opts(size = ExportSize.CUSTOM, custom = 999_999).targetLongEdge())
    }

    @Test
    fun targetLongEdge_16BitAlwaysFullRes() {
        assertNull(opts(format = ExportFormat.TIFF, size = ExportSize.MEDIUM).targetLongEdge())
        assertNull(opts(format = ExportFormat.PNG16, size = ExportSize.CUSTOM, custom = 1000).targetLongEdge())
    }

    @Test
    fun exportBaseName_defaultsWhenBlankOrFullyStripped() {
        assertEquals("Spektrafilm_123", exportBaseName("", 123))
        assertEquals("Spektrafilm_123", exportBaseName("   ", 123))
        assertEquals("Spektrafilm_123", exportBaseName("***", 123))
    }

    @Test
    fun exportBaseName_sanitisesToAPortableName() {
        assertEquals("My_Photo", exportBaseName("My Photo!", 1))
        assertEquals("Roll_12_frame", exportBaseName("Roll 12 — frame", 1))
        assertEquals("abc", exportBaseName("a/b\\c", 1))
        assertEquals("keep-this_1", exportBaseName("keep-this_1", 1))
    }
}
