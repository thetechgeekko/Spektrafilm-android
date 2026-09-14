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
        // The real file: a well-formed uncompressed DNG 1.4 whose RATIONAL BlackLevel is
        // 6406/100 = 64.06. The native preflight refuses it above open_buffer(); before this
        // there was no second route, so the editor drew nothing and said nothing.
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
}
