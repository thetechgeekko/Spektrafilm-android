/*
 * Spektrafilm for Android — source-file kind detection. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Helpers for deciding how a picked file should be routed (RAW pipeline vs. the normal photo
 * decode). Kept app-side and free of native dependencies so it is unit-testable on the plain JVM
 * (unlike com.spectrafilm.libraw.RawDecoder, whose object init loads libsfraw).
 */
package com.spectrafilm.app

import java.util.Locale

/**
 * Compressed still-image types that are positively NOT camera RAW. DNG is deliberately excluded —
 * it is TIFF-based and document providers may report it as image/tiff or image/x-adobe-dng, so
 * anything tiff/x-* (and anything unknown) must stay on the RAW path.
 */
private val NON_RAW_IMAGE_EXTENSIONS =
    setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")
private val NON_RAW_IMAGE_MIME_TYPES =
    setOf("image/jpeg", "image/png", "image/heic", "image/heif", "image/webp", "image/gif", "image/bmp")

/**
 * True iff [name] (a file name / path / content-URI last segment) and/or [mimeType] positively
 * identify a non-RAW still image (JPEG/PNG/HEIC/WebP/GIF/BMP). Returns false for RAW, DNG (incl.
 * `image/tiff`), and anything ambiguous — so a genuine DNG opened through the RAW document picker
 * with an extension-less content URI is never misrouted away from the RAW pipeline.
 *
 * Used to fix the old `isRawFileName(name) || true` shortcut: a photo mistakenly chosen via the
 * "Open RAW" picker now goes to the proper photo path instead of being forced through LibRaw (which
 * fails and then falls back to a lossy display-referred decode), while RAW/ambiguous files still
 * default to the RAW path.
 */
internal fun isNonRawImage(name: String, mimeType: String?): Boolean {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    if (ext in NON_RAW_IMAGE_EXTENSIONS) return true
    val mime = mimeType?.lowercase(Locale.ROOT) ?: return false
    return mime in NON_RAW_IMAGE_MIME_TYPES
}
