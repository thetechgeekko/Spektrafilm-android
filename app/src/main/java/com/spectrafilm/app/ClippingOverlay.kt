/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Clipping warnings on the preview: pixels pinned at the top of the output scale are tinted
 * warm, pixels pinned at the bottom cool, so "is the print blowing out the sky?" is answered on
 * the photo itself instead of by reading the histogram's corner wedges.
 *
 * Two ways in, both driving EditorViewerUiState:
 *  - the clipping button in the canvas row (a latched toggle), and
 *  - a second finger resting on any slider while it is being adjusted (a momentary peek that
 *    ends when that finger lifts). The gesture is the fast path; the button is the one that
 *    does not need to be discovered.
 *
 * The mask is computed from the SAME preview bitmap the viewer draws, under the same read lease
 * the histogram takes, so it costs no render and cannot outlive a retired frame. It is drawn
 * through ZoomableImage's own transform, so it tracks zoom and pan.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Every channel at the top of the scale: nothing left to print. */
internal const val CLIP_HIGH_ALL: Int = 0xF0FF2D2D.toInt()

/** At least one channel (but not all) at the top: a hue is being lost. */
internal const val CLIP_HIGH_SOME: Int = 0x99FF8A3D.toInt()

/** Every channel at zero: pure black, no detail left. */
internal const val CLIP_LOW_ALL: Int = 0xF02D6BFF.toInt()

/** At least one channel (but not all) at zero. */
internal const val CLIP_LOW_SOME: Int = 0x993DC8FF.toInt()

/**
 * Classify each packed ARGB [pixels] entry into [out]: one of the four CLIP_* colours, or 0
 * (fully transparent) where nothing is clipped. Highlights win when a pixel is both (one
 * channel at 255 and another at 0): the blown channel is the one a print cannot recover.
 *
 * The thresholds match the histogram's clipped bins (0 and 255), so the overlay lights exactly
 * where the wedges count.
 */
internal fun classifyClipping(pixels: IntArray, out: IntArray) {
    require(out.size >= pixels.size) { "clip mask output too small" }
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val hi = (if (r == 255) 1 else 0) + (if (g == 255) 1 else 0) + (if (b == 255) 1 else 0)
        val lo = (if (r == 0) 1 else 0) + (if (g == 0) 1 else 0) + (if (b == 0) 1 else 0)
        out[i] = when {
            hi == 3 -> CLIP_HIGH_ALL
            hi > 0 -> CLIP_HIGH_SOME
            lo == 3 -> CLIP_LOW_ALL
            lo > 0 -> CLIP_LOW_SOME
            else -> 0
        }
    }
}

/**
 * Build a same-size ARGB mask of [bmp]'s clipped pixels, or null when the frame was already
 * retired. Runs entirely on [default] and holds a preview read lease while it reads pixels.
 */
internal suspend fun computeClipMask(bmp: Bitmap, default: CoroutineDispatcher): Bitmap? =
    withContext(default) {
        val lease = acquirePreviewLease(bmp) ?: return@withContext null
        val w: Int
        val h: Int
        val mask: IntArray
        lease.use {
            w = it.value.width
            h = it.value.height
            mask = IntArray(w * h)
            val row = IntArray(w)
            val outRow = IntArray(w)
            for (y in 0 until h) {
                it.value.getPixels(row, 0, w, 0, y, w, 1)
                classifyClipping(row, outRow)
                System.arraycopy(outRow, 0, mask, y * w, w)
            }
        }
        Bitmap.createBitmap(mask, w, h, Bitmap.Config.ARGB_8888)
    }

/**
 * The clip mask for [bitmap] while [enabled], else null. Keyed by bitmap identity, so a new
 * frame drops the stale mask at once rather than painting the previous frame's clipping over
 * the new one; the mask for the new frame follows a few milliseconds later.
 */
@Composable
internal fun rememberClipMask(bitmap: Bitmap?, enabled: Boolean): Bitmap? {
    var mask by remember(bitmap, enabled) { mutableStateOf<Bitmap?>(null) }
    val dispatchers = rememberAppDispatchers()
    LaunchedEffect(bitmap, enabled) {
        if (!enabled || bitmap == null) return@LaunchedEffect
        mask = try {
            computeClipMask(bitmap, dispatchers.default)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Diag.w("clipping overlay unavailable: ${failure.message}")
            null
        }
    }
    return mask
}
