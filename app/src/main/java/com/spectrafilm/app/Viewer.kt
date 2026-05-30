/*
 * SpectraFilm for Android — editor-grade preview viewer. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A set of self-contained composables that turn the flat preview Image into an
 * editor-grade viewer:
 *   - ZoomableImage: pinch-zoom + pan + double-tap fit/2x, clamped to bounds.
 *   - CompareSlider: a draggable split handle revealing the input (before) vs the
 *     rendered output (after) in the same frame.
 *   - HistogramCard: RGB + luma histogram of the rendered preview Bitmap, computed
 *     off the main thread and drawn with a Compose Canvas.
 *   - MagnifierOverlay: a full-screen 1:1 view of a real full-resolution crop that
 *     the caller renders through the engine, so dye-cloud grain truly resolves.
 *
 * No new gradle dependency: everything is built on Compose pointerInput, graphicsLayer
 * and Canvas plus the Material3 widgets already used elsewhere.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Zoom limits for [ZoomableImage]. */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f

/**
 * A pinch-zoom + pan image that fits its bitmap to the available width (ContentScale.Fit)
 * at zoom 1. Pinch to zoom up to [MAX_ZOOM]x; drag to pan while zoomed; double-tap toggles
 * between fit (1x) and 2x centred on the tap. Pan is clamped so the image cannot be dragged
 * past its own edges. [onPointPicked] reports the tapped location in normalized image
 * coordinates (0..1, 0..1) for the magnifier; it is invoked on a single tap when not zoomed
 * past fit, otherwise a single tap is ignored (the gesture is treated as a pan inspect).
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onPointPicked: ((Float, Float) -> Unit)? = null,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()

    // Clamp the pan so the (scaled) content stays within the view bounds.
    fun clampOffset(raw: Offset, s: Float): Offset {
        val maxX = max(0f, (viewSize.width * (s - 1f)) / 2f)
        val maxY = max(0f, (viewSize.height * (s - 1f)) / 2f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    // Anchor the zoom about the gesture centroid (relative to view centre),
                    // then add the pan delta; finally clamp so edges can't be over-dragged.
                    val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
                    val centroidRel = centroid - c
                    val newOffset = (offset - centroidRel) * (newScale / oldScale) + centroidRel + pan
                    scale = newScale
                    offset = clampOffset(newOffset, newScale)
                }
            }
            .pointerInput(onPointPicked) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2f
                            val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
                            scale = target
                            offset = clampOffset((c - tap) * target, target)
                        }
                    },
                    onTap = { tap ->
                        val cb = onPointPicked ?: return@detectTapGestures
                        val n = viewToImageNormalized(tap, viewSize, scale, offset, aspect)
                        if (n != null) cb(n.x, n.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = "preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

/**
 * Map a view-space tap to normalized image coordinates (0..1), accounting for the
 * ContentScale.Fit letterboxing and the current zoom/pan transform. Returns null if the
 * tap fell on the letterbox margin (outside the image).
 */
private fun viewToImageNormalized(
    tap: Offset,
    view: IntSize,
    scale: Float,
    offset: Offset,
    aspect: Float,
): Offset? {
    if (view.width == 0 || view.height == 0) return null
    // The fitted (zoom=1) image rectangle inside the view (letterboxed).
    val viewAspect = view.width.toFloat() / view.height.toFloat()
    val fitW: Float
    val fitH: Float
    if (aspect > viewAspect) {
        fitW = view.width.toFloat()
        fitH = fitW / aspect
    } else {
        fitH = view.height.toFloat()
        fitW = fitH * aspect
    }
    val cx = view.width / 2f
    val cy = view.height / 2f
    // Undo the graphicsLayer transform (scale about centre, then translate).
    val ux = (tap.x - cx - offset.x) / scale + cx
    val uy = (tap.y - cy - offset.y) / scale + cy
    // Position within the fitted image rectangle.
    val left = cx - fitW / 2f
    val top = cy - fitH / 2f
    val nx = (ux - left) / fitW
    val ny = (uy - top) / fitH
    if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
    return Offset(nx, ny)
}

/**
 * A before/after split viewer. The [after] bitmap is shown across the whole frame; the
 * [before] bitmap is clipped to the left of a draggable vertical handle, revealing the
 * input where the handle hasn't passed. Drag the handle (or anywhere in the frame) left/
 * right to wipe between the two. Both bitmaps are drawn with ContentScale.Fit so they
 * register exactly.
 */
