package com.spectrafilm.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Output sharpening's kernel and the export file-name placeholders. */
class OutputSharpeningTest {

    private fun grey(v: Int, a: Int = 0xFF) = (a shl 24) or (v shl 16) or (v shl 8) or v
    private fun luma(p: Int) = p and 0xFF

    private fun sharpen(img: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val out = img.copyOf()
        sharpenRows(
            w, h, amount,
            readRow = { y, into -> img.copyInto(into, 0, y * w, y * w + w) },
            writeRow = { y, from -> from.copyInto(out, y * w) },
        )
        return out
    }

    @Test fun aFlatImageIsUnchanged() {
        val img = IntArray(5 * 4) { grey(120) }
        assertArrayEquals(img, sharpen(img, 5, 4, 1.1f))
    }

    @Test fun anEdgeGainsContrastOnBothSides() {
        // Left half 100, right half 160: a vertical edge between x=2 and x=3.
        val w = 6
        val h = 3
        val img = IntArray(w * h) { i -> grey(if (i % w < 3) 100 else 160) }
        val out = sharpen(img, w, h, 0.7f)
        val row = 1 * w
        assertTrue("dark side darker", luma(out[row + 2]) < 100)
        assertTrue("bright side brighter", luma(out[row + 3]) > 160)
        // Far from the edge nothing moves.
        assertEquals(100, luma(out[row + 0]))
        assertEquals(160, luma(out[row + 5]))
    }

    @Test fun smallDifferencesAreLeftAlone() {
        // A one-level ripple stays under the threshold: grain-scale noise is not amplified.
        val img = IntArray(4 * 4) { i -> grey(if ((i + i / 4) % 2 == 0) 128 else 129) }
        assertArrayEquals(img, sharpen(img, 4, 4, 1.1f))
    }

    @Test fun alphaIsPreservedAndValuesClamp() {
        val img = intArrayOf(grey(0, 0x80), grey(255, 0x80), grey(0, 0x80), grey(255, 0x80))
        val out = sharpen(img, 4, 1, 1.1f)
        for (p in out) assertEquals(0x80, (p ushr 24))
        for (p in out) assertTrue(luma(p) in 0..255)
    }

    @Test fun zeroAmountAndEmptyImagesAreNoOps() {
        val img = intArrayOf(grey(10), grey(200), grey(10))
        assertArrayEquals(img, sharpen(img, 3, 1, 0f))
        sharpen(IntArray(0), 0, 0, 1f)
    }

    @Test fun highBitDepthFormatsNeverSharpen() {
        val o = ExportOptions(ExportFormat.TIFF, 95, ExportSize.FULL, 0, "", sharpen = OutputSharpen.HIGH)
        assertEquals(OutputSharpen.NONE, o.effectiveSharpen())
        val j = o.copy(format = ExportFormat.JPEG)
        assertEquals(OutputSharpen.HIGH, j.effectiveSharpen())
    }

    @Test fun placeholdersExpandAndSanitise() {
        val ts = Calendar.getInstance(TimeZone.getDefault()).apply {
            clear(); set(2026, Calendar.NOVEMBER, 7, 9, 5, 3)
        }.timeInMillis
        val tokens = ExportNameTokens(sourceName = "IMG_0042.dng", film = "Portra 400", print = "Endura")
        assertEquals(
            "IMG_0042_Portra_400_20261107_090503",
            exportBaseName("{name} {FILM} {date} {time}", ts, tokens),
        )
        assertEquals("Endura_unknown", exportBaseName("{print}_{unknown}", ts, tokens))
        // A template that expands to nothing falls back to the timestamped default.
        assertEquals("Spektrafilm_$ts", exportBaseName("{film}", ts, ExportNameTokens()))
        // No placeholders: unchanged behaviour.
        assertEquals("my_photo", exportBaseName("my photo", ts, tokens))
    }
}
