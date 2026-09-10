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

/**
 * Container extensions that positively identify a *motion* clip. Kept separate from the still sets
 * because the RAW picker is launched with an accept-anything filter (MainActivity.kt,
 * `rawPicker.launch(...)`), so a user can and does pick a video with it.
 *
 * `.mcraw` is deliberately NOT here: it is RAW video, but it is routed by its own kind so the RAW
 * decoder and the mcraw decoder never share a branch.
 */
private val VIDEO_EXTENSIONS =
    setOf("mp4", "m4v", "mov", "qt", "mkv", "webm", "3gp", "3gpp", "avi", "ts", "mts", "m2ts", "insv")

/** How a picked file must be routed. Exhaustive: every picked URI is exactly one of these. */
internal enum class SourceFileKind { PHOTO, RAW, VIDEO, MCRAW }

/**
 * The single routing decision for a picked/shared file, in priority order:
 *
 *  1. `.mcraw` — MotionCam RAW video, its own container and its own decoder.
 *  2. video — by extension or by a `video/` MIME type.
 *  3. positively-known still photo — [isNonRawImage].
 *  4. everything else, including anything ambiguous — RAW.
 *
 * Rule 4 is the pre-existing deliberate default (a genuine DNG arrives as an extension-less content
 * URI reported as `image/tiff`, so unknown must mean RAW). Video is checked BEFORE it precisely
 * because that default would otherwise swallow clips: `isNonRawImage("clip.mp4", "video/mp4")` is
 * false, so before this function a picked video was handed to LibRaw and failed as a corrupt RAW.
 */
internal fun detectSourceKind(name: String, mimeType: String?): SourceFileKind {
    if (McrawContainer.isMcrawFileName(name)) return SourceFileKind.MCRAW
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    val mime = mimeType?.lowercase(Locale.ROOT)
    if (ext in VIDEO_EXTENSIONS || mime?.startsWith("video/") == true) return SourceFileKind.VIDEO
    return if (isNonRawImage(name, mimeType)) SourceFileKind.PHOTO else SourceFileKind.RAW
}

/**
 * The transfer function an imported clip is encoded with. Only [HLG] and [PQ] are HDR.
 *
 * [UNKNOWN] is not "SDR": a track can omit `KEY_COLOR_TRANSFER` entirely, and treating a missing
 * key as SDR would silently tone-map an HDR clip. Callers must disclose unknown as unknown.
 */
internal enum class VideoTransfer { SDR, HLG, PQ, UNKNOWN }

// android.media.MediaFormat COLOR_TRANSFER_* / COLOR_STANDARD_* values, inlined so this file stays
// unit-testable on the plain JVM (the android.jar stub throws "not mocked" for these).
internal const val COLOR_TRANSFER_LINEAR = 1
internal const val COLOR_TRANSFER_SRGB = 2
internal const val COLOR_TRANSFER_SDR_VIDEO = 3
internal const val COLOR_TRANSFER_ST2084 = 6
internal const val COLOR_TRANSFER_HLG = 7

/**
 * Classify a decoded track's transfer function from its `MediaFormat` `KEY_COLOR_TRANSFER`.
 *
 * That key is authoritative when present. When it is absent the format carries no honest answer, so
 * this returns [VideoTransfer.UNKNOWN] rather than assuming SDR.
 *
 * `KEY_COLOR_STANDARD` is deliberately NOT consulted: BT.2020 primaries alone do not imply an HDR
 * transfer (BT.2020 SDR clips exist), so inferring PQ from primaries would be exactly the silent
 * mis-tone-map this function exists to prevent.
 */
internal fun classifyVideoTransfer(colorTransfer: Int?): VideoTransfer =
    when (colorTransfer) {
        null -> VideoTransfer.UNKNOWN
        COLOR_TRANSFER_ST2084 -> VideoTransfer.PQ
        COLOR_TRANSFER_HLG -> VideoTransfer.HLG
        COLOR_TRANSFER_SDR_VIDEO, COLOR_TRANSFER_SRGB, COLOR_TRANSFER_LINEAR -> VideoTransfer.SDR
        else -> VideoTransfer.UNKNOWN
    }

/** True for the two transfers that carry HDR. [VideoTransfer.UNKNOWN] is not treated as HDR. */
internal fun VideoTransfer.isHdr(): Boolean =
    this == VideoTransfer.HLG || this == VideoTransfer.PQ
