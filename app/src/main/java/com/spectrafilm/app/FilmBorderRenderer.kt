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
import android.graphics.Path
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
        // FROM_PHOTO frames carry a placeholder colour; resolve it once from the photo.
        val tone by lazy(LazyThreadSafetyMode.NONE) { photoTone(photo) }
        fun resolve(c: Int) = if (c == FilmBorderGeometry.PHOTO_TONE) tone else c
        val background = resolve(layout.background)
        val frame = resolve(layout.frameColor)
        canvas.drawColor(background)
        if (frame != background) {
            val r = layout.frameRect
            canvas.drawRect(
                r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(),
                Paint().apply { color = frame },
            )
        }
        if (layout.inkOutline.size >= 3) {
            val path = Path()
            layout.inkOutline.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            path.close()
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FilmBorderGeometry.contrastOn(frame) })
        }
        // The photo goes over the ink, so nothing the frame paints can cover the picture.
        canvas.drawBitmap(photo, layout.image.left.toFloat(), layout.image.top.toFloat(), null)

        if (layout.rules.isNotEmpty()) {
            val rulePaint = Paint().apply {
                // A keyline reads as a pencil line, not a stripe: dark or light at partial strength.
                color = FilmBorderGeometry.contrastOn(frame) and 0x00FFFFFF or (0x99 shl 24)
            }
            for (r in layout.rules) {
                canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), rulePaint)
            }
        }

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
                letterSpacing = FilmBorderGeometry.TEXT_LETTER_SPACING_EM
            }
            for (t in layout.texts) {
                // BorderText.size is the cap height; monospace caps are ~0.72 of the em.
                textPaint.textSize = t.size / FilmBorderGeometry.TEXT_CAP_HEIGHT_EM
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

    /** [FilmBorderGeometry.mutedTone] of [photo]'s average colour, sampled from a tiny copy. */
    private fun photoTone(photo: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(photo, TONE_SAMPLE, TONE_SAMPLE, true)
        try {
            var r = 0L
            var g = 0L
            var b = 0L
            val px = IntArray(TONE_SAMPLE * TONE_SAMPLE)
            small.getPixels(px, 0, TONE_SAMPLE, 0, 0, TONE_SAMPLE, TONE_SAMPLE)
            for (c in px) {
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            val n = px.size
            return FilmBorderGeometry.mutedTone((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } finally {
            if (small !== photo) small.recycle()
        }
    }

    private const val TONE_SAMPLE = 16
}
