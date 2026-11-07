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
        publish: (Bitmap) -> Unit,
    ) {
        val startNs = System.nanoTime()
        val embedded = withContext(io) { readEmbeddedThumbnail(ctx, uri, userRotation) }
        if (embedded != null) {
            Diag.i("open placeholder exif ${embedded.width}x${embedded.height} ms=${elapsedMs(startNs)}")
            publish(embedded)
            if (max(embedded.width, embedded.height) >= GOOD_ENOUGH_EDGE_PX) return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val provided = loadProviderThumbnail(ctx, uri, userRotation, io) ?: return
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
                transform(thumb, placeholderTransform(orientation, userRotation))
            }
        }.getOrNull()

    /**
     * The provider thumbnail is returned upright (MediaStore applies the EXIF orientation), so
     * only the user's manual step is applied here.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun loadProviderThumbnail(
        ctx: Context,
        uri: Uri,
        userRotation: SourceRotation,
        io: CoroutineDispatcher,
    ): Bitmap? = coroutineScope {
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
                    transform(it, placeholderTransform(ExifOrientation.NONE, userRotation))
                }
            }
        } finally {
            watchdog.cancel()
        }
    }

    /** Applies [t] and the [TARGET_EDGE_PX] cap in one pass; recycles [src] if it was copied. */
    private fun transform(src: Bitmap, t: PlaceholderTransform): Bitmap {
        val longest = max(src.width, src.height)
        val scale = if (longest > TARGET_EDGE_PX) TARGET_EDGE_PX.toFloat() / longest else 1f
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
