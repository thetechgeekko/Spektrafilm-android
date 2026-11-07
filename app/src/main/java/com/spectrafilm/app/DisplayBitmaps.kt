/*
 * Spektrafilm for Android — display bitmaps. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Engine float results packed into colour-tagged display Bitmaps (native pack with a Kotlin
 * fallback). Split out of EngineHelpers.kt (#273).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import com.spectrafilm.app.masks.LocalAdjustment
import com.spectrafilm.app.masks.MaskCompositor
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.RenderCancellation
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Display-referred float RGB (0..1, already CCTF-encoded by the engine) → ARGB_8888 bitmap.
 *
 * Filled BAND-BY-BAND: the destination ARGB_8888 Bitmap is native memory (Android 8+), and
 * the only managed-heap cost is the int scratch we copy through `setPixels`. Writing one
 * horizontal strip at a time bounds that scratch to `w * bandRows` ints (~a few MB) instead
 * of a single `IntArray(w*h)` — which for a full-res export (e.g. 36 MP → 144 MB) overflowed
 * the ~256 MB ART heap and OOMed the export (device-reported on a 36 MP source). Peak managed
 * allocation is now independent of image megapixels.
 */
fun simResultToBitmap(
    data: ByteBuffer,
    w: Int,
    h: Int,
    colorSpace: ColorSpace = ColorSpace.SRGB,
): Bitmap {
    val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
    // Tag the bitmap with the engine output space so the system color-manages it to the panel
    // (and embeds the right ICC on Bitmap.compress export) instead of assuming sRGB. native; no IntArray.
    val bmp = createTaggedBitmap(w, h, colorSpace)
    return disposeOnFailure(
        owned = bmp,
        dispose = { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() },
    ) { ownedBitmap ->
        // #289: pack natively straight into the Bitmap's pixels when that is byte-identical
        // to the loop below. Only for an sRGB-tagged (or untagged) bitmap: setPixels takes
        // its ints as sRGB and converts them into the bitmap's own space, while the native
        // pack writes the values as-is, so on a wide-gamut tag the two would differ.
        val packStartNs = System.nanoTime()
        if (packsNativelyLikeSetPixels(ownedBitmap) && packNative(data, w, h, ownedBitmap)) {
            logDisplayPack("native", w, h, packStartNs)
            return@disposeOnFailure ownedBitmap
        }
        // Scratch is now a FLOAT strip plus the int strip, so the band is sized by the float
        // budget (~4 MB = 1M floats) rather than the int one. Total managed scratch stays in the
        // same class as the single 4 MB IntArray this replaced, and stays independent of image
        // megapixels — the OOM note above still holds.
        val bandRows = (1024 * 1024 / (w * 3)).coerceIn(1, h)
        val src = FloatArray(w * bandRows * 3)
        val strip = IntArray(w * bandRows)
        var y = 0
        while (y < h) {
            val rows = minOf(bandRows, h - y)
            val n = w * rows
            // Bulk-read the strip instead of three bounds-checked FloatBuffer.get() per pixel.
            // A device variant ladder put per-element buffer ops at ~51% of an equivalent
            // per-pixel loop's cost, with sequential reads costing nothing once bulk
            // (docs/research/perf-lab.md §16.9). This loop has no scatter, so that is the
            // whole of the win here.
            f.position(y * w * 3)
            f.get(src, 0, n * 3)
            packToArgb(src, strip, n)
            ownedBitmap.setPixels(strip, 0, w, 0, y, w, rows)
            y += rows
        }
        logDisplayPack("kotlin", w, h, packStartNs)
        ownedBitmap
    }
}

/**
 * True when writing raw bytes into [bitmap] gives the same pixels as `setPixels`, which
 * treats its ints as sRGB and converts them into the bitmap's colour space (API 26+).
 */
private fun packsNativelyLikeSetPixels(bitmap: Bitmap): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return true
    val cs = bitmap.colorSpace ?: return true
    return cs == android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
}

/** One line per converted frame, so #289's before/after is readable from logcat. */
private fun logDisplayPack(path: String, w: Int, h: Int, startNs: Long) {
    val ms = (System.nanoTime() - startNs) / 1_000_000.0
    Diag.i("display pack path=$path ${w}x$h ${"%.2f".format(java.util.Locale.ROOT, ms)}ms")
}

/** [SpektraEngine.packDisplayToBitmap], or false if the native library is unavailable. */
private fun packNative(data: ByteBuffer, w: Int, h: Int, bitmap: Bitmap): Boolean = try {
    SpektraEngine.packDisplayToBitmap(data, data.position(), w, h, bitmap)
} catch (_: LinkageError) {
    false
}

