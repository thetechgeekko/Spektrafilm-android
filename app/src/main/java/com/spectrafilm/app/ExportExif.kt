/*
 * Spektrafilm for Android — export EXIF. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Which source EXIF tags an export carries (GPS only on request) and how they are written.
 * Split out of ImagePipeline.kt (#273).
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * Comprehensive list of standard ExifInterface TAG_* attributes copied verbatim from the
 * source image into the export. Deliberately includes GPS/location (the user wants a FULL
 * copy). Tags that MUST reflect the exported (rendered) image rather than the source —
 * orientation, software, and the width/height/pixel-dimension family — are NOT in this list;
 * they are written explicitly as overrides in [applySourceExif] AFTER this bulk copy.
 */
private val EXIF_COPY_TAGS: List<String> = listOf(
    // --- Camera / image description ---
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_IMAGE_DESCRIPTION,
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_USER_COMMENT,
    ExifInterface.TAG_X_RESOLUTION,
    ExifInterface.TAG_Y_RESOLUTION,
    ExifInterface.TAG_RESOLUTION_UNIT,
    ExifInterface.TAG_BODY_SERIAL_NUMBER,
    ExifInterface.TAG_CAMERA_OWNER_NAME,
    // --- Lens ---
    ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_LENS_SERIAL_NUMBER,
    ExifInterface.TAG_LENS_SPECIFICATION,
    // --- Exposure ---
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_SPECTRAL_SENSITIVITY,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_ISO_SPEED,
    ExifInterface.TAG_SENSITIVITY_TYPE,
    ExifInterface.TAG_OECF,
    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
    ExifInterface.TAG_APERTURE_VALUE,
    ExifInterface.TAG_BRIGHTNESS_VALUE,
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    ExifInterface.TAG_MAX_APERTURE_VALUE,
    ExifInterface.TAG_SUBJECT_DISTANCE,
    ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_LIGHT_SOURCE,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FLASH_ENERGY,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_EXPOSURE_MODE,
    ExifInterface.TAG_EXPOSURE_INDEX,
    ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    ExifInterface.TAG_GAIN_CONTROL,
    ExifInterface.TAG_CONTRAST,
    ExifInterface.TAG_SATURATION,
    ExifInterface.TAG_SHARPNESS,
    ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
    ExifInterface.TAG_SENSING_METHOD,
    ExifInterface.TAG_FILE_SOURCE,
    ExifInterface.TAG_SCENE_TYPE,
    ExifInterface.TAG_CUSTOM_RENDERED,
    ExifInterface.TAG_SUBJECT_AREA,
    ExifInterface.TAG_SUBJECT_LOCATION,
    // --- White balance / colour ---
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_COLOR_SPACE,
    ExifInterface.TAG_WHITE_POINT,
    ExifInterface.TAG_PRIMARY_CHROMATICITIES,
    ExifInterface.TAG_COMPONENTS_CONFIGURATION,
    // --- Date / time ---
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    // --- EXIF / Interop versions ---
    ExifInterface.TAG_EXIF_VERSION,
    ExifInterface.TAG_FLASHPIX_VERSION,
    ExifInterface.TAG_MAKER_NOTE,
    ExifInterface.TAG_IMAGE_UNIQUE_ID,
)

/**
 * GPS / location EXIF tags, kept SEPARATE from [EXIF_COPY_TAGS] so they can be
 * gated behind the "preserve location on export" setting (default OFF — strip).
 * Security review F3: a photo app must not silently bake location into shared files.
 */
private val EXIF_GPS_TAGS: List<String> = listOf(
    ExifInterface.TAG_GPS_VERSION_ID,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_SATELLITES,
    ExifInterface.TAG_GPS_STATUS,
    ExifInterface.TAG_GPS_MEASURE_MODE,
    ExifInterface.TAG_GPS_DOP,
    ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_SPEED,
    ExifInterface.TAG_GPS_TRACK_REF,
    ExifInterface.TAG_GPS_TRACK,
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_IMG_DIRECTION,
    ExifInterface.TAG_GPS_MAP_DATUM,
    ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LATITUDE,
    ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LONGITUDE,
    ExifInterface.TAG_GPS_DEST_BEARING_REF,
    ExifInterface.TAG_GPS_DEST_BEARING,
    ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
    ExifInterface.TAG_GPS_DEST_DISTANCE,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_GPS_AREA_INFORMATION,
    ExifInterface.TAG_GPS_DIFFERENTIAL,
    ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
)

