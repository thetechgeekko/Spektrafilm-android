/*
 * Spektrafilm for Android — instant first pixels on open (#286). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * #286: a cheap stand-in image for the time between picking a photo and the first engine
 * render (cold, that is a full decode plus a full preview render, seconds on a RAW).
 *
 * Two sources, cheapest first:
 *  1. The file's embedded EXIF thumbnail (JPEG IFD1, or the preview a RAW/DNG carries), read
 *     with [ExifInterface] from the file descriptor. No pixel decode of the photo itself.
 *  1b. For a RAW, the camera's own embedded JPEG found by LibRaw ([RawCameraPreview]), when it
 *     is larger than step 1's (it is the only embedded image in CR3, 3FR, IIQ and the like).
 *  2. On API 29+, the provider's own thumbnail ([android.content.ContentResolver.loadThumbnail]),
 *     which for MediaStore items is usually already cached. Bounded by [PROVIDER_TIMEOUT_MS]
 *     because a provider without a cached thumbnail may decode the whole file.
 *
 * Step 2 runs only when step 1 gave nothing or something small (a JPEG IFD1 thumbnail is
 * typically 160x120), and a better result replaces the first one. The stand-in is display
 * only: it never reaches the engine, an export, or any cache.
 */
internal object OpenPlaceholder {
    /** Longest edge kept; larger embedded previews are scaled down to this. */
    const val TARGET_EDGE_PX = 1024

    /** An embedded thumbnail at least this large is good enough; skip the provider. */
    const val GOOD_ENOUGH_EDGE_PX = 480

    const val PROVIDER_TIMEOUT_MS = 1_500L

