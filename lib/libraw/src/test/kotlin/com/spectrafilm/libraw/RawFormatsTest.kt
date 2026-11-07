/*
 * Spektrafilm for Android -- the shared camera RAW format list. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.libraw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFormatsTest {
    @Test
    fun `every maker extension is lower case without a dot and listed once per maker`() {
        for (maker in RawFormats.makers) {
            assertEquals(maker.name, maker.extensions.toSet().size, maker.extensions.size)
            for (ext in maker.extensions) {
                assertEquals(ext, ext.lowercase(), ext)
                assertFalse(ext, ext.startsWith('.'))
                assertTrue(ext, RawFormats.isRawExtension(ext))
            }
        }
    }

    @Test
    fun `recognises the common and the medium-format containers in any case`() {
        for (name in listOf(
            "IMG_0001.CR3", "a.cr2", "a.crw", "DSC_1.NEF", "a.nrw", "DSC01.ARW", "a.sr2", "a.srf",
            "DSCF.RAF", "P1.ORF", "P1.RW2", "L1.RWL", "IMGP.PEF", "a.ptx", "SAM.SRW", "B.3FR",
            "B.FFF", "C.IIQ", "L.MOS", "M.MEF", "PICT.MRW", "E.ERF", "K.KDC", "K.DCR", "x.dng",
            "/sdcard/DCIM/x.raw",
        )) {
            assertTrue(name, RawFormats.isRawFileName(name))
        }
    }

    @Test
    fun `formats this build cannot decode are not offered`() {
        for (name in listOf("a.x3f", "a.gpr", "a.r3d", "a.braw", "a.ari", "a.jpg", "a.heic", "a.tif")) {
            assertFalse(name, RawFormats.isRawFileName(name))
        }
    }

    @Test
    fun `a dot that belongs to a directory or ends the name is not an extension`() {
        assertFalse(RawFormats.isRawFileName("photos.dng/IMG_0001"))
        assertFalse(RawFormats.isRawFileName("IMG_0001"))
        assertFalse(RawFormats.isRawFileName("IMG_0001."))
        assertFalse(RawFormats.isRawFileName(""))
    }

    @Test
    fun `mime types match without case or parameters, octet-stream does not`() {
        assertTrue(RawFormats.isRawMimeType("image/x-canon-cr3"))
        assertTrue(RawFormats.isRawMimeType("IMAGE/X-SONY-ARW"))
        assertTrue(RawFormats.isRawMimeType("image/x-adobe-dng; foo=bar"))
        assertFalse(RawFormats.isRawMimeType("application/octet-stream"))
        assertFalse(RawFormats.isRawMimeType("image/jpeg"))
        assertFalse(RawFormats.isRawMimeType(null))
    }

    @Test
    fun `RawDecoder name checks follow the shared list`() {
        // RawDecoder's object init loads libsfraw, so only the pure list is exercised here;
        // RawDecoder.isRawFileName delegates to it verbatim.
        assertTrue(RawFormats.extensions.containsAll(listOf("dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2")))
    }
}
