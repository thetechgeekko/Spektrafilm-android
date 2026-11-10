/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The frame caption's wording, from the photo's EXIF and the film stock. */
class FrameCaptionsTest {

    private val tags = mapOf(
        ExifInterface.TAG_MAKE to "FUJIFILM",
        ExifInterface.TAG_MODEL to "X-T5",
        ExifInterface.TAG_FOCAL_LENGTH to "350/10",
        ExifInterface.TAG_F_NUMBER to "28/10",
        ExifInterface.TAG_EXPOSURE_TIME to "1/250",
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "400",
        ExifInterface.TAG_DATETIME_ORIGINAL to "2026:11:10 16:49:02",
    )

    private fun text(kind: CaptionKind, t: Map<String, String> = tags, custom: String = "", stock: String? = "Portra 400") =
        FrameCaptions.text(FrameLook(caption = kind, captionText = custom), t, stock)

    @Test
    fun `each kind words its caption`() {
        assertEquals("FUJIFILM X-T5 · 35 mm · f/2.8 · 1/250 s · ISO 400", text(CaptionKind.SHOT_INFO))
        assertEquals("2026-11-10", text(CaptionKind.DATE))
        assertEquals("Portra 400", text(CaptionKind.FILM_STOCK))
        assertEquals("Lisbon, autumn", text(CaptionKind.CUSTOM, custom = "  Lisbon,\n autumn "))
        assertNull(text(CaptionKind.NONE))
    }

    @Test
    fun `missing facts leave parts out or the caption off`() {
        assertEquals("f/2.8", text(CaptionKind.SHOT_INFO, mapOf(ExifInterface.TAG_F_NUMBER to "2.8")))
        assertNull(text(CaptionKind.SHOT_INFO, emptyMap()))
        assertNull(text(CaptionKind.DATE, mapOf(ExifInterface.TAG_DATETIME_ORIGINAL to "0000:00:00 00:00:00")))
        assertEquals("2024-01-02", text(CaptionKind.DATE, mapOf(ExifInterface.TAG_DATETIME to "2024:01:02 03:04:05")))
        assertNull(text(CaptionKind.FILM_STOCK, stock = null))
        assertNull(text(CaptionKind.CUSTOM, custom = "   "))
    }

    @Test
    fun `a custom caption is capped`() {
        assertEquals(FrameLook.CAPTION_MAX, text(CaptionKind.CUSTOM, custom = "y".repeat(500))!!.length)
    }
}
