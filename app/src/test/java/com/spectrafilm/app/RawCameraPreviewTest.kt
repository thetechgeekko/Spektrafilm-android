/*
 * Spektrafilm for Android -- embedded camera JPEG sizing and orientation. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCameraPreviewTest {
    @Test fun sampleSizeNeverDropsBelowTheTarget() {
        assertEquals(1, jpegSampleSize(320, 240, 320))
        assertEquals(1, jpegSampleSize(639, 400, 320))
        assertEquals(2, jpegSampleSize(640, 400, 320))
        assertEquals(8, jpegSampleSize(6000, 4000, 640))  // 750 px long edge
        assertEquals(4, jpegSampleSize(4000, 6000, 1024))  // portrait: 1500 px
        // Every sample leaves the long edge in [target, 2 * target).
        for (edge in listOf(321, 1000, 1620, 5616, 11664)) {
            val s = jpegSampleSize(edge, edge / 2, 320)
            val out = edge / s
            assertTrue("edge=$edge sample=$s out=$out", out >= 320 && out < 640)
        }
        assertEquals(1, jpegSampleSize(6000, 4000, 0))
    }

    @Test fun scaledSizeKeepsAspectAndNeverUpscales() {
        assertEquals(320 to 213, scaledSize(750, 500, 320))
        assertEquals(213 to 320, scaledSize(500, 750, 320))
        assertEquals(300 to 200, scaledSize(300, 200, 320))
        assertEquals(320 to 1, scaledSize(5000, 2, 320))
    }

    @Test fun libRawFlipMapsToTheSameOrientationAsExif() {
        assertEquals(ExifOrientation.NONE, orientationForLibRawFlip(0))
        assertEquals(ExifOrientation(SourceRotation.CW180, false), orientationForLibRawFlip(3))
        assertEquals(ExifOrientation(SourceRotation.CW270, false), orientationForLibRawFlip(5))
        assertEquals(ExifOrientation(SourceRotation.CW90, false), orientationForLibRawFlip(6))
        // Codes LibRaw never reports for a frame fall back to upright, not to a guess.
        assertEquals(ExifOrientation.NONE, orientationForLibRawFlip(1))
        assertEquals(ExifOrientation.NONE, orientationForLibRawFlip(-1))
    }
}