@Composable
fun CompareSlider(
    before: Bitmap,
    after: Bitmap,
    modifier: Modifier = Modifier,
) {
    var split by remember { mutableStateOf(0.5f) }
    var width by remember { mutableStateOf(0) }
    val aspect = after.width.toFloat() / after.height.toFloat()
    val beforeImg = remember(before) { before.asImageBitmap() }
    val afterImg = remember(after) { after.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clipToBounds()
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (width > 0) split = (pos.x / width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, _, _, _ ->
                    if (width > 0) split = (centroid.x / width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // After (full frame).
        Image(afterImg, "after", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        // Before, clipped to the left of the split via a draw-phase rect clip.
        Image(
            bitmap = beforeImg,
            contentDescription = "before",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                },
        )
        // Handle + labels.
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * split
                drawLine(
                    color = Color.White,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3f,
                )
            }
            CompareTag("BEFORE", Alignment.TopStart)
            CompareTag("AFTER", Alignment.TopEnd)
        }
    }
}

@Composable
private fun CompareTag(text: String, alignment: Alignment) {
    Box(Modifier.fillMaxSize()) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(alignment)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * RGB + luma histogram computed from [bitmap] off the main thread and drawn in a small
 * card-sized Canvas. Recomputes whenever the bitmap identity changes.
 */
@Composable
fun HistogramCard(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var hist by remember { mutableStateOf<Histogram?>(null) }
    LaunchedEffect(bitmap) {
        hist = withContext(Dispatchers.Default) { computeHistogram(bitmap) }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101014)),
    ) {
        val h = hist
        if (h == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(24.dp),
                color = Color.White,
            )
        } else {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) { drawHistogram(h) }
        }
    }
}

/**
 * A compact, translucent histogram overlaid on the TOP EDGE of the live preview
 * (Lightroom-mobile style). Reuses [computeHistogram] (run off the main thread)
 * and [drawHistogram]; recomputes when the preview [bitmap] identity changes.
 *
 * The bitmap is the live preview reference — the render path replaces (never
 * recycles) it, so the background read here cannot hit a recycled buffer.
 */
@Composable
fun PreviewHistogramOverlay(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var hist by remember { mutableStateOf<Histogram?>(null) }
    LaunchedEffect(bitmap) {
        hist = withContext(Dispatchers.Default) { computeHistogram(bitmap) }
    }
    val h = hist ?: return
    Box(
        modifier = modifier
            .fillMaxWidth(0.6f)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.42f)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) { drawHistogram(h) }
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

/** Sample the bitmap (stride-decimated for speed) into 256-bin RGB + luma histograms. */
fun computeHistogram(bmp: Bitmap): Histogram {
    val w = bmp.width
    val h = bmp.height
    val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val l = IntArray(256)
    // Cap the number of sampled pixels for responsiveness on large bitmaps.
    val total = w.toLong() * h.toLong()
    val targetSamples = 200_000L
    val stride = max(1, (total / targetSamples).toInt())
    val px = IntArray(w)
    var sy = 0
    while (sy < h) {
        bmp.getPixels(px, 0, w, 0, sy, w, 1)
        var sx = 0
        while (sx < w) {
            val c = px[sx]
            val rr = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val bb = c and 0xFF
            r[rr]++; g[gg]++; b[bb]++
            val y = ((rr * 54 + gg * 183 + bb * 19) shr 8).coerceIn(0, 255)
            l[y]++
            sx += stride
        }
        sy += stride
    }
    var peak = 1
    for (i in 0 until 256) {
        peak = max(peak, max(max(r[i], g[i]), max(b[i], l[i])))
    }
    return Histogram(r, g, b, l, peak)
}

private fun DrawScope.drawHistogram(hist: Histogram) {
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
    drawChannel(hist.luma, Color(0x33FFFFFF))
    drawChannel(hist.r, Color(0x88FF4040))
    drawChannel(hist.g, Color(0x8840FF40))
    drawChannel(hist.b, Color(0x884070FF))
}

/**
 * A full-screen overlay that shows a real, full-resolution engine render of a crop, at
 * 1:1 (pixel-for-pixel) so that grain/dye-cloud structure actually resolves. While the
 * crop is being rendered, [crop] is null and a progress state is shown. The user dismisses
 * the overlay with the close button or by tapping the scrim.
 */
@Composable
fun MagnifierOverlay(
    crop: Bitmap?,
    rendering: Boolean,
    status: String,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "100% crop",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Box(
                Modifier
                    .size(320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF050505)),
                contentAlignment = Alignment.Center,
            ) {
                val c = crop
                if (c != null) {
                    // The crop is a real full-resolution engine render (~512px native), shown
                    // 1:1 — no upscale of the preview — so dye-cloud grain truly resolves.
                    Image(
                        bitmap = c.asImageBitmap(),
                        contentDescription = "100% crop",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            Text(
                status,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (rendering) {
                Text(
                    "Rendering full-resolution crop…",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onClose) {
                Text("Close", color = Color.White)
            }
        }
    }
}
