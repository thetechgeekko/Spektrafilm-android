/*
 * Spektrafilm for Android — date-back imprint text helpers. GPLv3.
 *
 * The engine draws the imprint (engine model/date_stamp.h); these helpers only
 * choose the default text a camera date back would print: the photo's capture
 * date when its EXIF has one, otherwise today. Format "'YY MM DD", the classic
 * date-back layout.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.util.Calendar
import java.util.Locale

internal object DateStampText {
    private val EXIF_DATE = Regex("""^(\d{4}):(\d{2}):(\d{2})""")

    /** "'YY MM DD" from an EXIF "YYYY:MM:DD HH:MM:SS" value, or null if it isn't one. */
    fun fromExif(value: String?): String? {
        val m = EXIF_DATE.find(value?.trim() ?: return null) ?: return null
        val (y, mo, d) = m.destructured
        if (y == "0000" || mo == "00" || d == "00") return null  // "0000:00:00" = unset
        return "'${y.substring(2)} $mo $d"
    }

    fun of(calendar: Calendar): String = String.format(
        Locale.ROOT,
        "'%02d %02d %02d",
        calendar.get(Calendar.YEAR) % 100,
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )

    fun today(): String = of(Calendar.getInstance())

    /** The capture date of [uri] from EXIF, or null. Does I/O: call off the main thread. */
    fun readCaptureDate(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            fromExif(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                ?: fromExif(exif.getAttribute(ExifInterface.TAG_DATETIME))
        }
    }.getOrNull()

    /** Keeps only characters the engine's seven-segment font draws, capped in length. */
    fun sanitize(input: String): String =
        input.filter { it in '0'..'9' || it in " '-.:/" }
            .take(OperationalParamLimits.DATE_STAMP_TEXT_MAX)
}
