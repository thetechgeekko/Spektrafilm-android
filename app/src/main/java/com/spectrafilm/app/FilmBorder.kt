/*
 * Spektrafilm for Android -- film border geometry. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A finished-print / film-strip frame added AROUND the exported photo, so the output grows and
 * no pixel of the picture is covered. Idea credited to SpektraLab (JamesQiu2005/SpektraLab, its
 * film-edge stage) and to hwx959's Spektrafilm-android fork; no code from either is used. Edge
 * text is deliberately generic -- no film-maker names or trademarks.
 *
 * This file is the pure, JVM-testable half: every rectangle and text position in output pixels.
 * [FilmBorderRenderer] paints it. Post-engine and UI-only: no engine param, no parity impact.
 */
package com.spectrafilm.app

import androidx.annotation.StringRes
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The border styles offered in the export sheet. */
enum class FilmBorder(@StringRes val labelRes: Int) {
    NONE(R.string.tool_export_border_none),

    /** Even paper-white margin, like a darkroom print or a gallery mat. */
    PRINT_WHITE(R.string.tool_export_border_print),

    /** Thin black edge, like a print made through a filed-out negative carrier. */
    FILED_BLACK(R.string.tool_export_border_filed),

    /** 35 mm strip: dark rebate, perforations along the long edge, edge numbers. */
    STRIP_135(R.string.tool_export_border_135),

    /** Medium-format roll: wider frame, no perforations, edge numbers. */
    ROLL_120(R.string.tool_export_border_120),
    ;

