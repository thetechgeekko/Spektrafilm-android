/*
 * Spektrafilm for Android — engine asset/bitmap helpers. GPLv3.
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
    // Issue #7 mitigation (app-side, conservative): LibRaw decodes at FULL native
    // resolution into a float32 buffer (12 bytes/px) BEFORE we box-downsample to maxEdge,
    // a multi-hundred-MB transient for 50-200MP RAW. True tiling needs native work and is
    // out of scope. Here we (1) guard the decode against OutOfMemoryError and retry at a
    // progressively smaller cap (the *output* shrinks; the native full-res decode is
    // unavoidable without native tiling, but a smaller target frees the downsample buffer
    // and the surviving LinearImage so a borderline device can still produce a usable
    // preview), and (2) surface a clear error rather than crash if even the smallest cap
    // fails. The display-quality difference of a smaller cap is preferable to an app crash.
    var attemptEdge = maxEdge.coerceAtLeast(MIN_RAW_FALLBACK_EDGE)
    while (true) {
        try {
            return decodeRawAtEdge(ctx, uri, wb, temperatureK, tint, attemptEdge)
        } catch (oom: OutOfMemoryError) {
            // Encourage the collector to reclaim the failed transient before retrying.
            System.gc()
            if (attemptEdge <= MIN_RAW_FALLBACK_EDGE) {
                // Out of headroom even at the smallest cap: surface a clear, catchable error
                // (the caller's runCatching turns this into a user-visible status, not a crash).
                throw RuntimeException(
                    "Not enough memory to decode this RAW file (too large for this device).", oom,
                )
            }
            attemptEdge = max(MIN_RAW_FALLBACK_EDGE, attemptEdge / 2)
        }
    }
}

/** Smallest longest-edge cap the RAW OOM-retry ladder will fall back to before giving up. */
private const val MIN_RAW_FALLBACK_EDGE = 512

/** Single RAW decode + box-downsample attempt at a specific [maxEdge] (see [decodeRawToLinear]). */
private fun decodeRawAtEdge(
    ctx: Context,
    uri: Uri,
    wb: WhiteBalance,
    temperatureK: Double,
    tint: Double,
    maxEdge: Int,
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
    // The full-resolution native float buffer (result.data) and the file byte[] are now
    // unreferenced once this returns the smaller `out` LinearImage -> eligible for prompt GC.
    return LinearImage(out, outW, outH, colorSpace = result.colorSpace)
}

/**
 * Crop a square-ish window of [cropEdge] native pixels out of [src], centred on the
 * normalized point ([nx], [ny]) in 0..1. The window is clamped to the image bounds. The
 * returned [LinearImage] shares the same color space and is a real pixel-for-pixel slice
 * of the source float buffer (no resampling), so when it is rendered through the engine at
 * full resolution the dye-cloud grain resolves at 1:1 — this is the backing for the 100%
 * grain magnifier, not an upscale of the downscaled preview.
 */
fun cropLinearImage(src: LinearImage, nx: Float, ny: Float, cropEdge: Int): LinearImage {
    val w = src.width
    val h = src.height
    val cw = min(cropEdge, w)
    val ch = min(cropEdge, h)
    val cxPx = (nx.coerceIn(0f, 1f) * w).toInt()
    val cyPx = (ny.coerceIn(0f, 1f) * h).toInt()
    val x0 = (cxPx - cw / 2).coerceIn(0, w - cw)
    val y0 = (cyPx - ch / 2).coerceIn(0, h - ch)

    val sf = src.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
    val out = ByteBuffer.allocateDirect(cw * ch * 3 * 4).order(ByteOrder.nativeOrder())
    val of = out.asFloatBuffer()
    var oi = 0
    for (oy in 0 until ch) {
        val rowBase = (y0 + oy) * w
        for (ox in 0 until cw) {
            val si = (rowBase + x0 + ox) * 3
            of.put(oi, sf.get(si)); of.put(oi + 1, sf.get(si + 1)); of.put(oi + 2, sf.get(si + 2))
            oi += 3
        }
    }
    return LinearImage(out, cw, ch, colorSpace = src.colorSpace)
}

