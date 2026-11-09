/*
 * Spektrafilm for Android — reduce an export-scale source to what the engine can render. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The engine's full-resolution render is not tiled: its working set grows with the pixel count
 * at roughly 90 (CPU) to 135 (GPU) bytes per pixel, measured on a 50 MP Samsung DNG on the host
 * (5.4 / 6.9 GiB peak RSS) and consistent with 1521 MiB for a 12.5 MP export on the SM-S948W
 * (#269). Rendering that 50 MP file at full size on the same 11 GB phone held ~2.1 GB RSS plus
 * ~6.4 GB swap until lmkd killed the app and several system processes with it. So an
 * export-scale source above what the device can carry is area-averaged down first: the export
 * comes out smaller, as the export sheet already says large RAW inputs may, instead of taking
 * the phone down. A tiled render would lift this.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.LinearImage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Engine working set per rendered pixel, rounded up from the GPU-path measurement. */
internal const val ENGINE_RENDER_BYTES_PER_PIXEL = 140L

/** Share of physical RAM a single full-resolution render may plan to occupy. */
internal const val ENGINE_RENDER_RAM_FRACTION = 0.33

/** Never reduce below this: every device that renders at all renders 12 MP. */
internal const val ENGINE_RENDER_MIN_PIXELS = 12L * 1024 * 1024

/**
 * The largest pixel count a full-resolution render may have on a device with [physicalBytes]
 * of RAM. About 27 MP on a 12 GB phone, 19 MP on an 8 GB one.
 */
internal fun engineRenderPixelCap(physicalBytes: Long): Long {
    if (physicalBytes <= 0L) return ENGINE_RENDER_MIN_PIXELS
    val cap = (physicalBytes * ENGINE_RENDER_RAM_FRACTION / ENGINE_RENDER_BYTES_PER_PIXEL).toLong()
    return maxOf(cap, ENGINE_RENDER_MIN_PIXELS)
}

/**
 * The size [width] x [height] must be reduced to so it holds at most [maxPixels], keeping the
 * aspect ratio, or null when it already fits.
 */
internal fun reducedToFit(width: Int, height: Int, maxPixels: Long): Pair<Int, Int>? {
    require(width > 0 && height > 0 && maxPixels > 0) { "invalid reduction request" }
    val pixels = width.toLong() * height
    if (pixels <= maxPixels) return null
    val scale = sqrt(maxPixels.toDouble() / pixels)
    var w = maxOf(1, floor(width * scale).toInt())
    var h = maxOf(1, floor(height * scale).toInt())
    // Floating-point rounding must never leave the result over the cap.
    while (w.toLong() * h > maxPixels) {
        if (w >= h) w-- else h--
    }
    return w to h
}

/**
 * An area-averaged copy of this image at [outW] x [outH] (each no larger than the source).
 * Every output pixel is the exact mean of the source area it covers, including fractional
 * edge pixels, so highlights and flat fields keep their linear values. The caller owns the
 * result; this image is left open.
 */
internal fun LinearImage.areaDownscaled(
    outW: Int,
    outH: Int,
    isCancelled: () -> Boolean = { false },
): LinearImage {
    val w = width
    val h = height
    require(outW in 1..w && outH in 1..h) { "area downscale only reduces: ${w}x$h -> ${outW}x$outH" }
    val (outBuf, outputLease) = allocRotBuf(checkedRotationBytes(outW, outH) / Float.SIZE_BYTES)
    val result = if (outputLease != null) {
        LinearImage.fromDataLease(outBuf, outW, outH, colorSpace, outputLease)
    } else {
        LinearImage(outBuf, outW, outH, colorSpace)
    }
    try {
        val sx = w.toDouble() / outW
        val sy = h.toDouble() / outH
        // Horizontal footprint of every output column: first source column, count, weights.
        val xStart = IntArray(outW)
        val xCount = IntArray(outW)
        val weightsList = ArrayList<Float>(w + outW)
        val xWeightAt = IntArray(outW)
        for (ox in 0 until outW) {
            val a = ox * sx
            val b = min(w.toDouble(), (ox + 1) * sx)
            val first = floor(a).toInt()
            var x = first
            xStart[ox] = first
            xWeightAt[ox] = weightsList.size
            while (x < w && x < b) {
                val overlap = min(b, x + 1.0) - maxOf(a, x.toDouble())
                if (overlap > 0) weightsList.add((overlap / sx).toFloat()) else weightsList.add(0f)
                x++
            }
            xCount[ox] = x - first
        }
        val xWeights = weightsList.toFloatArray()
        val invSy = (1.0 / sy).toFloat()
        acquireDataLease().use { srcLease ->
            result.acquireDataLease().use { dstLease ->
                val src = srcLease.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val dst = dstLease.data.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val row = FloatArray(w * 3)
                val hRow = FloatArray(outW * 3)
                val acc = FloatArray(outW * 3)
                var oy = 0
                for (y in 0 until h) {
                    if (oy >= outH) break
                    if ((y and 63) == 0 && isCancelled()) {
                        throw CancellationException("export downscale cancelled")
                    }
                    src.position(y * w * 3)
                    src.get(row, 0, w * 3)
                    for (ox in 0 until outW) {
                        var r = 0f
                        var g = 0f
                        var b = 0f
                        var i = xStart[ox] * 3
                        val wi = xWeightAt[ox]
                        for (k in 0 until xCount[ox]) {
                            val wt = xWeights[wi + k]
                            r += row[i] * wt
                            g += row[i + 1] * wt
                            b += row[i + 2] * wt
                            i += 3
                        }
                        val o = ox * 3
                        hRow[o] = r
                        hRow[o + 1] = g
                        hRow[o + 2] = b
                    }
                    // Distribute this source row over the output rows it overlaps.
                    var cur = y.toDouble()
                    val rowEnd = y + 1.0
                    while (cur < rowEnd && oy < outH) {
                        val boundary = (oy + 1) * sy
                        val end = min(rowEnd, boundary)
                        val wt = ((end - cur) * invSy).toFloat()
                        for (k in acc.indices) acc[k] += hRow[k] * wt
                        cur = end
                        if (end >= boundary - 1e-9) {
                            dst.position(oy * outW * 3)
                            dst.put(acc)
                            acc.fill(0f)
                            oy++
                        }
                    }
                }
                // A last output row left open by rounding still holds its full area.
                if (oy < outH) {
                    dst.position(oy * outW * 3)
                    dst.put(acc)
                }
            }
        }
        return result
    } catch (failure: Throwable) {
        result.close()
        throw failure
    }
}
