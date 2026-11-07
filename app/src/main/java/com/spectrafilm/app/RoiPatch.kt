/*
 * Spektrafilm for Android — grid-snapped zoom (ROI) render patch. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The region a zoom (ROI) render actually covers: the visible viewport plus a margin, with its
 * edges snapped to a grid. Approach taken from rawr's film-sim viewport patch (GPL-3.0; see
 * docs/renderer.md in github.com/adityawarmanfw/rawr), adapted to our crop-and-simulate path.
 *
 * Why a margin: halation, diffusion and blur spread light across the frame, so a crop cut exactly
 * at the viewport edge lacks the light that should bleed in from just outside it and the visible
 * border renders wrong. The margin keeps that light inside the render. It also means a small pan
 * that stays within the patch needs no new render at all.
 *
 * Why the grid: the same view (or a pan of a few pixels) gives the same patch, so the reuse check
 * in [RoiPatchRecord.covers] actually hits instead of every settle producing a slightly different
 * crop. The grid step is a quarter-octave-quantized fraction of the viewport span, so it scales
 * with the zoom level.
 */
internal object RoiPatch {
    /** Margin added on each side, as a fraction of the viewport span on that axis. */
    const val MARGIN_FRACTION = 0.1f

    /** The grid step is about 1/[GRID_DIVISIONS] of the viewport span. */
    const val GRID_DIVISIONS = 8

    /** Smallest patch edge in source pixels, matching renderRoi's old crop floor. */
    const val MIN_EDGE_PX = 8

    /** Pixel box of the patch for the viewport [roi] on a [srcW]x[srcH] source. */
    fun box(roi: RoiRect, srcW: Int, srcH: Int): CropBox {
        val (x0, w) = axis(roi.cxN, roi.wN, srcW)
        val (y0, h) = axis(roi.cyN, roi.hN, srcH)
        return CropBox(x0, y0, w, h)
    }

    /** The patch [box] as a normalized rect, for the overlay to project. */
    fun rectOf(box: CropBox, srcW: Int, srcH: Int): RoiRect = RoiRect(
        cxN = (box.x0 + box.width / 2f) / srcW,
        cyN = (box.y0 + box.height / 2f) / srcH,
        wN = box.width.toFloat() / srcW,
        hN = box.height.toFloat() / srcH,
    )

    /**
     * The render scale (output px per source px) the viewport [roi] needs so its visible part
     * comes out at most [maxViewportEdgePx] on the long side, never upscaled past 1.
     */
    fun neededScale(roi: RoiRect, srcW: Int, srcH: Int, maxViewportEdgePx: Int): Float {
        val viewLong = max(roi.wN * srcW, roi.hN * srcH).coerceAtLeast(1f)
        return min(1f, maxViewportEdgePx / viewLong)
    }

    /** The previewMaxSize to render [box] at [scale], at least 1 px. */
    fun renderEdge(box: CropBox, scale: Float): Int =
        ceil(max(box.width, box.height) * scale).toInt().coerceAtLeast(1)

    /** Start and length in pixels of the patch on one axis of [size] pixels. */
    internal fun axis(centerN: Float, spanN: Float, size: Int): Pair<Int, Int> {
        if (size <= 0) return 0 to 0
        val minLen = min(MIN_EDGE_PX, size)
        val a0 = (centerN - spanN / 2f) * size
        val a1 = (centerN + spanN / 2f) * size
        val span = (a1 - a0).coerceAtLeast(1f)
        val margin = span * MARGIN_FRACTION
        val step = quantizedStep(span / GRID_DIVISIONS)
        var start = floor((a0 - margin) / step) * step
        var end = ceil((a1 + margin) / step) * step
        start = start.coerceIn(0f, size.toFloat())
        end = end.coerceIn(0f, size.toFloat())
        var s = floor(start).toInt()
        var e = ceil(end).toInt()
        if (e - s < minLen) {
            // Grow around the centre, then slide back inside the frame.
            val c = ((a0 + a1) / 2f).toInt()
            s = (c - minLen / 2).coerceIn(0, size - minLen)
            e = s + minLen
        }
        return s to (e - s)
    }

    /** [x] rounded down to a power of 2^(1/4), never below 1 px. */
    internal fun quantizedStep(x: Float): Float {
        if (x <= 1f) return 1f
        // The epsilon keeps an exact power (64 -> 2^6) from flooring to the step below.
        val q = floor(4.0 * ln(x.toDouble()) / ln(2.0) + 1e-9) / 4.0
        return 2.0.pow(q).toFloat().coerceAtLeast(1f)
    }
}

/**
 * What a published or in-flight ROI render covers: the [box] on a [srcW]x[srcH] source, at
 * [scale] output px per source px, for edit generation [renderKey].
 */
