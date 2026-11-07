/*
 * Spektrafilm for Android -- film border painter. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Paints a [BorderLayout] from [FilmBorderGeometry] around a rendered Bitmap. The output keeps
 * the input's config and colour space, because the Bitmap encoders check the colour space
 * against the export contract. Border colours are sRGB; Skia converts them into a wide-gamut
 * destination, so a ProPhoto export gets the same paper white as an sRGB one.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build

internal object FilmBorderRenderer {

    /**
     * A new Bitmap with [layout]'s border around [photo]. [photo] is not recycled; the caller
     * owns both. [layout] must have been made for [photo]'s size.
     */
    fun render(photo: Bitmap, layout: BorderLayout): Bitmap {
        require(photo.width == layout.image.width && photo.height == layout.image.height) {
            "border layout is for ${layout.image.width}x${layout.image.height}, " +
                "photo is ${photo.width}x${photo.height}"
        }
        val config = photo.config ?: Bitmap.Config.ARGB_8888
        val out = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val space = photo.colorSpace
                ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            Bitmap.createBitmap(layout.outWidth, layout.outHeight, config, photo.hasAlpha(), space)
        } else {
            Bitmap.createBitmap(layout.outWidth, layout.outHeight, config)
        }
        try {
            paint(Canvas(out), photo, layout)
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
        return out
    }

    private fun paint(canvas: Canvas, photo: Bitmap, layout: BorderLayout) {
        canvas.drawColor(layout.background)
        canvas.drawBitmap(photo, layout.image.left.toFloat(), layout.image.top.toFloat(), null)

        if (layout.holes.isNotEmpty()) {
            val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layout.holeColor }
            val r = layout.holeRadius
            val rect = RectF()
            for (h in layout.holes) {
                rect.set(h.left.toFloat(), h.top.toFloat(), h.right.toFloat(), h.bottom.toFloat())
                canvas.drawRoundRect(rect, r, r, holePaint)
            }
        }

        if (layout.texts.isNotEmpty()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = layout.textColor
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.08f
            }
            for (t in layout.texts) {
                // BorderText.size is the cap height; monospace caps are ~0.72 of the em.
                textPaint.textSize = t.size / 0.72f
                if (t.vertical) {
                    canvas.save()
                    canvas.translate(t.x.toFloat(), t.baseline.toFloat())
                    canvas.rotate(-90f)
                    canvas.drawText(t.text, 0f, 0f, textPaint)
                    canvas.restore()
                } else {
                    canvas.drawText(t.text, t.x.toFloat(), t.baseline.toFloat(), textPaint)
                }
            }
        }
    }
}
