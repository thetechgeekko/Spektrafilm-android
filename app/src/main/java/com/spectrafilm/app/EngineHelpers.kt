/*
 * Spektrafilm for Android — engine asset and crop helpers. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * RAW decode is in RawDecode.kt, the decoded-source cache in DecodedSourceCache.kt and the
 * display bitmap packers in DisplayBitmaps.kt (#273).
 */
package com.spectrafilm.app

import android.content.Context
import com.spectrafilm.engine.LinearImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Recursively copy the bundled assets/spektra tree to filesDir/spektra; returns that dir. */
fun extractAssets(ctx: Context): File {
    val out = File(ctx.filesDir, "spektra")
    val am = ctx.assets
    fun copyDir(rel: String) {
        val entries = am.list(rel) ?: emptyArray()
        if (entries.isEmpty()) { // it's a file
            File(out.parentFile, rel).apply { parentFile?.mkdirs() }
            am.open(rel).use { input -> File(ctx.filesDir, rel).outputStream().use { input.copyTo(it) } }
            return
        }
        File(ctx.filesDir, rel).mkdirs()
        for (e in entries) copyDir("$rel/$e")
    }
    if (!out.exists()) copyDir("spektra")
    return out
}

/** A deterministic scene-linear ProPhoto-ish test image: horizontal exposure ramp + RGB bands. */
fun syntheticLinearImage(size: Int): LinearImage {
    require(size >= 2) { "synthetic image size must be at least 2" }
    val buf = ByteBuffer.allocateDirect(checkedRgbFloatBytes(size, size))
        .order(ByteOrder.nativeOrder())
    val f = buf.asFloatBuffer()
    for (y in 0 until size) {
        val band = (y * 4 / size) // 0..3
        for (x in 0 until size) {
            val t = x.toFloat() / (size - 1)        // 0..1 exposure ramp
            val v = 0.02f + t * t * 0.9f            // perceptually spread linear values
            val (r, g, b) = when (band) {
                0 -> Triple(v, v, v)                // neutral
                1 -> Triple(v, v * 0.25f, v * 0.25f)// reds
                2 -> Triple(v * 0.25f, v, v * 0.25f)// greens
                else -> Triple(v * 0.25f, v * 0.25f, v) // blues
            }
            val i = (y * size + x) * 3
            f.put(i, r); f.put(i + 1, g); f.put(i + 2, b)
        }
    }
    return LinearImage(buf, size, size, colorSpace = "ProPhoto RGB")
}

/**
 * Crop a square-ish window of [cropEdge] native pixels out of [src], centred on the
 * normalized point ([nx], [ny]) in 0..1. The window is clamped to the image bounds. The
 * returned [LinearImage] shares the same color space and is a real pixel-for-pixel slice
 * of the source float buffer (no resampling), so when it is rendered through the engine at
 * full resolution the dye-cloud grain resolves at 1:1 — this is the backing for the 100%
 * grain magnifier, not an upscale of the downscaled preview.
 */
fun cropLinearImage(src: LinearImage, nx: Float, ny: Float, cropEdge: Int, gain: Float = 1f): LinearImage =
    cropLinearImageRect(src, nx, ny, cropEdge, cropEdge, gain)

/**
 * Rectangular generalization of [cropLinearImage]: crop a [cropW]×[cropH] native-pixel window
 * out of [src], centred on the normalized point ([nx], [ny]). Clamped to the image bounds, no
 * resampling. This backs the Lightroom-style zoom loupe (render the visible viewport region at
 * native resolution) as well as the square 100% magnifier.
 */
fun cropLinearImageRect(
    src: LinearImage, nx: Float, ny: Float, cropW: Int, cropH: Int, gain: Float = 1f,
): LinearImage = cropLinearImageBox(src, centredCropBox(src.width, src.height, nx, ny, cropW, cropH), gain)

/** The box [cropLinearImageRect] cuts from a [w]x[h] image: [cropW]x[cropH] centred on ([nx], [ny]), clamped inside. */
fun centredCropBox(w: Int, h: Int, nx: Float, ny: Float, cropW: Int, cropH: Int): CropBox {
    val cw = cropW.coerceIn(1, w)
    val ch = cropH.coerceIn(1, h)
    val cxPx = (nx.coerceIn(0f, 1f) * w).toInt()
    val cyPx = (ny.coerceIn(0f, 1f) * h).toInt()
    val x0 = (cxPx - cw / 2).coerceIn(0, w - cw)
    val y0 = (cyPx - ch / 2).coerceIn(0, h - ch)
    return CropBox(x0, y0, cw, ch)
}

/** A pixel box inside a [LinearImage]: top-left corner and extent. */
data class CropBox(val x0: Int, val y0: Int, val width: Int, val height: Int)

/**
 * The pixel box the engine's crop stage cuts out of a [w]×[h] input for `io.crop`
 * (runtime/stages/crop_resize.cpp::crop_image, a transcription of upstream
 * utils/crop_resize.py). Reproduced here so the compare viewer's and the press-and-hold peek's
 * BEFORE frame can be cut to the same pixels the engine renders (#254). `center` is (x, y) as
 * fractions of (W, H); `size` is (x, y) as fractions of the LONG side; NumPy's round is
 * half-to-even, which is Math.rint. A box larger than the image is clipped to the image first,
 * as the engine does (upstream PR #71): the oracle let it go to a NumPy negative-start slice,
 * which returned a thin strip for a full-height crop measured one row too tall on the preview.
 */
