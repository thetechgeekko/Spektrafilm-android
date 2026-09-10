/*
 * Spektrafilm for Android — unit tests for picked-source kind detection. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards the routing that replaced the old `isRawFileName(name) || true` shortcut: positively-known
 * photo types go to the photo path; RAW/DNG and anything ambiguous stay on the RAW path.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDetectTest {

    @Test
    fun photoByExtension_isNonRaw() {
        assertTrue(isNonRawImage("photo.jpg", null))
        assertTrue(isNonRawImage("IMG_0001.JPEG", null))      // case-insensitive
        assertTrue(isNonRawImage("pic.heic", null))
        assertTrue(isNonRawImage("frame.png", "anything/else"))
    }

    @Test
    fun photoByMime_isNonRaw() {
        // Content URIs often have no usable extension; the MIME type carries it.
        assertTrue(isNonRawImage("noextension", "image/jpeg"))
        assertTrue(isNonRawImage("document:42", "image/heic"))
    }

    @Test
    fun rawAndDng_stayOnRawPath() {
        assertFalse(isNonRawImage("shot.dng", "image/x-adobe-dng"))
        assertFalse(isNonRawImage("shot.dng", "image/tiff"))  // DNG is TIFF-based
        assertFalse(isNonRawImage("shot.cr2", null))
        assertFalse(isNonRawImage("shot.nef", "image/x-nikon-nef"))
    }

    @Test
    fun ambiguous_defaultsToRawPath() {
        // Extension-less content-URI segment with no/unknown MIME must NOT be treated as a photo,
        // so a genuine DNG (the Xiaomi/MIUI case) is never misrouted.
        assertFalse(isNonRawImage("document:42", null))
        assertFalse(isNonRawImage("42", "application/octet-stream"))
        assertFalse(isNonRawImage("raw", null))
    }

    // --- detectSourceKind: the one routing decision -------------------------------------------

    @Test
    fun video_isNotMisroutedToRaw() {
        // The regression this function exists for. isNonRawImage() answers false for a clip (it is
        // not a known STILL), and the caller's default is RAW — so before detectSourceKind a picked
        // .mp4 was handed to LibRaw and failed as a corrupt RAW.
        assertFalse(isNonRawImage("clip.mp4", "video/mp4"))
        assertEquals(SourceFileKind.VIDEO, detectSourceKind("clip.mp4", "video/mp4"))
    }

    @Test
    fun videoByExtension() {
        for (n in listOf("a.mp4", "a.MOV", "a.mkv", "a.webm", "a.3gp", "a.m2ts", "a.insv")) {
            assertEquals("$n should route to VIDEO", SourceFileKind.VIDEO, detectSourceKind(n, null))
        }
    }

    @Test
    fun videoByMimeWithNoExtension() {
        // Content URIs frequently have no usable extension; the MIME type carries it.
        assertEquals(SourceFileKind.VIDEO, detectSourceKind("document:99", "video/x-matroska"))
        assertEquals(SourceFileKind.VIDEO, detectSourceKind("99", "VIDEO/MP4")) // case-insensitive
    }

    @Test
    fun mcrawWinsOverEverythingElse() {
        assertEquals(SourceFileKind.MCRAW, detectSourceKind("clip.mcraw", null))
        // .mcraw IS raw video, but it must not be mistaken for a plain video container: it has its
        // own decoder, so it must never land on the MediaCodec path.
        assertEquals(SourceFileKind.MCRAW, detectSourceKind("clip.mcraw", "video/mp4"))
    }

    @Test
    fun stillsAndAmbiguousKeepTheirOldRouting() {
        assertEquals(SourceFileKind.PHOTO, detectSourceKind("photo.jpg", null))
        assertEquals(SourceFileKind.RAW, detectSourceKind("shot.dng", "image/tiff"))
        assertEquals(SourceFileKind.RAW, detectSourceKind("shot.cr2", null))
        // Unknown must still mean RAW, not VIDEO and not PHOTO.
        assertEquals(SourceFileKind.RAW, detectSourceKind("document:42", null))
        assertEquals(SourceFileKind.RAW, detectSourceKind("42", "application/octet-stream"))
    }

    // --- classifyVideoTransfer: HDR detection --------------------------------------------------

    @Test
    fun hdrTransfersAreRecognised() {
        assertEquals(VideoTransfer.PQ, classifyVideoTransfer(COLOR_TRANSFER_ST2084))
        assertEquals(VideoTransfer.HLG, classifyVideoTransfer(COLOR_TRANSFER_HLG))
        assertTrue(classifyVideoTransfer(COLOR_TRANSFER_ST2084).isHdr())
        assertTrue(classifyVideoTransfer(COLOR_TRANSFER_HLG).isHdr())
    }

    @Test
    fun sdrTransfersAreRecognised() {
        assertEquals(VideoTransfer.SDR, classifyVideoTransfer(COLOR_TRANSFER_SDR_VIDEO))
        assertEquals(VideoTransfer.SDR, classifyVideoTransfer(COLOR_TRANSFER_SRGB))
        assertEquals(VideoTransfer.SDR, classifyVideoTransfer(COLOR_TRANSFER_LINEAR))
        assertFalse(classifyVideoTransfer(COLOR_TRANSFER_SDR_VIDEO).isHdr())
    }

    @Test
    fun missingTransferIsUnknownNotSdr() {
        // A track can omit KEY_COLOR_TRANSFER. Calling that SDR would silently tone-map an HDR clip,
        // so it must surface as UNKNOWN and be disclosed rather than guessed.
        assertEquals(VideoTransfer.UNKNOWN, classifyVideoTransfer(null))
        assertFalse(classifyVideoTransfer(null).isHdr())
        // An unrecognised value is equally not an assumption of SDR.
        assertEquals(VideoTransfer.UNKNOWN, classifyVideoTransfer(4242))
    }
}