    /**
     * Load the stand-in for [uri] and hand each improvement to [publish] (called on the
     * caller's context). [userRotation] is the session's manual rotate step, so a restored
     * rotated photo does not flip orientation when the render lands.
     */
    suspend fun load(
        ctx: Context,
        uri: Uri,
        userRotation: SourceRotation,
        io: CoroutineDispatcher,
        rawSource: Boolean,
        publish: (Bitmap) -> Unit,
    ) {
        val startNs = System.nanoTime()
        val embedded = withContext(io) { readEmbeddedThumbnail(ctx, uri, userRotation) }
        if (embedded != null) {
            Diag.i("open placeholder exif ${embedded.width}x${embedded.height} ms=${elapsedMs(startNs)}")
            publish(embedded)
            if (max(embedded.width, embedded.height) >= GOOD_ENOUGH_EDGE_PX) return
        }
        if (rawSource) {
            // The camera's own JPEG inside the RAW: usually 1-2 k px and the only embedded
            // image at all in containers ExifInterface cannot parse (CR3, 3FR, IIQ ...).
            val camera = withContext(io) {
                RawCameraPreview.load(ctx, uri, TARGET_EDGE_PX)?.let {
                    applyTransform(it.bitmap, placeholderTransform(it.orientation, userRotation))
                }
            }
            if (camera != null && max(camera.width, camera.height) > max(embedded?.width ?: 0, embedded?.height ?: 0)) {
                Diag.i("open placeholder camera ${camera.width}x${camera.height} ms=${elapsedMs(startNs)}")
                publish(camera)
                if (max(camera.width, camera.height) >= GOOD_ENOUGH_EDGE_PX) return
            } else {
                camera?.recycle()
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val provided = loadProviderThumbnail(ctx, uri, userRotation, io, rawSource) ?: return
        Diag.i("open placeholder provider ${provided.width}x${provided.height} ms=${elapsedMs(startNs)}")
        publish(provided)
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

    private fun readEmbeddedThumbnail(ctx: Context, uri: Uri, userRotation: SourceRotation): Bitmap? =
        runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (!exif.hasThumbnail()) return@use null
                val thumb = exif.thumbnailBitmap ?: return@use null
                val orientation = ExifOrientation.fromExif(
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
                )
                applyTransform(thumb, placeholderTransform(orientation, userRotation))
            }
        }.getOrNull()

    /**
     * MediaStore returns JPEG thumbnails upright but, measured on the test phone (One UI, DNG
     * with orientation 90), RAW/DNG thumbnails come back in sensor orientation. So the file's
     * own orientation is read here and [providerTransform] decides whether the provider
     * already applied it.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun loadProviderThumbnail(
        ctx: Context,
        uri: Uri,
        userRotation: SourceRotation,
        io: CoroutineDispatcher,
        rawSource: Boolean,
    ): Bitmap? = coroutineScope {
        val meta = withContext(io) { readOrientationMeta(ctx, uri) }
        val signal = CancellationSignal()
        // Cancels the binder call on timeout AND when this coroutine is cancelled (the render
        // arrived, or the source changed): a child is cancelled with its parent.
        val watchdog = launch {
            try {
                delay(PROVIDER_TIMEOUT_MS)
            } finally {
                signal.cancel()
            }
        }
        try {
            runInterruptible(io) {
                runCatching {
                    ctx.contentResolver.loadThumbnail(uri, Size(TARGET_EDGE_PX, TARGET_EDGE_PX), signal)
                }.getOrNull()?.let {
                    applyTransform(
                        it,
                        providerTransform(
                            exif = meta.orientation,
                            sourceW = meta.width,
                            sourceH = meta.height,
                            thumbW = it.width,
                            thumbH = it.height,
                            rawSource = rawSource,
                            user = userRotation,
                        ),
                    )
                }
            }
        } finally {
            watchdog.cancel()
        }
    }

    private class OrientationMeta(val orientation: ExifOrientation, val width: Int, val height: Int)

    private fun readOrientationMeta(ctx: Context, uri: Uri): OrientationMeta = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            OrientationMeta(
                ExifOrientation.fromExif(
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
                ),
                exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
            )
        }
    }.getOrNull() ?: OrientationMeta(ExifOrientation.NONE, 0, 0)

    /**
     * Applies [t] and the [maxEdge] cap (the placeholder's [TARGET_EDGE_PX] unless a caller
     * such as the slideshow needs screen size) in one pass; recycles [src] if it was copied.
     */
    internal fun applyTransform(src: Bitmap, t: PlaceholderTransform, maxEdge: Int = TARGET_EDGE_PX): Bitmap {
        val longest = max(src.width, src.height)
        val scale = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
        if (t.isIdentity && scale == 1f) return src
        val m = Matrix()
        if (scale != 1f) m.postScale(scale, scale)
        if (t.flipH) m.postScale(-1f, 1f)
        if (t.degrees != 0) m.postRotate(t.degrees.toFloat())
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }
}

/** Display transform for the stand-in: mirror first, then rotate clockwise ([ExifOrientation] order). */
internal data class PlaceholderTransform(val flipH: Boolean, val degrees: Int) {
    val isIdentity: Boolean get() = !flipH && degrees == 0
}

/**
 * EXIF baseline then the user's manual step, the same order the decode applies them
 * (loadSource: [applyExif] before the [SourceRotation]).
 */
internal fun placeholderTransform(exif: ExifOrientation, user: SourceRotation): PlaceholderTransform =
    PlaceholderTransform(exif.flipH, exif.rotation.then(user).degrees)

/**
 * Transform for a provider thumbnail of a source whose stored orientation is [exif] and stored
 * (unrotated) size is [sourceW]x[sourceH].
 *
 * For a quarter turn the aspect tells whether the provider already rotated it: a thumbnail whose
 * landscape/portrait shape still matches the stored frame was not rotated, so the EXIF turn is
 * applied here. When the stored size is unknown or square, or the orientation is a half turn or a
 * mirror (which keep the aspect), the aspect cannot tell, and RAW sources get the EXIF transform
 * while other sources are trusted to be upright (MediaStore's behaviour for JPEG/HEIC).
 */
internal fun providerTransform(
    exif: ExifOrientation,
    sourceW: Int,
    sourceH: Int,
    thumbW: Int,
    thumbH: Int,
    rawSource: Boolean,
    user: SourceRotation,
): PlaceholderTransform {
    if (exif.isIdentity) return placeholderTransform(ExifOrientation.NONE, user)
    val quarterTurn = exif.rotation == SourceRotation.CW90 || exif.rotation == SourceRotation.CW270
    val aspectKnown = sourceW > 0 && sourceH > 0 && sourceW != sourceH && thumbW != thumbH
    val providerApplied = if (quarterTurn && aspectKnown) {
        (sourceW > sourceH) != (thumbW > thumbH)
    } else {
        !rawSource
    }
    return placeholderTransform(if (providerApplied) ExifOrientation.NONE else exif, user)
}
