/*
 * SpectraFilm for Android — engine asset/bitmap helpers. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.libraw.RawDecoder
import com.spectrafilm.libraw.WhiteBalance
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

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
    val buf = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
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
 * Decode a camera RAW/DNG [uri] to a scene-linear ACES2065-1 [LinearImage] via LibRaw.
 * The native decode is full-resolution; if the longest edge exceeds [maxEdge] the linear
 * float buffer is box-downsampled (integer step) to keep memory and render time bounded.
 *
 * [wb]/[temperatureK]/[tint] mirror the GUI "Import Raw" white-balance controls.
 */
fun decodeRawToLinear(
    ctx: Context,
    uri: Uri,
    wb: WhiteBalance,
    temperatureK: Double,
    tint: Double,
    maxEdge: Int = MAX_EDGE_PX,
): LinearImage {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Could not open RAW file")
    val result = RawDecoder.decodeToLinear(
        bytes, RawDecoder.Settings(whiteBalance = wb, temperatureK = temperatureK, tint = tint),
    )
    val w = result.width
    val h = result.height
    val src = result.data.order(ByteOrder.nativeOrder()).asFloatBuffer()

    val longest = max(w, h)
    var step = 1
    while (longest / step > maxEdge) step++
    if (step <= 1) {
        return LinearImage(result.data, w, h, colorSpace = result.colorSpace)
    }

    val outW = (w + step - 1) / step
    val outH = (h + step - 1) / step
    val out = ByteBuffer.allocateDirect(outW * outH * 3 * 4).order(ByteOrder.nativeOrder())
    val of = out.asFloatBuffer()
    var oi = 0
    for (oy in 0 until outH) {
        val sy = oy * step
        val rowBase = sy * w
        for (ox in 0 until outW) {
            val si = (rowBase + ox * step) * 3
            of.put(oi, src.get(si)); of.put(oi + 1, src.get(si + 1)); of.put(oi + 2, src.get(si + 2))
            oi += 3
        }
    }
    return LinearImage(out, outW, outH, colorSpace = result.colorSpace)
}

/** Display-referred float RGB (0..1, already CCTF-encoded by the engine) → ARGB_8888 bitmap. */
fun simResultToBitmap(data: ByteBuffer, w: Int, h: Int): Bitmap {
    val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
    val px = IntArray(w * h)
    for (p in 0 until w * h) {
        val i = p * 3
        val r = (min(1f, maxOf(0f, f.get(i))) * 255f + 0.5f).toInt()
        val g = (min(1f, maxOf(0f, f.get(i + 1))) * 255f + 0.5f).toInt()
        val b = (min(1f, maxOf(0f, f.get(i + 2))) * 255f + 0.5f).toInt()
        px[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