/**
 * Clamp, scale, round and pack [count] interleaved RGB float32 pixels from [src] into
 * opaque ARGB_8888 ints in [dst].
 *
 * Split out as a pure function on purpose: it is the per-pixel hot loop of
 * [simResultToBitmap], and a JVM unit test can gate it while the `Bitmap.setPixels` beside
 * it cannot (`Bitmap` is an android.jar stub that throws off-device). The arithmetic is
 * character-for-character what the inlined loop did, so output is unchanged — NaN included,
 * which clamps to 0 exactly as before.
 */
internal fun packToArgb(src: FloatArray, dst: IntArray, count: Int) {
    var i = 0
    var k = 0
    while (k < count) {
        val r = (min(1f, maxOf(0f, src[i])) * 255f + 0.5f).toInt()
        val g = (min(1f, maxOf(0f, src[i + 1])) * 255f + 0.5f).toInt()
        val b = (min(1f, maxOf(0f, src[i + 2])) * 255f + 0.5f).toInt()
        dst[k++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        i += 3
    }
}

/**
 * ARGB_8888 bitmap tagged with the android color space matching the engine output [cs] (API 26+), so
 * the system color-manages it to the display and embeds the right ICC on Bitmap.compress export —
 * instead of treating wide-gamut output as sRGB. Falls back to a plain (sRGB) bitmap on API 24–25, for
 * ACES2065_1 (no faithful 8-bit tag → [ColorManagement.displayColorSpaceName] is null), or if a device
 * rejects the space. The createBitmap-with-color-space overload is API 26; setColorSpace is only API 29.
 */
private fun createTaggedBitmap(w: Int, h: Int, cs: ColorSpace): Bitmap {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val name = ColorManagement.displayColorSpaceName(cs)
        if (name != null) {
            val tagged = runCatching {
                val acs = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.valueOf(name))
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, /* hasAlpha = */ true, acs)
            }.getOrNull()
            if (tagged != null) return tagged
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
}

/**
 * Apply the creative output grade (gamut compression + Saturation/Vibrance) AND the local-adjustment
 * masks in place to [res]'s leased output buffer, then convert to a bitmap. Mutating that buffer means
 * a subsequent 16-bit export ([saveSimResultAsTiff] / [saveSimResultAsPng16]) that reads the SAME [res]
 * inherits both — so preview and every export format stay WYSIWYG. Masks run AFTER the global grade
 * (local adjustments are on the final graded image). All-no-op → zero per-pixel cost.
 */
fun simResultToBitmapGraded(
    res: SimResult,
    cctfEncoded: Boolean,
    saturation: Float,
    vibrance: Float,
    gamutCompress: Float,
    localAdjustments: List<LocalAdjustment> = emptyList(),
    cancellation: RenderCancellation? = null,
): Bitmap = res.acquireDataLease().use { lease ->
    val data = lease.data
    gradeBufferToBitmap(
        data,
        res.width,
        res.height,
        res.colorSpace,
        cctfEncoded,
        saturation,
        vibrance,
        gamutCompress,
        localAdjustments,
        cancellation,
    )
}

/**
 * The buffer-level core of [simResultToBitmapGraded]: grade [data] IN PLACE and
 * convert to a bitmap. Also the re-grade entry point for [GradeCache] hits —
 * use [GradeCache.Pristine.withScratch], never the retained master. When [cancellation] is supplied,
 * the buffer must remain private and unpublished: cancellation during mask work can leave completed
 * rows modified, so the caller must discard the buffer instead of publishing or reusing it.
 */
fun gradeBufferToBitmap(
    data: java.nio.ByteBuffer,
    width: Int,
    height: Int,
    colorSpace: com.spectrafilm.engine.ColorSpace,
    cctfEncoded: Boolean,
    saturation: Float,
    vibrance: Float,
    gamutCompress: Float,
    localAdjustments: List<LocalAdjustment> = emptyList(),
    cancellation: RenderCancellation? = null,
): Bitmap {
    ColorGrade.applyInPlace(data, width, height, colorSpace, cctfEncoded, saturation, vibrance, gamutCompress)
    if (cancellation == null) {
        MaskCompositor.applyInPlace(data, width, height, colorSpace, cctfEncoded, localAdjustments)
    } else {
        MaskCompositor.applyInPlace(data, width, height, colorSpace, cctfEncoded, localAdjustments, cancellation)
    }
    return simResultToBitmap(data, width, height, colorSpace)
}
