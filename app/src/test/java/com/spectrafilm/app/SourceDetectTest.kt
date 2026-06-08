/*
 * Spektrafilm for Android — unit tests for picked-source kind detection. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards the routing that replaced the old `isRawFileName(name) || true` shortcut: positively-known
 * photo types go to the photo path; RAW/DNG and anything ambiguous stay on the RAW path.
 */
package com.spectrafilm.app

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
}
