/*
 * Spektrafilm for Android — preview histogram. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Histogram overlay, sampling, clip detection and drawing. Split out of Viewer.kt (#273).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineDispatcher
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * A compact, translucent histogram overlaid on the TOP EDGE of the live preview
 * (Lightroom-mobile style). Sampling and binning both run off-main under an explicit read lease.
 * Replacing the bitmap resets the remembered bins synchronously, and retirement defers recycle
 * until any already-running reader releases its lease.
 */
@Composable
fun PreviewHistogramOverlay(bitmap: Bitmap, modifier: Modifier = Modifier) {
    // Keying state by identity prevents the previous frame's bins from being displayed while the
    // new frame is sampled. LaunchedEffect cancellation also rejects any late old-frame result.
    var hist by remember(bitmap) { mutableStateOf<Histogram?>(null) }
    val dispatchers = rememberAppDispatchers()
    LaunchedEffect(bitmap) {
        hist = try {
            computeHistogram(bitmap, dispatchers.default)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Diag.w("preview histogram unavailable: ${failure.message}")
            null
        }
    }
    val h = hist ?: return
    val clipHigh = remember(h) { h.highlightsClipped() }
    val clipLow = remember(h) { h.shadowsClipped() }
    val histogramDesc = when {
        clipHigh && clipLow -> stringResource(R.string.tool_viewer_histogram_desc_clipped_both)
        clipHigh -> stringResource(R.string.tool_viewer_histogram_desc_clipped_high)
        clipLow -> stringResource(R.string.tool_viewer_histogram_desc_clipped_low)
        else -> stringResource(R.string.tool_viewer_histogram_desc)
    }
    Box(
        modifier = modifier
            .fillMaxWidth(0.6f)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SpektraBrand.scrim.copy(alpha = 0.42f))
            .semantics { contentDescription = histogramDesc },
    ) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) { drawHistogram(h, clipLow, clipHigh) }
    }
}

/** Per-channel 256-bin counts plus the per-channel maximum used for scaling. */
class Histogram(
    val r: IntArray,
    val g: IntArray,
    val b: IntArray,
    val luma: IntArray,
    val peak: Int,
)

/**
 * How much of the frame has to sit pinned at an end of the scale before it is called clipped.
 *
 * A photograph of a street lamp at night has a handful of genuinely blown pixels and is not a
 * clipped photograph; a marker that lit for those would light on almost every frame and mean
 * nothing. A thousandth of the frame is the point where it is worth knowing about.
 */
private const val CLIP_FRACTION = 0.001f

/** Is any channel pinned at the top of the scale across more than [CLIP_FRACTION] of the frame? */
internal fun Histogram.highlightsClipped(): Boolean = clipped(255)

/** ...and at the bottom. */
internal fun Histogram.shadowsClipped(): Boolean = clipped(0)

private fun Histogram.clipped(bin: Int): Boolean {
    // luma is a full-frame channel, so its bin total is the sampled pixel count.
    var total = 0L
    for (v in luma) total += v
    if (total <= 0L) return false
    val worst = maxOf(r[bin], g[bin], b[bin])
    return worst.toFloat() / total.toFloat() > CLIP_FRACTION
}

/** Bitmap-independent immutable sample set; safe to bin after its Bitmap is retired. */
internal class HistogramSamples internal constructor(pixels: IntArray) {
    internal val pixels: IntArray = pixels.copyOf()
}

/** Capture stride-decimated pixels while the caller holds a preview read lease. */
private fun sampleHistogram(bmp: Bitmap): HistogramSamples {
    val w = bmp.width
    val h = bmp.height
    val total = w.toLong() * h.toLong()
    val targetSamples = 200_000L
    val stride = max(1, (total / targetSamples).toInt())
    val sampledWidth = (w + stride - 1) / stride
    val sampledHeight = (h + stride - 1) / stride
    val samples = IntArray(sampledWidth * sampledHeight)
    val row = IntArray(w)
    var sampleIndex = 0
    var sy = 0
    while (sy < h) {
        bmp.getPixels(row, 0, w, 0, sy, w, 1)
        var sx = 0
        while (sx < w) {
            samples[sampleIndex++] = row[sx]
            sx += stride
        }
        sy += stride
    }
    check(sampleIndex == samples.size) { "histogram sample geometry mismatch" }
    return HistogramSamples(samples)
}

/**
 * Sample a bitmap (stride-decimated for speed) into 256-bin RGB + luma histograms. The entire
 * Bitmap access runs on the injected default dispatcher and is protected from deterministic recycle by a read
 * lease. A frame retired before the worker acquires it produces no histogram.
 */
suspend fun computeHistogram(bmp: Bitmap, default: CoroutineDispatcher): Histogram? = withContext(default) {
    val lease = previewBitmapReadLeases.acquire(bmp) ?: return@withContext null
    lease.use { computeHistogram(sampleHistogram(it.value)) }
}

/** Bin a Bitmap-independent sample captured by [sampleHistogram]. */
internal fun computeHistogram(samples: HistogramSamples): Histogram {
    val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val l = IntArray(256)
    for (c in samples.pixels) {
        val rr = (c shr 16) and 0xFF
        val gg = (c shr 8) and 0xFF
        val bb = c and 0xFF
        r[rr]++; g[gg]++; b[bb]++
        val y = ((rr * 54 + gg * 183 + bb * 19) shr 8).coerceIn(0, 255)
        l[y]++
    }
    var peak = 1
    for (i in 0 until 256) {
        peak = max(peak, max(max(r[i], g[i]), max(b[i], l[i])))
    }
    return Histogram(r, g, b, l, peak)
}

/**
 * [clipLow] / [clipHigh] light the corner wedges. They are computed from the SAME bins this
 * draws, so the marker is exactly as current as the histogram under it — during a drag both
 * describe the draft render, and both sharpen together on the settle pass. It cannot disagree
 * with the shape it sits on, which is the only consistency that matters here.
 */
private fun DrawScope.drawHistogram(hist: Histogram, clipLow: Boolean, clipHigh: Boolean) {
    val w = size.width
    val h = size.height
    val binW = w / 256f
    // log scale compresses the spikes so the shape is readable.
    val logPeak = kotlin.math.ln(1f + hist.peak.toFloat())
    fun y(v: Int): Float = h - (kotlin.math.ln(1f + v.toFloat()) / logPeak) * h

    fun drawChannel(data: IntArray, color: Color) {
        for (i in 0 until 256) {
            val x = i * binW
            val top = y(data[i])
            drawLine(color, Offset(x, h), Offset(x, top), strokeWidth = binW.coerceAtLeast(1f))
        }
    }
    // luma backdrop, then additive-ish RGB on top
    drawChannel(hist.luma, SpektraBrand.histLuma)
    drawChannel(hist.r, SpektraBrand.histRed)
    drawChannel(hist.g, SpektraBrand.histGreen)
    drawChannel(hist.b, SpektraBrand.histBlue)

    // Corner wedges, in the padding OUTSIDE the plot, so they cannot be mistaken for data.
    // Blown highlights are the ones that cost you a print, so that corner is the loud one.
    val wedge = h * 0.30f
    if (clipLow) {
        drawPath(
            Path().apply {
                moveTo(0f, 0f); lineTo(wedge, 0f); lineTo(0f, wedge); close()
            },
            SpektraBrand.clipShadows,
        )
    }
    if (clipHigh) {
        drawPath(
            Path().apply {
                moveTo(w, 0f); lineTo(w - wedge, 0f); lineTo(w, wedge); close()
            },
            SpektraBrand.clipHighlights,
        )
    }
}
