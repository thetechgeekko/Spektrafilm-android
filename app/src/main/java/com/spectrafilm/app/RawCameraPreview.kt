/*
 * Spektrafilm for Android -- the camera's own JPEG inside a RAW, for thumbnails. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Nearly every RAW carries JPEGs the camera rendered (a small thumbnail, often a ~1600 px
 * preview, sometimes a full-size one). Showing one is far cheaper than decoding the sensor
 * data, and for containers Android's ExifInterface cannot read (CR3, 3FR, IIQ, MOS, KDC ...)
 * it is the only thumbnail there is. LibRaw finds them (metadata parse only, lazy reads; see
 * lib/libraw raw_preview.h) and the platform JPEG decoder decodes them, subsampled.
 *
 * Display only: the Library grid and the open placeholder. The editor and every export still
 * decode the sensor data at full resolution through the scene-linear RAW path.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.spectrafilm.libraw.EmbeddedRawPreview
import com.spectrafilm.libraw.RawDecoder
import kotlin.math.max
import kotlin.math.roundToInt

internal object RawCameraPreview {
    /** A decoded camera JPEG in sensor orientation, and the orientation that uprights it. */
    class Loaded(val bitmap: Bitmap, val orientation: ExifOrientation)

    /**
     * The embedded JPEG of the RAW at [uri], decoded so its long edge is about [targetEdge],
     * or null when there is none (or the provider gives no seekable file). Blocking; call on an
     * IO dispatcher.
     */
    fun load(ctx: Context, uri: Uri, targetEdge: Int): Loaded? = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val preview = RawDecoder.extractEmbeddedPreview(pfd.fd, targetEdge) ?: return@use null
            val bitmap = decodeJpeg(preview.jpeg, targetEdge) ?: return@use null
            Loaded(bitmap, orientationForLibRawFlip(preview.libRawFlip))
        }
    }.getOrNull()

    private fun decodeJpeg(jpeg: ByteArray, targetEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = jpegSampleSize(bounds.outWidth, bounds.outHeight, targetEdge)
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
        val (w, h) = scaledSize(decoded.width, decoded.height, targetEdge)
        if (w == decoded.width && h == decoded.height) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}

/**
 * Largest power-of-two JPEG subsample that keeps the long edge at or above [targetEdge] (the
 * decoder then hands back between 1x and 2x the target, never less).
 */
internal fun jpegSampleSize(width: Int, height: Int, targetEdge: Int): Int {
    val longEdge = max(width, height)
    if (targetEdge <= 0 || longEdge <= targetEdge) return 1
    var sample = 1
    while (longEdge / (sample * 2) >= targetEdge) sample *= 2
    return sample
}

/** Size that brings the long edge down to [targetEdge]; never upscales. */
internal fun scaledSize(width: Int, height: Int, targetEdge: Int): Pair<Int, Int> {
    val longEdge = max(width, height)
    if (targetEdge <= 0 || longEdge <= targetEdge) return width to height
    val scale = targetEdge.toDouble() / longEdge
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/** LibRaw's frame flip as the EXIF-style orientation the rest of the app applies. */
internal fun orientationForLibRawFlip(flip: Int): ExifOrientation =
    when (EmbeddedRawPreview.clockwiseDegreesForFlip(flip)) {
        90 -> ExifOrientation(SourceRotation.CW90, false)
        180 -> ExifOrientation(SourceRotation.CW180, false)
        270 -> ExifOrientation(SourceRotation.CW270, false)
        else -> ExifOrientation.NONE
    }
