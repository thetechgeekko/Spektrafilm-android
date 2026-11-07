/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Photo info: the read-only facts about the open source (camera, exposure, file) for the
 * editor's info sheet. Reading and formatting are kept apart from Android so the formatting
 * is tested on the plain JVM: [PhotoInfoFormat] takes a tag lookup, [readPhotoInfo] wires it
 * to ExifInterface. Location is reported as present or absent only, never as coordinates.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The sheet's three groups, in display order. */
internal enum class PhotoInfoGroup(@StringRes val labelRes: Int) {
    CAMERA(R.string.photo_info_group_camera),
    EXPOSURE(R.string.photo_info_group_exposure),
    FILE(R.string.photo_info_group_file),
}

internal enum class PhotoInfoField(val group: PhotoInfoGroup, @StringRes val labelRes: Int) {
    CAMERA(PhotoInfoGroup.CAMERA, R.string.photo_info_camera),
    LENS(PhotoInfoGroup.CAMERA, R.string.photo_info_lens),
    FOCAL_LENGTH(PhotoInfoGroup.EXPOSURE, R.string.photo_info_focal_length),
    APERTURE(PhotoInfoGroup.EXPOSURE, R.string.photo_info_aperture),
    SHUTTER(PhotoInfoGroup.EXPOSURE, R.string.photo_info_shutter),
    ISO(PhotoInfoGroup.EXPOSURE, R.string.photo_info_iso),
    EXPOSURE_BIAS(PhotoInfoGroup.EXPOSURE, R.string.photo_info_exposure_bias),
    TAKEN(PhotoInfoGroup.FILE, R.string.photo_info_taken),
    NAME(PhotoInfoGroup.FILE, R.string.photo_info_name),
    DIMENSIONS(PhotoInfoGroup.FILE, R.string.photo_info_dimensions),
    SIZE(PhotoInfoGroup.FILE, R.string.photo_info_size),
    LOCATION(PhotoInfoGroup.FILE, R.string.photo_info_location),
}

/**
 * One photo's facts. Text values are already formatted and locale-neutral; [sizeBytes] and
 * [hasLocation] stay raw so the UI formats them with the platform's localized helpers.
 */
internal data class PhotoInfo(
    val values: Map<PhotoInfoField, String>,
    val sizeBytes: Long?,
    val hasLocation: Boolean,
)

internal object PhotoInfoFormat {
    /** Builds [PhotoInfo] from an EXIF tag lookup ([tag] returns null for an absent tag). */
    fun build(
        tag: (String) -> String?,
        fileName: String?,
        sizeBytes: Long?,
        width: Int?,
        height: Int?,
        hasLocation: Boolean,
    ): PhotoInfo {
        val v = LinkedHashMap<PhotoInfoField, String>()
        camera(tag(ExifInterface.TAG_MAKE), tag(ExifInterface.TAG_MODEL))?.let { v[PhotoInfoField.CAMERA] = it }
        lens(tag(ExifInterface.TAG_LENS_MAKE), tag(ExifInterface.TAG_LENS_MODEL))?.let { v[PhotoInfoField.LENS] = it }
        focal(
            rational(tag(ExifInterface.TAG_FOCAL_LENGTH)),
            tag(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.toIntOrNull(),
        )?.let { v[PhotoInfoField.FOCAL_LENGTH] = it }
        aperture(rational(tag(ExifInterface.TAG_F_NUMBER)))?.let { v[PhotoInfoField.APERTURE] = it }
        shutter(rational(tag(ExifInterface.TAG_EXPOSURE_TIME)))?.let { v[PhotoInfoField.SHUTTER] = it }
        iso(tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))?.let { v[PhotoInfoField.ISO] = it }
        bias(rational(tag(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)))?.let { v[PhotoInfoField.EXPOSURE_BIAS] = it }
        taken(tag(ExifInterface.TAG_DATETIME_ORIGINAL) ?: tag(ExifInterface.TAG_DATETIME))
            ?.let { v[PhotoInfoField.TAKEN] = it }
        fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { v[PhotoInfoField.NAME] = it }
        if (width != null && height != null && width > 0 && height > 0) {
            v[PhotoInfoField.DIMENSIONS] = dimensions(width, height)
        }
        return PhotoInfo(v, sizeBytes?.takeIf { it >= 0 }, hasLocation)
    }

