/*
 * Spektrafilm for Android — image import/export helpers. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Converts a display-referred (sRGB) Bitmap into the scene-linear ProPhoto-RGB
 * float buffer the engine expects. The gallery writers (JPEG, Ultra HDR, PNG, 16-bit TIFF)
 * live in GalleryExport.kt and the EXIF copy in ExportExif.kt (#273).
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.NativeBufferOwner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException
import kotlin.math.max

/**
 * sRGB (D65) -> ProPhoto RGB (D50) linear 3x3 matrix, row-major.
 *
 * Baked constant. Derivation:
 *   M = [ProPhoto XYZ->RGB (D50)] · [Bradford CAT D65->D50] · [sRGB RGB->XYZ (D65)]
 * i.e. take linear sRGB primaries to CIE XYZ under D65, chromatically adapt the
 * white point from D65 to D50 (Bradford), then convert XYZ to linear ProPhoto RGB.
 * Values match the colour-science reference matrix for sRGB->ProPhoto (linear).
 */
private val SRGB_TO_PROPHOTO = floatArrayOf(
    0.5290825f, 0.3303437f, 0.1405738f,
    0.0982640f, 0.8734031f, 0.0283329f,
    0.0167029f, 0.1176946f, 0.8656026f,
)

/** Cap the longest edge for interactive (preview/magnifier) renders so on-device memory stays bounded. */
const val MAX_EDGE_PX = 2048

/**
 * Longest-edge cap for the FINAL export render. Export must be full-resolution (the
 * "proxy preview vs full-res export" model), so this is set high enough to be effectively
 * native for any phone camera (16384 ≈ 200 MP at 4:3) while still bounding a pathological
 * input. The engine's per-stage buffers are native (off-Java-heap) allocations, so a
 * full-res render of a typical 12–50 MP frame fits in device RAM; the RAW decode path also
 * has an OOM-retry ladder, and the export is wrapped in runCatching, so an over-large source
 * degrades/fails gracefully rather than crashing. (Was previously capped at MAX_EDGE_PX,
 * which silently downscaled e.g. a 12 MP export to ~3 MP.)
 */
const val EXPORT_MAX_EDGE_PX = 16384

/** Inverse sRGB CCTF: display sRGB (0..1) -> scene-linear (0..1). */
private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055) / 1.055), 2.4).toFloat()

/**
 * [srgbToLinear] over the 256 values an 8-bit channel can take, built by that same function
 * so the table is bit-identical to calling it per pixel; a decoded 12.5 MP photo is otherwise
 * 37.5 M `Math.pow` calls (#198 measured the conversion, not the codec, as the decode cost).
 */
private val SRGB_TO_LINEAR_8BIT: FloatArray = FloatArray(256) { srgbToLinear(it / 255f) }

/** Optional wall-clock breakdown of [decodeToLinearProPhoto], for the #177 bench (#198). */
class DecodeTimings {
    /** Platform codec into a Bitmap ([decodeDownscaled]). */
    var bitmapDecodeMs: Long = 0
    /** `Bitmap.getPixels` staging copies inside the conversion. */
    var getPixelsMs: Long = 0
    /** The whole conversion ([bitmapToLinearProPhoto]), staging included. */
    var convertMs: Long = 0
}

/** sRGB OETF: scene-linear (0..1) -> display sRGB (0..1). */
private fun linearToSrgb(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) v * 12.92f else (1.055f * Math.pow(v.toDouble(), 1.0 / 2.4).toFloat() - 0.055f)
}

/** ProPhoto RGB (D50) linear -> sRGB (D65) linear 3x3, row-major (inverse of [SRGB_TO_PROPHOTO]). */
private val PROPHOTO_TO_SRGB = floatArrayOf(
    2.0340429f, -0.7275221f, -0.3065211f,
    -0.2289921f, 1.2317125f, -0.0027205f,
    -0.0085287f, -0.1532223f, 1.1617509f,
)

/**
 * Convert a scene-linear wide-gamut [LinearImage] to a display-referred sRGB Bitmap for the
 * "before" reference in the compare viewer. ProPhoto-RGB sources are matrixed to sRGB; other
 * spaces (e.g. ACES2065-1 RAW) are tone-mapped through the same matrix as a reasonable
 * approximation, then sRGB-encoded. This is only a viewing reference, not a render path.
 */