internal data class RoiPatchRecord(
    val renderKey: Int,
    val srcW: Int,
    val srcH: Int,
    val box: CropBox,
    val scale: Float,
) {
    /**
     * True when this render already shows the viewport [roi] for [currentRenderKey] at the
     * needed sharpness, so a new settle can keep it instead of rendering again. The viewport
     * must lie inside the patch's margin-free interior on any side that is not the frame edge,
     * so the light that bleeds in from outside the view is still inside the render.
     */
    fun covers(roi: RoiRect, currentRenderKey: Int, maxViewportEdgePx: Int): Boolean {
        if (renderKey != currentRenderKey || srcW <= 0 || srcH <= 0) return false
        val need = RoiPatch.neededScale(roi, srcW, srcH, maxViewportEdgePx)
        // 5% slack so float noise in the scale does not force a re-render of the same view.
        if (scale < need * 0.95f) return false
        val vx0 = (roi.cxN - roi.wN / 2f) * srcW
        val vx1 = (roi.cxN + roi.wN / 2f) * srcW
        val vy0 = (roi.cyN - roi.hN / 2f) * srcH
        val vy1 = (roi.cyN + roi.hN / 2f) * srcH
        // Half the margin is enough to keep halation's near field; requiring all of it would
        // re-render on almost every pan.
        val mx = (vx1 - vx0) * RoiPatch.MARGIN_FRACTION / 2f
        val my = (vy1 - vy0) * RoiPatch.MARGIN_FRACTION / 2f
        val bx0 = box.x0.toFloat()
        val by0 = box.y0.toFloat()
        val bx1 = (box.x0 + box.width).toFloat()
        val by1 = (box.y0 + box.height).toFloat()
        fun inside(v0: Float, v1: Float, b0: Float, b1: Float, m: Float, size: Int): Boolean {
            val lo = if (b0 <= 0f) 0f else b0 + m
            val hi = if (b1 >= size) size.toFloat() else b1 - m
            // A sub-pixel tolerance: the viewport is float, the box is whole pixels.
            return v0 >= lo - 0.5f && v1 <= hi + 0.5f
        }
        return inside(vx0, vx1, bx0, bx1, mx, srcW) && inside(vy0, vy1, by0, by1, my, srcH)
    }
}

/**
 * The ROI renders the next settle may keep instead of rendering again: the one on screen (valid
 * only while [shownOverlay] is still the published overlay, so every path that clears or
 * replaces the overlay invalidates it for free) and the one in flight (valid only while its
 * [inFlightJob] is the current, active ROI job). Touched on the main thread only.
 */
internal class RoiPatchTracker {
    var shownOverlay: Any? = null
    var shown: RoiPatchRecord? = null
    var inFlightJob: kotlinx.coroutines.Job? = null
    var inFlight: RoiPatchRecord? = null

    /** Why the settle for [roi] needs no new render, or null when it does. */
    fun reuseReason(
        roi: RoiRect,
        renderKey: Int,
        currentOverlay: Any?,
        currentJob: kotlinx.coroutines.Job?,
        maxViewportEdgePx: Int,
    ): String? {
        val s = shown
        if (s != null && currentOverlay != null && shownOverlay === currentOverlay &&
            s.covers(roi, renderKey, maxViewportEdgePx)
        ) return REUSED_SHOWN
        val f = inFlight
        val job = inFlightJob
        if (f != null && job != null && job === currentJob && job.isActive &&
            f.covers(roi, renderKey, maxViewportEdgePx)
        ) return REUSED_IN_FLIGHT
        return null
    }

    companion object {
        const val REUSED_SHOWN = "shown"
        const val REUSED_IN_FLIGHT = "in-flight"
    }
}

/**
 * Least-recently-used store of finished ROI patch renders (zoom step 3, rawr study), so panning
 * back to an area or zooming back to a level already rendered for the current edit shows it at
 * once instead of rendering again. Bounded by [maxBytes] of bitmap memory.
 *
 * Ownership: the cache owns every value it holds. A value leaving the cache (eviction, [retain]
 * or [clear]) is handed to [recycle] unless [isShown] says it is the overlay on screen right now;
 * a shown value is then recycled by whoever replaces it on screen, which must check [holds] first
 * and skip values still cached. That keeps a bitmap from being recycled while it is drawn and
 * from being recycled twice. Main thread only.
 */
internal class RoiPatchCache<T : Any>(
    private val maxBytes: Long,
    private val sizeOf: (T) -> Long,
    private val recycle: (T) -> Unit,
    private val isShown: (T) -> Boolean,
) {
    private val entries = LinkedHashMap<RoiPatchRecord, T>(16, 0.75f, true)
    // Sizes as measured at put(): a recycled bitmap may no longer report its allocation.
    private val sizes = HashMap<RoiPatchRecord, Long>()
    private var bytes = 0L

    val size: Int get() = entries.size
    val totalBytes: Long get() = bytes

    /** Adds [value] for [record]; evicts the least recently used entries over [maxBytes]. */
    fun put(record: RoiPatchRecord, value: T) {
        val size = sizeOf(value)
        if (size > maxBytes) return
        entries.remove(record)?.let { old -> drop(record, old) }
        entries[record] = value
        sizes[record] = size
        bytes += size
        val it = entries.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.value === value) continue
            it.remove()
            drop(e.key, e.value)
        }
    }

    /** The most recently used entry that shows [roi] for [renderKey] sharply enough, or null. */
    fun find(roi: RoiRect, renderKey: Int, maxViewportEdgePx: Int): Pair<RoiPatchRecord, T>? {
        val hit = entries.entries.lastOrNull { it.key.covers(roi, renderKey, maxViewportEdgePx) }
            ?: return null
        // Touch: re-read through get() so access order marks it most recent.
        val value = entries[hit.key] ?: return null
        return hit.key to value
    }

    /** True while [value] is held here (the caller must not recycle it). */
    fun holds(value: T): Boolean = entries.values.any { it === value }

    /** Drops every entry not rendered for [renderKey]. */
    fun retain(renderKey: Int) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.renderKey != renderKey) {
                it.remove()
                drop(e.key, e.value)
            }
        }
    }

    /**
     * Drops every entry. [includeShown] also recycles the one on screen: only for when the screen
     * itself is going away (the overlay's own disposal may already have skipped it as cached).
     */
    fun clear(includeShown: Boolean = false) {
        val all = entries.entries.map { it.key to it.value }
        entries.clear()
        all.forEach { (k, v) -> drop(k, v, includeShown) }
    }

    private fun drop(record: RoiPatchRecord, value: T, includeShown: Boolean = false) {
        bytes -= sizes.remove(record) ?: 0L
        if (includeShown || !isShown(value)) recycle(value)
    }
}
