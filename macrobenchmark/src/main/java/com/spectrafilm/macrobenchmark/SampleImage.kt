// Spektrafilm for Android — macrobenchmark sample image (#459). GPLv3.
package com.spectrafilm.macrobenchmark

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A synthetic 12 MP JPEG in the shared Pictures collection, opened by the app through the
 * same ACTION_VIEW route a gallery "Open with" uses. Generated, not a photo: no licence to
 * track and no personal picture on the device, while still a full-size decode + render.
 * Written once per test-APK install; later runs reuse the row this package owns.
 */
internal object SampleImage {
    private const val DISPLAY_NAME = "spektrafilm-benchmark-sample.jpg"
    private const val WIDTH = 4000
    private const val HEIGHT = 3000

    fun uri(): Uri = find() ?: insert()

    private fun resolver() = InstrumentationRegistry.getInstrumentation().context.contentResolver

    private fun find(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        resolver().query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(DISPLAY_NAME),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            }
        }
        return null
    }

    private fun insert(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, DISPLAY_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpektrafilmBenchmark")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = resolver()
        val uri = checkNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ) { "MediaStore refused the benchmark sample" }
        val bitmap = render()
        try {
            checkNotNull(resolver.openOutputStream(uri)) { "no stream for $uri" }.use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "JPEG encode failed" }
            }
        } finally {
            bitmap.recycle()
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    /** Sky-to-ground gradient with a warm highlight: shadows, mids and a near-clipped sun. */
    private fun render(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, HEIGHT.toFloat(),
            intArrayOf(0xFF1E3A6E.toInt(), 0xFFE8A060.toInt(), 0xFF2B2418.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = RadialGradient(
            WIDTH * 0.7f, HEIGHT * 0.45f, HEIGHT * 0.25f,
            intArrayOf(0xFFFFF4D6.toInt(), 0x00FFF4D6),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        return bitmap
    }
}
