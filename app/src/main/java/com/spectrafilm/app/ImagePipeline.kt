/*
 * SpectraFilm for Android — image import/export helpers. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Converts a display-referred (sRGB) Bitmap into the scene-linear ProPhoto-RGB
 * float buffer the engine expects, and writes a rendered Bitmap to the gallery.
 */
package com.spectrafilm.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.spectrafilm.engine.LinearImage
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

/** Cap the longest edge for full-resolution renders so on-device memory stays bounded. */
const val MAX_EDGE_PX = 2048

/** Longest-edge cap for fast interactive previews. */
const val PREVIEW_EDGE_PX = 720

/** Inverse sRGB CCTF: display sRGB (0..1) -> scene-linear (0..1). */
private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055) / 1.055), 2.4).toFloat()

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
fun linearToDisplayBitmap(img: LinearImage): Bitmap {
    val w = img.width
    val h = img.height
    val f = img.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
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
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
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
fun decodeToLinearProPhoto(ctx: Context, uri: Uri, maxEdge: Int = MAX_EDGE_PX): LinearImage {
    val src = decodeDownscaled(ctx, uri, maxEdge)
    try {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val buf = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
        val f = buf.asFloatBuffer()
        val m = SRGB_TO_PROPHOTO
        for (p in pixels.indices) {
            val argb = pixels[p]
            val rl = srgbToLinear(((argb shr 16) and 0xFF) / 255f)
            val gl = srgbToLinear(((argb shr 8) and 0xFF) / 255f)
            val bl = srgbToLinear((argb and 0xFF) / 255f)
            val pr = m[0] * rl + m[1] * gl + m[2] * bl
            val pg = m[3] * rl + m[4] * gl + m[5] * bl
            val pb = m[6] * rl + m[7] * gl + m[8] * bl
            val i = p * 3
            f.put(i, pr); f.put(i + 1, pg); f.put(i + 2, pb)
        }
        return LinearImage(buf, w, h, colorSpace = "ProPhoto RGB")
    } finally {
        src.recycle()
    }
}

/** Decode [uri] with inSampleSize so the longest edge is at most [maxEdge]. */
private fun decodeDownscaled(ctx: Context, uri: Uri, maxEdge: Int = MAX_EDGE_PX): Bitmap {
    val resolver = ctx.contentResolver
    // First pass: read bounds only.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)

    var sample = 1
    while (longest / sample > maxEdge) sample *= 2

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
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
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        ?: error("Could not open output for LUT")
}

/** Filesystem-safe `<film>_<print>.cube` filename from friendly stock names. */
fun cubeFileName(film: String, print: String): String {
    fun clean(s: String) = s.trim().ifEmpty { "lut" }.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
    return "${clean(film)}_${clean(print)}.cube"
}

enum class ExportFormat(val display: String, val mime: String, val ext: String) {
    PNG("PNG", "image/png", "png"),
    JPEG("JPEG", "image/jpeg", "jpg"),
}

/**
 * Save [bmp] to the public gallery under Pictures/SpectraFilm.
 * Uses scoped storage (MediaStore RELATIVE_PATH + IS_PENDING) on API 29+, and the
 * legacy direct-file + MediaStore insert path below that. Returns the content [Uri].
 */
fun saveToGallery(ctx: Context, bmp: Bitmap, format: ExportFormat, jpegQuality: Int = 95): Uri {
    val name = "SpectraFilm_${System.currentTimeMillis()}.${format.ext}"
    val compress = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    val quality = if (format == ExportFormat.PNG) 100 else jpegQuality.coerceIn(1, 100)

    val resolver = ctx.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SpectraFilm")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { bmp.compress(compress, quality, it) }
            ?: error("Could not open output stream")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // Legacy (API 24..28): write to the public Pictures dir, then register with MediaStore.
    @Suppress("DEPRECATION")
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "SpectraFilm"
    ).apply { mkdirs() }
    val file = File(dir, name)
    FileOutputStream(file).use { bmp.compress(compress, quality, it) }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, format.mime)
        @Suppress("DEPRECATION")
        put(MediaStore.Images.Media.DATA, file.absolutePath)
    }
    return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: Uri.fromFile(file)
}