    /** "Fujifilm X-T5", without repeating the make when the model already starts with it. */
    fun camera(make: String?, model: String?): String? {
        val mk = make?.trim().orEmpty()
        val md = model?.trim().orEmpty()
        return when {
            md.isEmpty() -> mk.ifEmpty { null }
            mk.isEmpty() || md.startsWith(mk, ignoreCase = true) -> md
            // Makers spell themselves differently in the two tags ("NIKON CORPORATION" / "NIKON Z 6").
            md.startsWith(mk.substringBefore(' '), ignoreCase = true) -> md
            else -> "$mk $md"
        }
    }

    fun lens(make: String?, model: String?): String? = camera(make, model)

    /** EXIF RATIONAL "num/den" or a plain decimal. */
    fun rational(raw: String?): Double? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val slash = s.indexOf('/')
        val value = if (slash < 0) {
            s.toDoubleOrNull()
        } else {
            val num = s.substring(0, slash).toDoubleOrNull()
            val den = s.substring(slash + 1).toDoubleOrNull()
            if (num == null || den == null || den == 0.0) null else num / den
        }
        return value?.takeIf { it.isFinite() }
    }

    fun focal(mm: Double?, eq35: Int?): String? {
        if (mm == null || mm <= 0) return null
        val base = "${trim(mm)} mm"
        return if (eq35 != null && eq35 > 0 && abs(eq35 - mm) >= 1) "$base ($eq35 mm eq.)" else base
    }

    fun aperture(f: Double?): String? =
        f?.takeIf { it > 0 }?.let { "f/" + String.format(Locale.ROOT, if (it < 10) "%.1f" else "%.0f", it) }

    /** "1/250 s" below a third of a second, otherwise seconds ("0.5 s", "30 s"). */
    fun shutter(seconds: Double?): String? {
        val s = seconds?.takeIf { it > 0 } ?: return null
        return if (s < 0.3) {
            "1/${(1.0 / s).roundToLong()} s"
        } else {
            "${trim(s)} s"
        }
    }

    fun iso(raw: String?): String? =
        raw?.trim()?.split(',')?.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { "ISO $it" }

    /** "+0.7 EV"; a zero bias is left out, it says nothing. */
    fun bias(ev: Double?): String? {
        val e = ev ?: return null
        val tenths = (e * 10).roundToInt()
        if (tenths == 0) return null
        val sign = if (tenths > 0) "+" else "−"
        return sign + String.format(Locale.ROOT, "%.1f", abs(tenths) / 10.0) + " EV"
    }

    /** EXIF "yyyy:MM:dd HH:mm:ss" shown as "yyyy-MM-dd HH:mm"; anything else verbatim. */
    fun taken(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("0000") } ?: return null
        val m = Regex("""^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2})""").find(s) ?: return s
        val (y, mo, d, h, mi) = m.destructured
        return "$y-$mo-$d $h:$mi"
    }

    /** "6240 × 4160 (26.0 MP)". */
    fun dimensions(w: Int, h: Int): String {
        val mp = w.toLong() * h / 1_000_000.0
        return "$w × $h (" + String.format(Locale.ROOT, "%.1f", mp) + " MP)"
    }

    private fun trim(x: Double): String =
        if (abs(x - x.roundToLong()) < 0.05) x.roundToLong().toString()
        else String.format(Locale.ROOT, "%.1f", x)
}

/**
 * Reads [uri]'s EXIF and file facts. Blocking IO: call it off the main thread. Missing or
 * unreadable EXIF yields only what the provider knows (name, size) plus the decoded size.
 */
internal fun readPhotoInfo(
    context: Context,
    uri: Uri,
    fallbackName: String?,
    decodedWidth: Int?,
    decodedHeight: Int?,
): PhotoInfo {
    val resolver = context.contentResolver
    var name: String? = fallbackName
    var size: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }
                        ?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }
                        ?.let { size = c.getLong(it) }
                }
            }
    }
    val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
    val exifWidth = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)?.takeIf { it > 0 }
    val exifHeight = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)?.takeIf { it > 0 }
    return PhotoInfoFormat.build(
        tag = { t -> exif?.getAttribute(t) },
        fileName = name,
        sizeBytes = size,
        width = decodedWidth ?: exifWidth,
        height = decodedHeight ?: exifHeight,
        hasLocation = exif?.latLong != null,
    )
}