/** Native pixel edge of the full-resolution magnifier crop. */
const val MAGNIFIER_CROP_PX = 512

/**
 * In-memory cache of the decoded *proxy-resolution source* [LinearImage], so that interactive
 * slider/param edits don't re-decode the RAW/photo (LibRaw decode or bitmap decode + sRGB→
 * ProPhoto linearization + EXIF/rotation) on every render. Only the look/film params change
 * between most renders, and the decoded source does NOT depend on any of them — it depends
 * only on the DECODE-affecting inputs captured in [Key].
 *
 * READ-ONLY / DEFENSIVE-COPY DECISION: verified against the engine + JNI that the input image
 * buffer is treated as strictly const, so the SAME cached [LinearImage] can be re-fed to
 * `simulatePreview` across edits with no defensive copy:
 *   - `spk_simulate` / `spk_simulate_preview` take `const spk_image* in` (spektra.h).
 *   - `preprocess_geometry` (the shared entry for both scan_film and print routes) copies
 *     `in->data` into a fresh `std::vector<double> src` and operates only on that copy
 *     (spektra.cpp ~L265-267); the input is never written.
 *   - `spk_simulate_preview` downscales `in->data` into a fresh `std::vector<float> small`
 *     (read-only read; spektra.cpp ~L901) before simulating.
 *   - The JNI obtains `in_data` via `GetDirectBufferAddress` and only reads it (spektra_jni.cpp).
 * Therefore re-using the cached buffer cannot corrupt it. (If the engine ever started writing
 * into the input, this class would have to hand out a copy instead — see [get].)
 *
 * Scope: exactly ONE cached entry (the current source at the current proxy resolution). Storing
 * a new entry drops the previous [LinearImage] reference, so its direct ByteBuffer becomes
 * eligible for GC — we never hold two large source buffers at once.
 *
 * Thread-safety: all access happens from the preview render coroutine (Dispatchers.Default /
 * .IO sequentially per render); methods are `@Synchronized` as cheap insurance since
 * [invalidate] may be called from a different scope.
 */
class DecodedSourceCache {
    /** Everything that affects the DECODE of the proxy source — and nothing that doesn't. */
    private data class Key(
        val uri: String?,
        val kind: String,
        val whiteBalance: WhiteBalance,
        val temperature: Float,
        val tint: Float,
        val rotationDegrees: Int,
        val maxEdge: Int,
    )

    private var key: Key? = null
    private var image: LinearImage? = null

    /**
     * Return the cached decoded source if its key matches the supplied decode inputs, else
     * null. A null result means the caller must decode (via loadSource) and then [put] it.
     */
    @Synchronized
    fun get(
        uri: String?, kind: String, whiteBalance: WhiteBalance,
        temperature: Float, tint: Float, rotationDegrees: Int, maxEdge: Int,
    ): LinearImage? {
        val k = Key(uri, kind, whiteBalance, temperature, tint, rotationDegrees, maxEdge)
        return if (k == key) image else null
    }

    /** Store [img] as the single cached entry, dropping any previous one (GC reclaims it). */
    @Synchronized
    fun put(
        uri: String?, kind: String, whiteBalance: WhiteBalance,
        temperature: Float, tint: Float, rotationDegrees: Int, maxEdge: Int,
        img: LinearImage,
    ) {
        key = Key(uri, kind, whiteBalance, temperature, tint, rotationDegrees, maxEdge)
        image = img // previous LinearImage (and its direct buffer) is now unreferenced -> GC
    }

    /** Drop the cached entry (e.g. on teardown). */
    @Synchronized
    fun invalidate() { key = null; image = null }
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
