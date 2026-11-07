/*
 * Spektrafilm for Android -- export soft proof. GPLv3.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.NumberFormat
import java.util.Locale

class ExportProofTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun countsExactlyThePixelsTheClippingOverlayTints() {
        val pixels = intArrayOf(
            argb(128, 128, 128),
            argb(255, 200, 10),
            argb(0, 0, 0),
            argb(1, 254, 1),
            argb(30, 0, 200),
            argb(255, 255, 255),
        )
        val mask = IntArray(pixels.size)
        classifyClipping(pixels, mask)
        assertEquals(mask.count { it != 0 }, countClipped(pixels))
        assertEquals(4, countClipped(pixels))
        // Only the first [count] are read.
        assertEquals(1, countClipped(pixels, 2))
    }

    @Test fun aClippedShareIsShownAsAtLeastATenthOfAPercent() {
        val format = NumberFormat.getPercentInstance(Locale.US).apply { maximumFractionDigits = 1 }
        assertEquals("0.1%", proofPercent(ProofStats(total = 1_000_000, clipped = 1), format))
        assertEquals("12.5%", proofPercent(ProofStats(total = 8, clipped = 1), format))
        assertEquals(0.0, ProofStats(0, 0).fraction, 0.0)
    }

    @Test fun onlyWideSpacesWarnAboutTheScreenAndAcesHasNoProof() {
        assertFalse(proofWiderThanScreen(ColorSpace.SRGB))
        assertFalse(proofWiderThanScreen(ColorSpace.LINEAR_SRGB))
        assertTrue(proofWiderThanScreen(ColorSpace.ADOBE_RGB))
        assertTrue(proofWiderThanScreen(ColorSpace.PROPHOTO))
        assertTrue(proofWiderThanScreen(ColorSpace.REC2020))
        assertFalse(proofApplies(ColorSpace.ACES2065_1))
        assertTrue(proofApplies(ColorSpace.PROPHOTO))
    }

    @Test fun onlyEightBitFilesClipLikeThePreview() {
        assertTrue(proofCountsFileClipping(ExportFormat.JPEG))
        assertTrue(proofCountsFileClipping(ExportFormat.PNG))
        for (f in listOf(ExportFormat.ULTRA_HDR, ExportFormat.TIFF, ExportFormat.PNG16, ExportFormat.TIFF32F)) {
            assertFalse(f.name, proofCountsFileClipping(f))
        }
    }
}
