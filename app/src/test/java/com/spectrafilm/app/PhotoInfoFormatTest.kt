package com.spectrafilm.app

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoInfoFormatTest {
    @Test fun cameraDoesNotRepeatTheMake() {
        assertEquals("Fujifilm X-T5", PhotoInfoFormat.camera("Fujifilm", "X-T5"))
        assertEquals("Canon EOS R6", PhotoInfoFormat.camera("Canon", "Canon EOS R6"))
        assertEquals("NIKON Z 6", PhotoInfoFormat.camera("NIKON CORPORATION", "NIKON Z 6"))
        assertEquals("Pixel 9", PhotoInfoFormat.camera(null, " Pixel 9 "))
        assertEquals("Leica", PhotoInfoFormat.camera("Leica", ""))
        assertNull(PhotoInfoFormat.camera(" ", null))
    }

    @Test fun rationalsParseAndRejectNonsense() {
        assertEquals(0.004, PhotoInfoFormat.rational("1/250")!!, 1e-12)
        assertEquals(2.8, PhotoInfoFormat.rational("28/10")!!, 1e-12)
        assertEquals(35.0, PhotoInfoFormat.rational("35")!!, 1e-12)
        assertNull(PhotoInfoFormat.rational("1/0"))
        assertNull(PhotoInfoFormat.rational("abc"))
        assertNull(PhotoInfoFormat.rational(""))
        assertNull(PhotoInfoFormat.rational("NaN"))
    }

    @Test fun exposureValuesReadLikeACamera() {
        assertEquals("35 mm (52 mm eq.)", PhotoInfoFormat.focal(35.0, 52))
        assertEquals("50 mm", PhotoInfoFormat.focal(50.0, 50))
        assertEquals("6.9 mm", PhotoInfoFormat.focal(6.86, null))
        assertNull(PhotoInfoFormat.focal(0.0, 24))
        assertEquals("f/2.8", PhotoInfoFormat.aperture(2.8))
        assertEquals("f/11", PhotoInfoFormat.aperture(11.0))
        assertEquals("1/250 s", PhotoInfoFormat.shutter(0.004))
        assertEquals("0.5 s", PhotoInfoFormat.shutter(0.5))
        assertEquals("30 s", PhotoInfoFormat.shutter(30.0))
        assertEquals("ISO 400", PhotoInfoFormat.iso("400"))
        assertEquals("ISO 100", PhotoInfoFormat.iso("100,0"))
        assertNull(PhotoInfoFormat.iso("0"))
        assertEquals("+0.7 EV", PhotoInfoFormat.bias(2.0 / 3))
        assertEquals("−1.0 EV", PhotoInfoFormat.bias(-1.0))
        assertNull(PhotoInfoFormat.bias(0.01))
    }

    @Test fun takenAndDimensions() {
        assertEquals("2026-11-07 09:41", PhotoInfoFormat.taken("2026:11:07 09:41:22"))
        assertNull(PhotoInfoFormat.taken("0000:00:00 00:00:00"))
        assertEquals("yesterday", PhotoInfoFormat.taken("yesterday"))
        assertEquals("6240 × 4160 (26.0 MP)", PhotoInfoFormat.dimensions(6240, 4160))
    }

    @Test fun buildKeepsOnlyWhatIsKnown() {
        val tags = mapOf(
            ExifInterface.TAG_MAKE to "Fujifilm",
            ExifInterface.TAG_MODEL to "X-T5",
            ExifInterface.TAG_F_NUMBER to "28/10",
            ExifInterface.TAG_EXPOSURE_TIME to "1/250",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "400",
        )
        val info = PhotoInfoFormat.build(tags::get, " IMG_1234.RAF ", 1234L, 6240, 4160, hasLocation = true)
        assertEquals("Fujifilm X-T5", info.values[PhotoInfoField.CAMERA])
        assertEquals("f/2.8", info.values[PhotoInfoField.APERTURE])
        assertEquals("1/250 s", info.values[PhotoInfoField.SHUTTER])
        assertEquals("ISO 400", info.values[PhotoInfoField.ISO])
        assertEquals("IMG_1234.RAF", info.values[PhotoInfoField.NAME])
        assertFalse(PhotoInfoField.LENS in info.values)
        assertFalse(PhotoInfoField.EXPOSURE_BIAS in info.values)
        assertEquals(1234L, info.sizeBytes)
        assertTrue(info.hasLocation)
    }

    @Test fun rowsGroupInOrderAndSkipEmptyGroups() {
        val info = PhotoInfoFormat.build({ null }, "a.jpg", -1L, 0, 0, hasLocation = false)
        assertNull(info.sizeBytes)
        val rows = info.rows(sizeText = { "$it B" }, locationText = { if (it) "yes" else "no" })
        assertEquals(listOf(PhotoInfoGroup.FILE), rows.map { it.first })
        assertEquals(
            listOf(PhotoInfoField.NAME to "a.jpg", PhotoInfoField.LOCATION to "no"),
            rows.single().second,
        )
    }
}
