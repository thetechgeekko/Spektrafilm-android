package com.spectrafilm.app

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateStampTextTest {
    @Test
    fun fromExif_formatsCaptureDateLikeADateBack() {
        assertEquals("'26 11 07", DateStampText.fromExif("2026:11:07 17:37:09"))
        assertEquals("'99 01 31", DateStampText.fromExif(" 1999:01:31 00:00:00"))
    }

    @Test
    fun fromExif_rejectsMissingOrUnsetDates() {
        assertNull(DateStampText.fromExif(null))
        assertNull(DateStampText.fromExif(""))
        assertNull(DateStampText.fromExif("0000:00:00 00:00:00"))
        assertNull(DateStampText.fromExif("2026-11-07"))
    }

    @Test
    fun of_formatsCalendarDay() {
        val c = Calendar.getInstance().apply { clear(); set(2005, Calendar.MARCH, 4) }
        assertEquals("'05 03 04", DateStampText.of(c))
    }

    @Test
    fun sanitize_keepsOnlyDrawableCharactersAndCapsLength() {
        assertEquals("'26 11 07", DateStampText.sanitize("'26 11 07"))
        assertEquals("2026-11-0710:00", DateStampText.sanitize("2026-11-07T10:00Z"))
        assertEquals(OperationalParamLimits.DATE_STAMP_TEXT_MAX,
            DateStampText.sanitize("1".repeat(100)).length)
        // Whatever sanitize returns must pass preset validation.
        assertEquals(true, OperationalParamLimits.DATE_STAMP_TEXT.matches(DateStampText.sanitize("a/b.c:d-e'f g")))
    }
}