    companion object {
        /** Lenient parse for stored values: unknown or missing means no border. */
        fun parse(name: String?): FilmBorder =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Axis-aligned rectangle in output pixels, [left, right) x [top, bottom). */
data class BorderRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One run of edge text: baseline-left anchor, cap height, and the text itself. */
data class BorderText(val x: Int, val baseline: Int, val size: Int, val text: String, val vertical: Boolean)

/**
 * Everything [FilmBorderRenderer] needs, as plain numbers. Colours are sRGB ARGB ints.
 *
 * @property outWidth  output width (photo + border)
 * @property image     where the photo goes, unscaled (image.width == input width)
 * @property holes     perforations, drawn as rounded rectangles with radius [holeRadius]
 */
data class BorderLayout(
    val outWidth: Int,
    val outHeight: Int,
    val image: BorderRect,
    val background: Int,
    val holes: List<BorderRect>,
    val holeRadius: Float,
    val holeColor: Int,
    val texts: List<BorderText>,
    val textColor: Int,
)

object FilmBorderGeometry {
    // Paper white is a touch warm, as unbleached fibre paper is; pure #FFFFFF reads as a UI card.
    const val PAPER_WHITE: Int = 0xFFF6F4EE.toInt()
    const val FILED_BLACK: Int = 0xFF0B0A09.toInt()
    // Unexposed base of a scanned, inverted negative: almost black, faintly warm.
    const val REBATE: Int = 0xFF14110E.toInt()
    // Perforations are holes: the scanner's backlight shows through them.
    const val HOLE: Int = 0xFFEDEAE3.toInt()
    // Edge markings are latent-image exposures, they scan as a warm amber.
    const val EDGE_TEXT: Int = 0xFFE3A44A.toInt()

    /** Generic edge label. Not a maker's name. */
    const val EDGE_LABEL = "SPEKTRAFILM"

    // Print margin as a fraction of the short edge; filed carrier edge likewise.
    private const val PRINT_MARGIN = 0.06
    private const val FILED_MARGIN = 0.025

    // 135, in millimetres, for a frame whose short side is 24 mm on 35 mm stock.
    private const val S135_SHORT = 24.0
    private const val S135_REBATE = 5.5 // (35 - 24) / 2
    private const val S135_GAP = 2.0 // to the neighbouring frame
    private const val S135_HOLE_ALONG = 1.98
    private const val S135_HOLE_ACROSS = 2.79
    private const val S135_HOLE_PITCH = 4.75
    private const val S135_HOLE_OUTER = 1.7 // film edge to hole
    private const val S135_HOLE_RADIUS = 0.5

    // 120: 56 mm image on 61.5 mm stock.
    private const val S120_SHORT = 56.0
    private const val S120_REBATE = 2.75
    private const val S120_GAP = 3.5

    /** Frame number printed on the rebate: stable per source, 1..36. */
    fun frameNumberFor(seed: String?): Int =
        if (seed.isNullOrEmpty()) 1 else Math.floorMod(seed.hashCode(), 36) + 1

    /**
     * Layout for [border] around a [width] x [height] photo, or null for [FilmBorder.NONE] or an
     * empty photo. Strip styles run along the photo's long edge (portrait strips are vertical).
     */
    fun layout(border: FilmBorder, width: Int, height: Int, frameNumber: Int = 1): BorderLayout? {
        if (width <= 0 || height <= 0) return null
        return when (border) {
            FilmBorder.NONE -> null
            FilmBorder.PRINT_WHITE -> margin(width, height, PRINT_MARGIN, PAPER_WHITE)
            FilmBorder.FILED_BLACK -> margin(width, height, FILED_MARGIN, FILED_BLACK)
            FilmBorder.STRIP_135 -> strip(width, height, frameNumber, is135 = true)
            FilmBorder.ROLL_120 -> strip(width, height, frameNumber, is135 = false)
        }
    }

    /** Output size for [border] without building the whole layout. */
    fun outputSize(border: FilmBorder, width: Int, height: Int): Pair<Int, Int> =
        layout(border, width, height)?.let { it.outWidth to it.outHeight } ?: (width to height)

    /**
     * Long edge to shrink a [width] x [height] photo to, so that photo + [border] has an output
     * long edge of at most [outputLongEdge]. Never asks to enlarge the photo.
     */
    fun photoLongEdgeFor(border: FilmBorder, width: Int, height: Int, outputLongEdge: Int): Int {
        val photoLong = max(width, height)
        val (ow, oh) = outputSize(border, width, height)
        val outLong = max(ow, oh)
        if (outLong <= outputLongEdge) return photoLong
        var edge = min(photoLong, floor(outputLongEdge.toDouble() * photoLong / outLong).toInt())
        // Border sizes round to whole pixels, so the proportional guess can overshoot by one or
        // two; step down until the finished image truly fits.
        while (edge > 1) {
            val (w2, h2) = scaledDimensions(width, height, edge)
            val (fw, fh) = outputSize(border, w2, h2)
            if (max(fw, fh) <= outputLongEdge) break
            edge--
        }
        return max(1, edge)
    }

    private fun margin(w: Int, h: Int, fraction: Double, color: Int): BorderLayout {
        val m = max(1, (min(w, h) * fraction).roundToInt())
        return BorderLayout(
            outWidth = w + 2 * m,
            outHeight = h + 2 * m,
            image = BorderRect(m, m, m + w, m + h),
            background = color,
            holes = emptyList(),
            holeRadius = 0f,
            holeColor = color,
            texts = emptyList(),
            textColor = color,
        )
    }

    /**
     * Strip layout computed in "along" (the long edge) / "across" coordinates and transposed for
     * a portrait photo, so one code path draws both orientations.
     */
    private fun strip(w: Int, h: Int, frameNumber: Int, is135: Boolean): BorderLayout {
        val landscape = w >= h
        val along = if (landscape) w else h
        val across = if (landscape) h else w
        val pxPerMm = across / if (is135) S135_SHORT else S120_SHORT
        fun mm(v: Double) = max(1, (v * pxPerMm).roundToInt())

        val rebate = mm(if (is135) S135_REBATE else S120_REBATE)
        val gap = mm(if (is135) S135_GAP else S120_GAP)
        val outAlong = along + 2 * gap
        val outAcross = across + 2 * rebate

        // Rectangles in (along, across) space: a = along axis, c = across axis.
        data class AC(val a0: Int, val c0: Int, val a1: Int, val c1: Int)
        val holesAc = ArrayList<AC>()
        if (is135) {
            val holeAlong = S135_HOLE_ALONG * pxPerMm
            val pitch = S135_HOLE_PITCH * pxPerMm
            val count = floor((outAlong - holeAlong) / pitch).toInt() + 1
            // Centre the run of holes on the strip so both ends look the same.
            val first = (outAlong - ((count - 1) * pitch + holeAlong)) / 2.0
            val outer = mm(S135_HOLE_OUTER)
            val holeAcross = mm(S135_HOLE_ACROSS)
            for (i in 0 until count) {
                val a0 = (first + i * pitch).roundToInt()
                val a1 = (first + i * pitch + holeAlong).roundToInt()
                if (a0 < 0 || a1 > outAlong || a1 <= a0) continue
                // Top rebate, then bottom rebate (mirror).
                holesAc += AC(a0, outer, a1, outer + holeAcross)
                holesAc += AC(a0, outAcross - outer - holeAcross, a1, outAcross - outer)
            }
        }

        // Edge text sits on the far rebate: between the film edge and the holes on 135, centred
        // on the rebate on 120. Cap height is ~70 % of the space it lives in.
        val textBand = if (is135) mm(S135_HOLE_OUTER) else rebate
        val textSize = max(1, (textBand * 0.7).roundToInt())
        val baselineAcross = outAcross - (textBand - textSize) / 2
        val n = frameNumber.coerceIn(1, 99)
        val label = "$EDGE_LABEL  ${if (is135) "135" else "120"}"
        val runs = listOf(
            (gap + along * 0.08).roundToInt() to "▸$n",
            (gap + along * 0.34).roundToInt() to label,
            (gap + along * 0.72).roundToInt() to "${n}A",
        )

        fun rect(r: AC) = if (landscape) {
            BorderRect(r.a0, r.c0, r.a1, r.c1)
        } else {
            // Portrait: along runs down the page, across runs left to right.
            BorderRect(r.c0, r.a0, r.c1, r.a1)
        }

        val texts = runs.map { (a, t) ->
            if (landscape) {
                BorderText(x = a, baseline = baselineAcross, size = textSize, text = t, vertical = false)
            } else {
                // Vertical text reads bottom-to-top on the right rebate, so the run starts at
                // the same distance from the BOTTOM that a landscape run starts from the left.
                BorderText(
                    x = baselineAcross,
                    baseline = outAlong - a,
                    size = textSize,
                    text = t,
                    vertical = true,
                )
            }
        }

        val outW = if (landscape) outAlong else outAcross
        val outH = if (landscape) outAcross else outAlong
        val image = if (landscape) {
            BorderRect(gap, rebate, gap + w, rebate + h)
        } else {
            BorderRect(rebate, gap, rebate + w, gap + h)
        }
        return BorderLayout(
            outWidth = outW,
            outHeight = outH,
            image = image,
            background = REBATE,
            holes = holesAc.map(::rect),
            holeRadius = (S135_HOLE_RADIUS * pxPerMm).toFloat(),
            holeColor = HOLE,
            texts = texts,
            textColor = EDGE_TEXT,
        )
    }
}
