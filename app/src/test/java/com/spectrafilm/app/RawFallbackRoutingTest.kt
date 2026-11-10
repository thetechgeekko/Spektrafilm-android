/*
 * Spektrafilm for Android — which refused RAW decodes still get a second route. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The qualified LibRaw route refuses inputs it cannot carry exactly. That refusal is correct
 * and is gated natively by test_raw_precision_contract. What this file gates is the SEPARATE
 * question of what the editor does next: a refused route is not a refused photo when a
 * decoder that does not use that route is available.
 */
package com.spectrafilm.app

import com.spectrafilm.libraw.DecodeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFallbackRoutingTest {

    @Test
    fun precisionMetadataFallsBackToThePlatformDecoder() {
        // Metadata the native route cannot model still gets the platform decoder rather than
        // a dead editor. (The original trigger, a fractional RATIONAL BlackLevel such as
        // GCam's 6406/100, is now rounded natively and no longer lands here: #271.)
        assertTrue(rawFallbackSupported(DecodeStatus.PRECISION_METADATA))
    }

    @Test
    fun theCodecsThatAlreadyFellBackStillDo() {
        assertTrue(rawFallbackSupported(DecodeStatus.DEFLATE_DNG))
        assertTrue(rawFallbackSupported(DecodeStatus.LOSSY_JPEG_DNG))
        assertTrue(rawFallbackSupported(DecodeStatus.JPEGXL_DNG))
    }

    @Test
    fun genuineDecodeFailuresStillFailRatherThanRetryingForever() {
        // These are data errors, not "this route cannot represent the input". Sending them
        // to the platform decoder would just burn a second full decode to fail again.
        assertFalse(rawFallbackSupported(DecodeStatus.UNPACK))
        assertFalse(rawFallbackSupported(DecodeStatus.NO_MEMORY))
        assertFalse(rawFallbackSupported(DecodeStatus.CANCELLED))
    }

    @Test
    fun fileUnsupportedFallsBackForTheUrisThisAppActuallyGets() {
        // This arm used to require the path to end in ".dng". A SAF document id is
        // "image:220439" and has no suffix, so the guard never fired for a picked file and
        // a DNG LibRaw merely fails to recognise lost its second route entirely.
        assertTrue(rawFallbackSupported(DecodeStatus.FILE_UNSUPPORTED))
    }

    @Test
    fun memoryRefusalOpensThePreviewButNeverTheExport() {
        // A 50 MP LinearRaw DNG is over a smaller phone's decode budget. The preview may come
        // from the platform decoder; an export must not, because that decoder produced a
        // corrupt full-resolution image from exactly such a file.
        assertTrue(rawMemoryFallbackSupported(DecodeStatus.NO_MEMORY, 1024))
        assertTrue(rawMemoryFallbackSupported(DecodeStatus.NO_MEMORY, HALF_DECODE_EDGE_THRESHOLD))
        assertFalse(rawMemoryFallbackSupported(DecodeStatus.NO_MEMORY, HALF_DECODE_EDGE_THRESHOLD + 1))
        assertFalse(rawMemoryFallbackSupported(DecodeStatus.NO_MEMORY, Int.MAX_VALUE))
        assertFalse(rawMemoryFallbackSupported(DecodeStatus.UNPACK, 1024))
    }

    @Test
    fun anExportNeverSavesAPreviewOrAHalfSizeDecodeOfARaw() {
        // A Lightroom panorama DNG the native route refused came back from the platform
        // decoder as its 1024 px embedded preview, and the export saved that at 1074x432.
        assertTrue(exportRefusesDecode(SourceKind.RAW, EXPORT_MAX_EDGE_PX, usedPlatformFallback = true, memoryDegraded = false))
        assertTrue(exportRefusesDecode(SourceKind.RAW, EXPORT_MAX_EDGE_PX, usedPlatformFallback = false, memoryDegraded = true))
        assertFalse(exportRefusesDecode(SourceKind.RAW, EXPORT_MAX_EDGE_PX, usedPlatformFallback = false, memoryDegraded = false))
    }

    @Test
    fun previewsAndOtherSourcesKeepTheirReducedDecodes() {
        // The editor may show a preview-sized or half-size decode; only an export refuses it.
        assertFalse(exportRefusesDecode(SourceKind.RAW, 2048, usedPlatformFallback = true, memoryDegraded = true))
        assertFalse(exportRefusesDecode(SourceKind.RAW, EXPORT_MAX_EDGE_PX - 1, usedPlatformFallback = true, memoryDegraded = false))
        assertFalse(exportRefusesDecode(SourceKind.PHOTO, EXPORT_MAX_EDGE_PX, usedPlatformFallback = true, memoryDegraded = true))
    }
}