/**
 * A snapshot of the source image's standard EXIF tags, captured from the source URI before
 * export so it can be re-applied to the exported JPEG. [tags] maps tag name -> attribute
 * string for every non-null tag in [EXIF_COPY_TAGS]. Empty when the source has no readable
 * EXIF (e.g. the synthetic demo image, or a source whose EXIF cannot be parsed).
 */
class SourceExif(val tags: Map<String, String>) {
    val isEmpty: Boolean get() = tags.isEmpty()
}

/**
 * Read all standard [EXIF_COPY_TAGS] from [sourceUri] (via the content resolver). Returns an
 * empty [SourceExif] if the URI is null, has no EXIF, or cannot be parsed — never throws.
 *
 * With [keepGps], a MediaStore-backed source on Android 10+ is read through its original-media
 * form (`MediaStore.setRequireOriginal`), because MediaStore otherwise hands back a stream with
 * the location bytes redacted and the export silently loses GPS (#261). That read needs
 * `ACCESS_MEDIA_LOCATION`; if it fails for ANY reason — the permission is not held, or the
 * provider rejects the query parameter — the plain read below is the fallback, so the worst case
 * is an export without GPS, never an export without EXIF at all.
 */
fun readSourceExif(ctx: Context, sourceUri: Uri?, keepGps: Boolean = false): SourceExif {
    if (sourceUri == null) return SourceExif(emptyMap())
    fun readFromOrThrow(uri: Uri): SourceExif =
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val map = HashMap<String, String>()
            // GPS/location is only captured when the user has opted in (default OFF);
            // never reading it keeps location out of the export entirely. (F3.)
            val tags = if (keepGps) EXIF_COPY_TAGS + EXIF_GPS_TAGS else EXIF_COPY_TAGS
            for (tag in tags) {
                exif.getAttribute(tag)?.let { map[tag] = it }
            }
            SourceExif(map)
        } ?: SourceExif(emptyMap())
    fun readFrom(uri: Uri): SourceExif =
        runCatching { readFromOrThrow(uri) }.getOrDefault(SourceExif(emptyMap()))
    if (!keepGps) return readFrom(sourceUri)
    // A shared photo's working copy (#508) was read through a location-redacted stream; its
    // original, while still granted, is where the GPS lives.
    val metadataUri = SharedSourceCopies.originOf(sourceUri) ?: sourceUri
    val originalUri = originalMediaUriForGpsMetadata(metadataUri) ?: metadataUri
    if (originalUri == sourceUri) return readFrom(sourceUri)
    return runCatching { readFromOrThrow(originalUri) }.getOrElse { readFrom(sourceUri) }
}

/**
 * Apply the captured [source] EXIF to the [dest] ExifInterface (opened on the exported JPEG),
 * then write the Spektrafilm overrides and call saveAttributes(). Overrides written AFTER the
 * bulk copy so they win:
 *   - TAG_ORIENTATION = ORIENTATION_NORMAL (1): Spektrafilm bakes rotation/orientation into the
 *     exported pixels (loadSource applies manual rotation), so viewers must not re-rotate.
 *   - TAG_SOFTWARE = "Spektrafilm".
 *   - width / height / pixel-x/y dimensions = the EXPORTED dimensions (the render may be
 *     cropped/resized/rotated vs the source).
 * Works for an empty [source] too: only the overrides are written, which is the desired
 * behaviour for the demo image / EXIF-less sources.
 */
private fun applySourceExif(
    dest: ExifInterface,
    source: SourceExif,
    outW: Int,
    outH: Int,
    outputColorSpace: OutputExifColorSpace,
) {
    for ((tag, value) in source.tags) {
        runCatching { dest.setAttribute(tag, value) }
    }
    dest.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    dest.setAttribute(ExifInterface.TAG_SOFTWARE, "Spektrafilm")
    dest.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, outW.toString())
    dest.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, outH.toString())
    dest.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, outW.toString())
    dest.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, outH.toString())
    // The source tag describes the camera file, not these rendered samples. Always override it.
    dest.setAttribute(ExifInterface.TAG_COLOR_SPACE, outputColorSpace.value.toString())
    dest.saveAttributes()
}

/** Apply [source] EXIF + Spektrafilm overrides to an exported JPEG at filesystem [path]. */
internal fun writeExifToPath(
    path: String,
    source: SourceExif,
    outW: Int,
    outH: Int,
    outputColorSpace: OutputExifColorSpace,
) {
    applySourceExif(ExifInterface(path), source, outW, outH, outputColorSpace)
}
