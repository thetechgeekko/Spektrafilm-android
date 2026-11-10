/*
 * Spektrafilm for Android -- frame caption wording. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Turns a [FrameLook]'s caption choice into the line printed under the photo, from the photo's
 * own EXIF and the film stock in use. Pure and JVM-testable; [FilmBorderGeometry] places it.
 */
package com.spectrafilm.app

import androidx.exifinterface.media.ExifInterface

internal object FrameCaptions {
    /** Between the parts of a shot-info caption. */
    const val SEPARATOR = " · "

    /**
     * The caption for [look], or null when it has none or the photo lacks what it asks for.
     * [tags] are the photo's EXIF tags by name (as read for export); [stock] is the film
     * stock's display name.
     */
    fun text(look: FrameLook, tags: Map<String, String>, stock: String?): String? {
        val raw = when (look.caption) {
            CaptionKind.NONE -> null
            CaptionKind.SHOT_INFO -> shotInfo(tags)
            CaptionKind.DATE -> date(tags)
            CaptionKind.FILM_STOCK -> stock
            CaptionKind.CUSTOM -> look.captionText
        }
        return raw?.let(FrameLook::cleanCaption)?.takeIf { it.isNotEmpty() }
    }

    /** "X-T5 · 35 mm · f/2.8 · 1/250 s · ISO 400", leaving out whatever is missing. */
    fun shotInfo(tags: Map<String, String>): String? {
        val parts = listOfNotNull(
            PhotoInfoFormat.camera(tags[ExifInterface.TAG_MAKE], tags[ExifInterface.TAG_MODEL]),
            PhotoInfoFormat.focal(PhotoInfoFormat.rational(tags[ExifInterface.TAG_FOCAL_LENGTH]), null),
            PhotoInfoFormat.aperture(PhotoInfoFormat.rational(tags[ExifInterface.TAG_F_NUMBER])),
            PhotoInfoFormat.shutter(PhotoInfoFormat.rational(tags[ExifInterface.TAG_EXPOSURE_TIME])),
            PhotoInfoFormat.iso(
                tags[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY] ?: tags[ExifInterface.TAG_ISO_SPEED_RATINGS],
            ),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** The capture date as "2026-11-10" (locale-neutral, like the rest of the photo facts). */
    fun date(tags: Map<String, String>): String? {
        val taken = PhotoInfoFormat.taken(
            tags[ExifInterface.TAG_DATETIME_ORIGINAL] ?: tags[ExifInterface.TAG_DATETIME],
        ) ?: return null
        return Regex("""^\d{4}-\d{2}-\d{2}""").find(taken)?.value ?: taken
    }
}
