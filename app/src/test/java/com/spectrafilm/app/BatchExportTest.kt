/*
 * Spektrafilm for Android — batch export naming and routing. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchExportTest {

    private val ts = 1_794_000_000_000L
    private fun tokens(name: String) = ExportNameTokens(sourceName = name, film = "Portra", print = "Endura")

    @Test
    fun `a template without the photo name gets a counter so files never collide`() {
        val names = (0 until 12).map { batchExportBaseName("Trip", ts, tokens("a.jpg"), it, 12) }
        assertEquals("Trip_01", names.first())
        assertEquals("Trip_12", names.last())
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `the counter is wide enough for the batch`() {
        assertEquals("Trip_007", batchExportBaseName("Trip", ts, tokens("a.jpg"), 6, 120))
    }

    @Test
    fun `the default timestamped name is numbered too`() {
        val a = batchExportBaseName("", ts, tokens("a.jpg"), 0, 3)
        val b = batchExportBaseName("", ts, tokens("b.jpg"), 1, 3)
        assertTrue(a, a.startsWith("Spektrafilm_$ts"))
        assertFalse(a == b)
    }

    @Test
    fun `a name template already tells photos apart`() {
        assertEquals("IMG_0042_Portra", batchExportBaseName("{name}_{film}", ts, tokens("IMG_0042.dng"), 3, 10))
    }

    @Test
    fun `a single photo is named exactly like a normal export`() {
        assertEquals(
            exportBaseName("Trip", ts, tokens("a.jpg")),
            batchExportBaseName("Trip", ts, tokens("a.jpg"), 0, 1),
        )
    }

    @Test
    fun `routing matches the editor and skips motion`() {
        assertEquals(SourceKind.PHOTO, batchSourceKind("a.jpg", "image/jpeg"))
        assertEquals(SourceKind.RAW, batchSourceKind("a.dng", "image/x-adobe-dng"))
        // Extension-less SAF ids stay RAW, as in the editor (SourceDetect rule 4).
        assertEquals(SourceKind.RAW, batchSourceKind("image%3A220439", null))
        assertNull(batchSourceKind("clip.mp4", "video/mp4"))
        assertNull(batchSourceKind("take.mcraw", null))
    }

    @Test
    fun `scene-linear exports cannot be batched`() {
        fun opts(f: ExportFormat) = ExportOptions(f, 95, ExportSize.FULL, 0, "")
        assertTrue(batchSupports(opts(ExportFormat.JPEG)))
        assertTrue(batchSupports(opts(ExportFormat.TIFF)))
        assertFalse(batchSupports(opts(ExportFormat.SCENE_LINEAR_TIFF)))
    }
}
