/*
 * Spektrafilm for Android — soft proof in the export sheet. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The editor's preview is already rendered in the export's colour space: the sheet's colour
 * space is the same ParamsState.outputColorSpace the preview renders with, and the export
 * descriptor passes it to the engine unchanged. So the preview IS the proof of the file's
 * colour. What the sheet did not say is what that proof means:
 *
 *  - how much of the picture the file clips (a channel pinned at the top or bottom of the
 *    scale: a blown highlight, a blocked shadow, or a colour outside the chosen space), counted
 *    on the same preview pixels the clipping overlay classifies, and
 *  - that a space wider than sRGB holds colours the screen may not show, so the preview draws
 *    those clipped while the file keeps them.
 *
 * The idea of a soft proof on the export page is SpektraLab's
 * (https://github.com/JamesQiu2005/SpektraLab, GPL-3.0-or-later); this is an independent,
 * much smaller implementation of it.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.spectrafilm.engine.ColorSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** Clipped pixels of one preview frame. */
internal data class ProofStats(val total: Int, val clipped: Int) {
    val fraction: Double get() = if (total == 0) 0.0 else clipped.toDouble() / total
}

/**
 * Counts packed ARGB [pixels] (the first [count]) with any channel at 0 or 255: the same
 * pixels [classifyClipping] tints, so the number and the overlay always agree.
 */
internal fun countClipped(pixels: IntArray, count: Int = pixels.size): Int {
    var clipped = 0
    for (i in 0 until count) {
        val c = pixels[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        if (r == 0 || g == 0 || b == 0 || r == 255 || g == 255 || b == 255) clipped++
    }
    return clipped
}

/**
 * Whether a proof means anything for [space]: ACES2065-1 previews are untagged (an export
 * intent, not a display space), so their pixels say nothing about the file.
 */
internal fun proofApplies(space: ColorSpace): Boolean = space != ColorSpace.ACES2065_1

/**
 * Whether the preview's clipping is the FILE's clipping: only for the 8-bit encoders, which
 * quantise and clamp exactly as the preview does. 16-bit files keep values the 8-bit preview
 * rounds to 0 or 255, a float TIFF keeps values outside [0, 1], and Ultra HDR keeps highlights
 * above the SDR base in its gain map, so for those the count would overstate the loss.
 */
internal fun proofCountsFileClipping(format: ExportFormat): Boolean =
    format == ExportFormat.JPEG || format == ExportFormat.PNG

/** A space wider than sRGB: the file can hold colours the screen may not show. */
internal fun proofWiderThanScreen(space: ColorSpace): Boolean =
    space == ColorSpace.ADOBE_RGB || space == ColorSpace.PROPHOTO || space == ColorSpace.REC2020

/** Share of [stats] clipped, for a person: at least 0.1 % once anything is. */
internal fun proofPercent(stats: ProofStats, format: NumberFormat): String =
    format.format(maxOf(stats.fraction, 0.001))

/** Counts [bmp]'s clipped pixels on [default] under a preview read lease; null when retired. */
internal suspend fun computeProofStats(bmp: Bitmap, default: CoroutineDispatcher): ProofStats? =
    withContext(default) {
        val lease = acquirePreviewLease(bmp) ?: return@withContext null
        lease.use {
            val w = it.value.width
            val h = it.value.height
            val row = IntArray(w)
            var clipped = 0
            for (y in 0 until h) {
                it.value.getPixels(row, 0, w, 0, y, w, 1)
                clipped += countClipped(row, w)
            }
            ProofStats(w * h, clipped)
        }
    }

/** The proof lines under the export sheet's colour space. */
@Composable
internal fun ExportProofSection(
    previewBitmap: Bitmap?,
    colorSpace: ColorSpace,
    spaceName: String,
    format: ExportFormat,
) {
    if (!proofApplies(colorSpace)) return
    val counts = proofCountsFileClipping(format)
    var stats by remember(previewBitmap, counts) { mutableStateOf<ProofStats?>(null) }
    val dispatchers = rememberAppDispatchers()
    LaunchedEffect(previewBitmap, counts) {
        if (!counts) return@LaunchedEffect
        val bmp = previewBitmap ?: return@LaunchedEffect
        if (bmp.isRecycled) return@LaunchedEffect
        stats = try {
            computeProofStats(bmp, dispatchers.default)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Diag.w("export proof unavailable: ${failure.message}")
            null
        }
    }
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    stats?.takeIf { counts }?.let { s ->
        val text = if (s.clipped == 0) {
            stringResource(R.string.tool_export_proof_none, spaceName)
        } else {
            val percent = remember(s) {
                NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
                    .let { proofPercent(s, it) }
            }
            stringResource(R.string.tool_export_proof_clipped, percent, spaceName)
        }
        Text(text, style = style, color = color)
    }
    if (proofWiderThanScreen(colorSpace)) {
        Text(stringResource(R.string.tool_export_proof_wide, spaceName), style = style, color = color)
    }
}