fun engineCropBox(w: Int, h: Int, center: Pair<Float, Float>, size: Pair<Float, Float>): CropBox {
    val maxDim = kotlin.math.max(w, h).toDouble()
    val cy = Math.rint(h * center.second.toDouble())
    val cx = Math.rint(w * center.first.toDouble())
    val sh = Math.rint(maxDim * size.second.toDouble()).toLong().coerceAtMost(h.toLong())
    val sw = Math.rint(maxDim * size.first.toDouble()).toLong().coerceAtMost(w.toLong())
    var y0 = Math.rint(cy - sh / 2.0).toLong()
    var x0 = Math.rint(cx - sw / 2.0).toLong()
    if (y0 < 0) y0 = 0
    if (x0 < 0) x0 = 0
    if (y0 + sh > h) y0 = h - sh
    if (x0 + sw > w) x0 = w - sw
    val (ry, rh) = numpySlice(y0, sh, h.toLong())
    val (rx, rw) = numpySlice(x0, sw, w.toLong())
    return CropBox(rx.toInt(), ry.toInt(), rw, rh)
}

/** `array[start : start + sz]` with NumPy's negative-index semantics: offset and extent. */
private fun numpySlice(startIn: Long, sz: Long, dim: Long): Pair<Long, Int> {
    var start = startIn
    var stop = start + sz
    if (start < 0) start += dim
    if (stop < 0) stop += dim
    start = start.coerceIn(0, dim)
    stop = stop.coerceIn(0, dim)
    return start to (if (stop > start) (stop - start).toInt() else 0)
}

/**
 * Cut [box] out of [src] pixel-for-pixel (no resampling); the box must lie inside the image.
 *
 * [gain] scales every copied value (1 = a plain copy). The zoom patch and the magnifier pass the
 * WHOLE frame's auto-exposure gain here and render the crop with auto-exposure off, so a crop is
 * exposed exactly like the frame it was cut from instead of being metered on its own (which made
 * a zoomed-in dark corner jump brighter and a bright sky darker). See [frameExposureGain].
 */
fun cropLinearImageBox(src: LinearImage, box: CropBox, gain: Float = 1f): LinearImage = src.acquireDataLease().use { sourceLease ->
    require(gain.isFinite() && gain > 0f) { "crop gain must be finite and positive" }
    val sourceData = sourceLease.data
    val w = src.width
    val h = src.height
    val x0 = box.x0
    val y0 = box.y0
    val cw = box.width
    val ch = box.height
    require(cw >= 1 && ch >= 1 && x0 >= 0 && y0 >= 0 && x0 + cw <= w && y0 + ch <= h) {
        "crop box $box lies outside the ${w}x$h image"
    }

    val requiredSourceBytes = checkedRgbFloatBytes(w, h)
    val logicalSource = sourceData.duplicate().order(ByteOrder.nativeOrder())
    require(logicalSource.remaining() >= requiredSourceBytes) {
        "crop source buffer is truncated: ${logicalSource.remaining()} < $requiredSourceBytes"
    }
    logicalSource.limit(logicalSource.position() + requiredSourceBytes)
    val sf = logicalSource.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
    val out = ByteBuffer.allocateDirect(checkedRgbFloatBytes(cw, ch))
        .order(ByteOrder.nativeOrder())
    val of = out.asFloatBuffer()
    var oi = 0
    for (oy in 0 until ch) {
        val rowBase = (y0 + oy) * w
        for (ox in 0 until cw) {
            val si = (rowBase + x0 + ox) * 3
            if (gain == 1f) {
                of.put(oi, sf.get(si)); of.put(oi + 1, sf.get(si + 1)); of.put(oi + 2, sf.get(si + 2))
            } else {
                of.put(oi, sf.get(si) * gain)
                of.put(oi + 1, sf.get(si + 1) * gain)
                of.put(oi + 2, sf.get(si + 2) * gain)
            }
            oi += 3
        }
    }
    LinearImage(out, cw, ch, colorSpace = src.colorSpace)
}

/** Native pixel edge of the full-resolution magnifier crop. */
const val MAGNIFIER_CROP_PX = 512

/** Max long-edge (px) of a Lightroom-zoom ROI render — bounds cost/memory to ~screen resolution
 *  while still being far sharper than the upscaled ~640px proxy. */
const val ROI_RENDER_MAX_PX = 1600

/** Long-edge (px) of the fast DRAFT zoom-ROI render: a low-res sharp crop shown almost immediately
 *  on a zoom settle, then refined to the full ROI_RENDER_MAX_PX crop. ~5x faster than the full pass,
 *  so the zoomed region resolves quickly instead of waiting ~1s on the soft scaled proxy. */
const val ROI_DRAFT_MAX_PX = 640

/** Long-edge (px) of the live DRAFT preview rendered continuously while a control is being dragged.
 *  Small enough to render back-to-back at interactive rates from the already-cached full-edge proxy
 *  (just a smaller engine pass, never a re-decode); the crisp full preview still lands on settle.
 *  This is Lightroom's draft/final loupe behaviour, ported to the spectral CPU engine. */
const val DRAFT_RENDER_MAX_PX = 384
