/*
 * Spektrafilm for Android — free-angle straighten. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Straighten is a decode-time geometry op, applied right after the 90-degree source rotation
 * and BEFORE the engine, exactly like that rotation. It is deliberately not an engine
 * crop_resize parameter:
 *
 *  - the engine is parity-locked to upstream spektrafilm, which has no rotation, so a new
 *    stage there would be an Android-only fork of the oracle path;
 *  - the engine's crop box is mirrored app-side (engineCropBox, compare/peek, skin fields,
 *    ROI renders), and every one of those mirrors would have to learn the rotation;
 *  - the GPU and zoom paths read the decoded source, so a decode-time op reaches them all
 *    for free and the image every path sees is the same one.
 *
 * The output is the LARGEST rectangle of the source's own aspect ratio that fits inside the
 * rotated frame, so a straightened photo has no empty corners and keeps its shape (a 3:2
 * frame stays 3:2, which also keeps a normalized crop box meaningful across angle changes).
 */
package com.spectrafilm.app

import com.spectrafilm.engine.LinearImage
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The ruler's range, in degrees either way. */
internal const val STRAIGHTEN_MAX_DEGREES = 45f

/** Clamp a stored angle to the ruler's range; NaN and infinities become 0. */
internal fun clampStraighten(deg: Float): Float =
    if (deg.isFinite()) deg.coerceIn(-STRAIGHTEN_MAX_DEGREES, STRAIGHTEN_MAX_DEGREES) else 0f

/**
 * Scale of the largest same-aspect rectangle inside a [w] x [h] frame rotated by [deg]:
 * the output is (w * k) x (h * k). 1 at 0 degrees, and below 1 at any other angle.
 */
internal fun straightenScale(w: Int, h: Int, deg: Float): Double {
    val rad = Math.toRadians(deg.toDouble())
    val c = abs(cos(rad))
    val s = abs(sin(rad))
    val fw = w.toDouble()
    val fh = h.toDouble()
    return minOf(fw / (fw * c + fh * s), fh / (fw * s + fh * c))
}

/** Pixel size of [straightened] output for a [w] x [h] source. */
internal fun straightenedSize(w: Int, h: Int, deg: Float): Pair<Int, Int> {
    if (deg == 0f) return w to h
    val k = straightenScale(w, h, deg)
    // floor, never round: a rounded-up edge would sample past the rotated frame.
    return floor(w * k).toInt().coerceAtLeast(1) to floor(h * k).toInt().coerceAtLeast(1)
}

/**
 * Return a new [LinearImage] rotated clockwise by [deg] degrees and cut to the largest
 * same-aspect rectangle inside the rotated frame (see the file header). 0 returns the input
 * unchanged. Bilinear sampling. Consumes this image (closed on success and on failure),
 * like [rotated].
 */
fun LinearImage.straightened(
    deg: Float,
    isCancelled: () -> Boolean = { false },
): LinearImage = straightenedWithWorkers(deg, defaultRotWorkers(width.toLong() * height), isCancelled)

/**
 * Work is split by OUTPUT row and every output pixel is computed from its own coordinates
 * alone (nothing accumulates along a row), so the result is byte-identical for any worker
 * count — the same contract as [rotatedWithWorkers]. `StraightenTest` asserts it 1-vs-8.
 */
internal fun LinearImage.straightenedWithWorkers(
    deg: Float,
    workers: Int,
    isCancelled: () -> Boolean = { false },
): LinearImage {
    if (deg == 0f) return this
    if (isCancelled()) {
        close()
        throw CancellationException("source straighten cancelled")
    }
    val ch = 3
    val w = width
    val h = height
    val (ow, oh) = straightenedSize(w, h, deg)
    return acquireDataLease().use { sourceLease ->
        val sourceWindow = try {
            checkedRotationSourceWindow(sourceLease.data)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        val (outBuf, outputLease) = try {
            allocRotBuf(checkedRotationBytes(ow, oh) / Float.SIZE_BYTES)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        val result = if (outputLease != null) {
            LinearImage.fromDataLease(outBuf, ow, oh, colorSpace, outputLease)
        } else {
            LinearImage(outBuf, ow, oh, colorSpace)
        }
        val rad = Math.toRadians(deg.toDouble())
        val cosT = cos(rad)
        val sinT = sin(rad)
        val cx = w / 2.0
        val cy = h / 2.0
        val ocx = ow / 2.0
        val ocy = oh / 2.0
        val failure = AtomicReference<Throwable?>(null)
        fun cancelled(): Boolean = failure.get() != null || isCancelled()

        fun work(y0: Int, y1: Int) {
            val src = sourceWindow.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            val dst = outBuf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            val row = FloatArray(ow * ch)
            for (oy in y0 until y1) {
                if (cancelled()) throw CancellationException("source straighten cancelled")
                val v = oy + 0.5 - ocy
                for (ox in 0 until ow) {
                    val u = ox + 0.5 - ocx
                    // Inverse of a clockwise turn (y down): the source point shown at (u, v).
                    val sx = cx + u * cosT + v * sinT - 0.5
                    val sy = cy - u * sinT + v * cosT - 0.5
                    val fx0 = floor(sx)
                    val fy0 = floor(sy)
                    val fx = (sx - fx0).toFloat()
                    val fy = (sy - fy0).toFloat()
                    val x0 = fx0.toInt().coerceIn(0, w - 1)
                    val y0s = fy0.toInt().coerceIn(0, h - 1)
                    val x1 = (fx0.toInt() + 1).coerceIn(0, w - 1)
                    val y1s = (fy0.toInt() + 1).coerceIn(0, h - 1)
                    val i00 = (y0s * w + x0) * ch
                    val i10 = (y0s * w + x1) * ch
                    val i01 = (y1s * w + x0) * ch
                    val i11 = (y1s * w + x1) * ch
                    val d = ox * ch
                    for (c in 0 until ch) {
                        val top = src.get(i00 + c) + (src.get(i10 + c) - src.get(i00 + c)) * fx
                        val bot = src.get(i01 + c) + (src.get(i11 + c) - src.get(i01 + c)) * fx
                        row[d + c] = top + (bot - top) * fy
                    }
                }
                dst.position(oy * ow * ch)
                dst.put(row, 0, ow * ch)
            }
        }

        try {
            val n = workers.coerceIn(1, oh)
            if (n == 1) {
                work(0, oh)
            } else {
                val per = (oh + n - 1) / n
                fun guarded(a: Int, b: Int) {
                    try {
                        work(a, b)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
                val threads = ArrayList<Thread>(n - 1)
                try {
                    var start = per
                    while (start < oh) {
                        val a = start
                        val b = minOf(start + per, oh)
                        threads += Thread { guarded(a, b) }.also { it.start() }
                        start = b
                    }
                    guarded(0, minOf(per, oh))
                } finally {
                    var interrupted = false
                    for (thread in threads) {
                        while (thread.isAlive) {
                            try {
                                thread.join()
                            } catch (e: InterruptedException) {
                                interrupted = true
                                failure.compareAndSet(null, e)
                            }
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt()
                }
                failure.get()?.let { throw it }
            }
            if (isCancelled()) throw CancellationException("source straighten cancelled")
        } catch (t: Throwable) {
            result.close()
            close()
            throw t
        }
        close()
        result
    }
}
