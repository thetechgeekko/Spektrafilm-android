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

enum class ExportFormat(val display: String, val mime: String, val ext: String) {
    PNG("PNG", "image/png", "png"),
    JPEG("JPEG", "image/jpeg", "jpg"),
}

/**
 * Save [bmp] to the public gallery under Pictures/SpectraFilm.
 * Uses scoped storage (MediaStore RELATIVE_PATH + IS_PENDING) on API 29+, and the
 * legacy direct-file + MediaStore insert path below that. Returns the content [Uri].
 */
fun saveToGallery(ctx: Context, bmp: Bitmap, format: ExportFormat): Uri {
    val name = "SpectraFilm_${System.currentTimeMillis()}.${format.ext}"
    val compress = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    val quality = if (format == ExportFormat.PNG) 100 else 95

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