fun linearToDisplayBitmap(img: LinearImage): Bitmap =
    Bitmap.createBitmap(linearToDisplayArgb(img), img.width, img.height, Bitmap.Config.ARGB_8888)

/** [img] as display-sRGB packed ARGB (`width*height`, row-major), the pixels [linearToDisplayBitmap] shows. */
fun linearToDisplayArgb(img: LinearImage): IntArray {
    val w = img.width
    val h = img.height
    return img.acquireDataLease().use { lease ->
        val data = lease.data
        val f = data.asFloatBuffer()
        val m = PROPHOTO_TO_SRGB
        val px = IntArray(w * h)
        for (p in 0 until w * h) {
            val i = p * 3
            val pr = f.get(i); val pg = f.get(i + 1); val pb = f.get(i + 2)
            val rl = m[0] * pr + m[1] * pg + m[2] * pb
            val gl = m[3] * pr + m[4] * pg + m[5] * pb
            val bl = m[6] * pr + m[7] * pg + m[8] * pb
            val r = (linearToSrgb(rl) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (linearToSrgb(gl) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (linearToSrgb(bl) * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        px
    }
}

/**
 * The BEFORE frame of the compare viewer and the press-and-hold peek: the decoded source cut to
 * the pixel box the engine's crop stage renders, so before and after show one frame. Rotation is
 * already in [src] (a decode-time input). Without the cut, the whole uncropped source was drawn
 * into the cropped after frame's aspect box, letterboxed and at the wrong size (#254).
 */
fun beforeFrameBitmap(src: LinearImage, params: com.spectrafilm.engine.SpektraParams): Bitmap {
    val io = params.io
    if (!io.crop) return linearToDisplayBitmap(src)
    val box = engineCropBox(src.width, src.height, io.cropCenter, io.cropSize)
    if (box.width < 1 || box.height < 1) return linearToDisplayBitmap(src)
    val cropped = cropLinearImageBox(src, box)
    return try {
        linearToDisplayBitmap(cropped)
    } finally {
        cropped.close()
    }
}

/**
 * Decode [uri] to a display-sRGB Bitmap, downscale so the longest edge is
 * <= [MAX_EDGE_PX], then convert to a scene-linear ProPhoto-RGB float [LinearImage].
 *
 * Steps per pixel:
 *   1. read 8-bit sRGB-encoded R,G,B (0..255 -> 0..1)
 *   2. inverse sRGB CCTF -> scene-linear sRGB
 *   3. apply the sRGB->ProPhoto linear 3x3 matrix -> linear ProPhoto RGB float
 */
fun decodeToLinearProPhoto(
    ctx: Context,
    uri: Uri,
    maxEdge: Int = MAX_EDGE_PX,
    isCancelled: () -> Boolean = { false },
    timings: DecodeTimings? = null,
): LinearImage {
    val decodeStart = System.nanoTime()
    val src = decodeDownscaled(ctx, uri, maxEdge)
    timings?.bitmapDecodeMs = (System.nanoTime() - decodeStart) / 1_000_000L
    try {
        // Export-scale (anything above the preview cap) decodes a full-res photo whose linear
        // float buffer (w*h*3*4) is large — 144 MB at 12 MP — so keep it OFF the managed heap.
        return bitmapToLinearProPhoto(
            src,
            offHeap = maxEdge > MAX_EDGE_PX,
            isCancelled = isCancelled,
            timings = timings,
        )
    } finally {
        src.recycle()
    }
}

/**
 * Convert an already-decoded display-sRGB [src] Bitmap to a scene-linear ProPhoto-RGB
 * float [LinearImage] (same inverse-CCTF + sRGB->ProPhoto matrix as
 * [decodeToLinearProPhoto]). Used by both the photo-picker path and the lossy/JPEG-XL
 * DNG ImageDecoder fallback (display-referred). Does not recycle [src].
 *
 * When [offHeap] is true the linear float buffer is allocated NATIVELY (malloc +
 * NewDirectByteBuffer) instead of `ByteBuffer.allocateDirect` (which on Android is a
 * non-movable byte[] on the ~256 MB ART heap) — for a full-res photo (e.g. 12 MP -> 144 MB)
 * the managed allocation OOMs the export. The returned [LinearImage] frees it via onClose.
 * Pixels are read band-by-band so the transient int scratch is a few MB, not IntArray(w*h).
 */
internal fun checkedRgbFloatByteCount(width: Int, height: Int, label: String): Int {
    require(width > 0 && height > 0) { "$label has invalid dimensions ${width}x$height" }
    val bytes = try {
        Math.multiplyExact(
            Math.multiplyExact(width.toLong(), height.toLong()),
            3L * Float.SIZE_BYTES,
        )
    } catch (failure: ArithmeticException) {
        throw IllegalArgumentException("$label dimensions overflow: ${width}x$height", failure)
    }
    require(bytes <= Int.MAX_VALUE) { "$label buffer overflow: $bytes bytes" }
    return bytes.toInt()
}

internal fun checkedRgbFloatWindow(
    data: ByteBuffer,
    width: Int,
    height: Int,
    label: String,
): FloatBuffer = checkedRgbFloatByteWindow(data, width, height, label).asFloatBuffer()

internal fun checkedRgbFloatByteWindow(
    data: ByteBuffer,
    width: Int,
    height: Int,
    label: String,
): ByteBuffer {
    require(data.isDirect) { "$label requires a direct buffer" }
    val requiredBytes = checkedRgbFloatByteCount(width, height, label)
    val logical = data.duplicate().order(ByteOrder.nativeOrder())
    require(logical.remaining() >= requiredBytes) {
        "$label buffer is truncated: ${logical.remaining()} < $requiredBytes bytes"
    }
    logical.limit(logical.position() + requiredBytes)
    return logical.slice().order(ByteOrder.nativeOrder())
}

fun bitmapToLinearProPhoto(
    src: Bitmap,
    offHeap: Boolean = false,
    isCancelled: () -> Boolean = { false },
    timings: DecodeTimings? = null,
): LinearImage {
    val convertStart = System.nanoTime()
    val w = src.width
    val h = src.height
    val byteCount = checkedRgbFloatByteCount(w, h, "bitmap conversion")
    if (isCancelled()) throw CancellationException("bitmap conversion cancelled")
    val nativeOwner = if (offHeap) NativeBufferOwner.allocate(byteCount.toLong()) else null
    val nativeLease = nativeOwner?.acquireDataLease()
    val buf = if (offHeap) {
        checkNotNull(nativeLease).data
    } else {
        ByteBuffer.allocateDirect(byteCount)
    }.order(ByteOrder.nativeOrder())
    val image = try {
        if (nativeLease != null) {
            LinearImage.fromDataLease(buf, w, h, "ProPhoto RGB", nativeLease)
        } else {
            LinearImage(buf, w, h, colorSpace = "ProPhoto RGB")
        }
    } catch (failure: Throwable) {
        nativeOwner?.close()
        throw failure
    }
    nativeOwner?.close()
    val f = buf.asFloatBuffer()
    val m = SRGB_TO_PROPHOTO
    val lut = SRGB_TO_LINEAR_8BIT
    // Read pixels in horizontal strips: avoids a full IntArray(w*h) (4 B/px) managed spike.
    // Half a megapixel per band keeps the two scratch arrays at 2 MB + 6 MB.
    val bandRows = (512 * 1024 / w).coerceIn(1, h)
    var getPixelsNs = 0L
    try {
        val rowPix = IntArray(w * bandRows)
        // Converted band, written with one bulk put: the per-sample indexed put on a direct
        // FloatBuffer was the other half of the old per-pixel cost. Same float expressions in
        // the same order, so the stored values are the ones the per-sample loop produced.
        val band = FloatArray(w * bandRows * 3)
        var y = 0
        while (y < h) {
            if (isCancelled()) throw CancellationException("bitmap conversion cancelled")
            val rows = minOf(bandRows, h - y)
            val t0 = System.nanoTime()
            src.getPixels(rowPix, 0, w, 0, y, w, rows)
            getPixelsNs += System.nanoTime() - t0
            val n = w * rows
            var k = 0
            var o = 0
            while (k < n) {
                val argb = rowPix[k++]
                val rl = lut[(argb shr 16) and 0xFF]
                val gl = lut[(argb shr 8) and 0xFF]
                val bl = lut[argb and 0xFF]
                band[o] = m[0] * rl + m[1] * gl + m[2] * bl
                band[o + 1] = m[3] * rl + m[4] * gl + m[5] * bl
                band[o + 2] = m[6] * rl + m[7] * gl + m[8] * bl
                o += 3
            }
            f.position(y * w * 3)
            f.put(band, 0, n * 3)
            y += rows
        }
        if (isCancelled()) throw CancellationException("bitmap conversion cancelled")
        timings?.let {
            it.getPixelsMs = getPixelsNs / 1_000_000L
            it.convertMs = (System.nanoTime() - convertStart) / 1_000_000L
        }
        return image
    } catch (failure: Throwable) {
        image.close()
        throw failure
    }
}

/**
 * Lossy / JPEG-XL DNG fallback: decode [uri] via the platform image decoder
 * ([BitmapFactory] on every API level) and convert
 * to a display-referred ProPhoto-RGB [LinearImage]. Used when LibRaw throws on a
 * compressed Samsung/Pixel Expert-RAW DNG. The result is display-referred (NOT linear
 * ACES scene data), so it bypasses the spectral scene-linear assumptions — preview/
 * import quality only. Downscaled to [maxEdge]; EXIF orientation is applied by the
 * caller (loadSource) just like any other source.
 *
 * Why not [android.graphics.ImageDecoder]: it applies the codec's encoded origin itself
 * (hwui ImageDecoder::getOrigin), and Skia's RAW codec hands a DNG with a JPEG preview to
 * the JPEG codec WITH the file's EXIF orientation as that origin (SkRawCodec::MakeFromStream).
 * The caller then applied the same orientation a second time, so a portrait DNG on API 28+
 * came out upside down (90 + 90) or back to sideways (180 + 180). BitmapFactory never applies
 * the origin, so the caller's single EXIF pass is the only one on every API level and for
 * both the preview-JPEG and the full dng_sdk routes inside Skia.
 */

/**
 * Subsample factor to ask the platform decoder for when targeting [maxEdge].
 *
 * The decoder only subsamples by integer (in practice power-of-two) factors, so the factor
 * alone cannot land on an arbitrary [maxEdge]; decodeViaPlatform pairs it with an exact
 * downscale afterwards. The factor's job is therefore to land at or ABOVE the target and let
 * the downscale finish, because anything below the target is resolution the downscale can no
 * longer recover.
 *
 * This used to stop at the first factor that was UNDER the target, which threw away up to
 * half the requested resolution: a 4080px DNG previewed at 640 was sampled 1/8 to 510px and
 * the exact downscale below then had nothing left to do, so every fallback preview shipped
 * softer than it was asked for. 1/4 gives 1020px, which downscales exactly onto 640.
 *
 * The intermediate bitmap is ARGB_8888 and is downscaled immediately, so the factor is still
 * the LARGEST one that clears the target — it never overshoots into a needless allocation.
 */
internal fun platformSampleSize(longest: Int, maxEdge: Int): Int {
    if (longest <= 0 || maxEdge <= 0) return 1
    var sample = 1
    while (longest / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

fun decodeViaPlatform(
    ctx: Context,
    uri: Uri,
    maxEdge: Int = MAX_EDGE_PX,
    isCancelled: () -> Boolean = { false },
): LinearImage {
    // BitmapFactory, not ImageDecoder: see the KDoc. The EXIF baseline is applied exactly once,
    // by decodeSourceRequest.
    val bmp: Bitmap = decodeDownscaled(ctx, uri, maxEdge)
    // Hard cap to maxEdge BEFORE the linear conversion. ImageDecoder.setTargetSampleSize
    // (and BitmapFactory inSampleSize) are only hints and are IGNORED for some DNG/RAW
    // decoders, which then hand back a full-resolution bitmap. bitmapToLinearProPhoto would
    // then allocateDirect(w*h*3*4) — on Android a managed (non-movable) byte[] — and a
    // 4080x3060 DNG is a ~150 MB allocation that OOMs the ART heap ("Failed to allocate a
    // 149817619 byte allocation"). Downscale the bitmap (cheap native op) so the float
    // buffer is bounded by maxEdge regardless of whether the decoder honoured the hint.
    val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
    else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
    val longest = max(argb.width, argb.height)
    val safe = if (longest <= maxEdge) {
        argb
    } else {
        val scale = maxEdge.toFloat() / longest
        Bitmap.createScaledBitmap(
            argb,
            (argb.width * scale).toInt().coerceAtLeast(1),
            (argb.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { argb.recycle() }
    }
    try {
        return bitmapToLinearProPhoto(
            safe,
            offHeap = maxEdge > MAX_EDGE_PX,
            isCancelled = isCancelled,
        )
    } finally {
        safe.recycle()
    }
}

/**
 * Read the SOURCE image's EXIF orientation tag from [uri] and map it to an
 * [ExifOrientation] baseline (clockwise rotation + optional horizontal mirror).
 * Returns [ExifOrientation.NONE] if the stream has no EXIF, can't be read, or is
 * already upright. This is applied to the decoded [LinearImage] BEFORE the user's
 * manual rotate steps so JPEG/HEIC imports (and lossy-DNG ImageDecoder fallbacks)
 * appear upright in preview AND export.
 */
fun readExifOrientation(ctx: Context, uri: Uri): ExifOrientation {
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val o = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
            ExifOrientation.fromExif(o)
        } ?: ExifOrientation.NONE
    }.getOrDefault(ExifOrientation.NONE)
}

/**
 * Decode [uri] with inSampleSize so the longest edge is at most [maxEdge].
 *
 * The subsample factor lands at or ABOVE the target and the exact downscale below finishes
 * the job — see [platformSampleSize]. This used to run its own loop that stopped at the first
 * factor UNDER the target, which threw away up to half the requested resolution and left the
 * downscale nothing to do. [decodeViaPlatform] was fixed at the time; this path, which serves
 * the primary photo import, was not, so both now share the one helper.
 */
private fun decodeDownscaled(ctx: Context, uri: Uri, maxEdge: Int = MAX_EDGE_PX): Bitmap {
    val resolver = ctx.contentResolver
    // First pass: read bounds only.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)

    val opts = BitmapFactory.Options().apply {
        inSampleSize = platformSampleSize(longest, maxEdge)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: error("Could not decode image")

    // inSampleSize is power-of-two; do a final exact downscale if still over the cap.
    val long2 = max(decoded.width, decoded.height)
    if (long2 <= maxEdge) return decoded
    val scale = maxEdge.toFloat() / long2
    val scaled = Bitmap.createScaledBitmap(
        decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1), true
    )
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

/** Write baked `.cube` LUT [text] to a SAF [uri] (from CreateDocument). */
fun saveTextToUri(ctx: Context, uri: Uri, text: String) {
    writeVerifiedNewDocument(
        ctx,
        uri,
        text.toByteArray(Charsets.UTF_8),
        maxBytes = 64 * 1024 * 1024,
    )
}

/** Filesystem-safe `<film>_<print>_<size>.<cube|clf>` LUT filename from friendly stock names. */
fun lutFileName(film: String, print: String, size: Int, clf: Boolean): String {
    fun clean(s: String) = s.trim().ifEmpty { "lut" }.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
    return "${clean(film)}_${clean(print)}_$size.${if (clf) "clf" else "cube"}"
}

/**
 * Scene-linear luminance of the export [source] on the gain map grid of a [renderW]x[renderH]
 * render (see [SceneHighlightTap]), or null when the export carries no gain map. Must run while
 * the decoded source is still open, i.e. before the export closes it after simulate.
 */
internal fun captureSceneTapForGainmap(
    context: android.content.Context,
    source: LinearImage,
    params: com.spectrafilm.engine.SpektraParams,
    renderW: Int,
    renderH: Int,
    descriptor: OutputDescriptor,
): SceneLuma? {
    val contract = descriptor.metadata.hdrGainMap ?: return null
    if (Build.VERSION.SDK_INT < 34) return null
    val box = if (params.io.crop) {
        engineCropBox(source.width, source.height, params.io.cropCenter, params.io.cropSize)
            .takeIf { it.width >= 1 && it.height >= 1 }
    } else {
        null
    }
    val mapW = maxOf(1, (renderW + contract.downsample - 1) / contract.downsample)
    val mapH = maxOf(1, (renderH + contract.downsample - 1) / contract.downsample)
    val tap = source.acquireDataLease().use { lease ->
        SceneHighlightTap.capture(
            src = lease.data.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer(),
            srcW = source.width,
            srcH = source.height,
            box = box,
            mapW = mapW,
            mapH = mapH,
        )
    }
    return withFilmHalationGlow(
        tap, params, FilmHalationPreset.forProfile(context, params.filmProfile), maxOf(source.width, source.height), box?.width ?: source.width, renderW,
        contract.downsample,
    )
}
