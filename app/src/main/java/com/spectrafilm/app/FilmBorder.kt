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
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The frame styles offered in the Frame tab and the export sheet. Names are persisted (settings,
 * sessions, export presets): append new entries, never rename.
 */
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

    /** Instant print: even margin on three sides and a deep one below. */
    INSTANT(R.string.tool_export_border_instant),

    /** Wide gallery mat with a thin keyline around the photo. */
    GALLERY_MAT(R.string.tool_export_border_mat),

    /** Half-frame 35 mm: an 18 x 24 mm frame, so the strip runs across the photo's long edge. */
    HALF_FRAME(R.string.tool_export_border_half),

    /** Paper margin with the ragged black edge a hand-filed negative carrier prints. */
    ROUGH_FILED(R.string.tool_export_border_rough),
    ;

    /** Whether the margin width can be set (strips keep real film proportions). */
    val takesWidth: Boolean
        get() = this == PRINT_WHITE || this == FILED_BLACK || this == INSTANT ||
            this == GALLERY_MAT || this == ROUGH_FILED

    /** Whether the frame colour applies to the frame itself (strips are always film base). */
    val takesColor: Boolean get() = takesWidth

    /** A strip of film rather than a paper margin. */
    val isStrip: Boolean get() = this == STRIP_135 || this == ROLL_120 || this == HALF_FRAME

    companion object {
        /** Lenient parse for stored values: unknown or missing means no border. */
        fun parse(name: String?): FilmBorder =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Frame colour. [STYLE] keeps each style's own colour; [FROM_PHOTO] takes a muted tone from the photo. */
enum class FrameColor(@StringRes val labelRes: Int, val argb: Int?) {
    STYLE(R.string.frame_color_style, null),
    PAPER_WHITE(R.string.frame_color_white, FilmBorderGeometry.PAPER_WHITE),
    CREAM(R.string.frame_color_cream, 0xFFEFE4CF.toInt()),
    GREY(R.string.frame_color_grey, 0xFF8C8986.toInt()),
    BLACK(R.string.frame_color_black, FilmBorderGeometry.FILED_BLACK),
    FROM_PHOTO(R.string.frame_color_photo, null),
    ;

    companion object {
        fun parse(name: String?): FrameColor = entries.firstOrNull { it.name == name } ?: STYLE
    }
}

/** Pad the framed photo out to a fixed aspect, without cropping it. Ratios are width : height. */
enum class FrameAspect(@StringRes val labelRes: Int, val ratioW: Int, val ratioH: Int) {
    ORIGINAL(R.string.frame_aspect_original, 0, 0),
    SQUARE(R.string.frame_aspect_square, 1, 1),
    PORTRAIT_4_5(R.string.frame_aspect_4_5, 4, 5),
    STORY_9_16(R.string.frame_aspect_9_16, 9, 16),
    ;

    companion object {
        fun parse(name: String?): FrameAspect = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/**
 * The frame settings beyond the style. Defaults reproduce the original fixed borders exactly,
 * so a stored style with no look renders as it always did.
 *
 * @property width margin as a fraction of the photo's short edge, or null for the style's own
 */
data class FrameLook(
    val width: Float? = null,
    val color: FrameColor = FrameColor.STYLE,
    val aspect: FrameAspect = FrameAspect.ORIGINAL,
) {
    val isDefault: Boolean get() = this == FrameLook()

    fun toJson(): JSONObject = JSONObject().apply {
        width?.let { put("width", it.toDouble()) }
        put("color", color.name)
        put("aspect", aspect.name)
    }

    companion object {
        /** Width slider range, as a fraction of the short edge. */
        val WIDTH_RANGE: ClosedFloatingPointRange<Float> = 0.005f..0.25f

        /** Lenient: a missing object, key or unknown name falls back to the default. */
        fun fromJson(json: JSONObject?): FrameLook {
            if (json == null) return FrameLook()
            val w = json.optDouble("width", Double.NaN)
            return FrameLook(
                width = if (w.isFinite()) w.toFloat().coerceIn(WIDTH_RANGE) else null,
                color = FrameColor.parse(json.optString("color")),
                aspect = FrameAspect.parse(json.optString("aspect")),
            )
        }
    }
}

/** A complete frame: a style plus its look. */
data class FrameSpec(val style: FilmBorder, val look: FrameLook = FrameLook()) {
    /** True when nothing is added around the photo. */
    val isNone: Boolean get() = style == FilmBorder.NONE && look.aspect == FrameAspect.ORIGINAL

    /** Stable text identity for cache keys: the bare style name when the look is the default. */
    fun key(): String = if (look.isDefault) style.name else buildString {
        append(style.name)
        look.width?.let { append("|w=").append(it) }
        append("|c=").append(look.color.name)
        append("|a=").append(look.aspect.name)
    }

    companion object {
        val NONE = FrameSpec(FilmBorder.NONE)
    }
}

/** Axis-aligned rectangle in output pixels, [left, right) x [top, bottom). */
data class BorderRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One run of edge text: baseline-left anchor, cap height, and the text itself. */
data class BorderText(val x: Int, val baseline: Int, val size: Int, val text: String, val vertical: Boolean)

/** A point in output pixels. */
data class BorderPoint(val x: Int, val y: Int)

/**
 * Everything [FilmBorderRenderer] needs, as plain numbers. Colours are sRGB ARGB ints, except
 * [FilmBorderGeometry.PHOTO_TONE], which the renderer replaces with a tone taken from the photo.
 *
 * Paint order: [background] over the whole output, [frameColor] over [frameRect] (the frame
 * itself; it differs from the background only when the frame is padded to an aspect), the
 * [inkOutline] polygon, the photo at [image], [rules], [holes], then [texts]. The photo is
 * drawn after the ink, so no part of the frame can cover a pixel of the picture.
 *
 * @property outWidth  output width (photo + frame + aspect padding)
 * @property image     where the photo goes, unscaled (image.width == input width)
 * @property holes     perforations, drawn as rounded rectangles with radius [holeRadius]
 * @property rules     thin keylines, painted in a colour that reads on [frameColor]
 * @property inkOutline outer edge of a ragged ink band around the photo, or empty
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
    val frameRect: BorderRect = BorderRect(0, 0, outWidth, outHeight),
    val frameColor: Int = background,
    val rules: List<BorderRect> = emptyList(),
    val inkOutline: List<BorderPoint> = emptyList(),
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

    /**
     * Placeholder colour for [FrameColor.FROM_PHOTO]: fully transparent, which no real frame
     * colour is. [FilmBorderRenderer] swaps it for [mutedTone] of the photo's average.
     */
    const val PHOTO_TONE: Int = 0x00000001

    /** Generic edge label. Not a maker's name. */
    const val EDGE_LABEL = "SPEKTRAFILM"

    // Edge-text metrics shared with FilmBorderRenderer. BorderText.size is the cap height;
    // monospace caps are ~0.72 of the em, glyphs advance 0.6 em, plus the paint's tracking.
    const val TEXT_CAP_HEIGHT_EM = 0.72f
    const val TEXT_LETTER_SPACING_EM = 0.08f
    private const val TEXT_ADVANCE_EM = 0.6f

    /**
     * Upper bound, in pixels, of how far a run of edge text extends along its baseline.
     * Padded 5 % so a font whose advance is a little wider than 0.6 em still cannot overlap.
     */
    fun textRunLength(text: String, capHeight: Int): Int =
        ceil(text.length * capHeight * (TEXT_ADVANCE_EM + TEXT_LETTER_SPACING_EM) / TEXT_CAP_HEIGHT_EM * 1.05)
            .toInt()

    // Default margins as a fraction of the short edge.
    private const val PRINT_MARGIN = 0.06f
    private const val FILED_MARGIN = 0.025f
    private const val INSTANT_MARGIN = 0.06f
    private const val MAT_MARGIN = 0.12f
    private const val ROUGH_MARGIN = 0.06f

    // Instant print: the bottom margin is this many times the side margin.
    private const val INSTANT_BOTTOM = 3.4

    // Gallery mat keyline: offset from the photo as a share of the margin, thickness of the short edge.
    private const val MAT_KEY_OFFSET = 0.18
    private const val MAT_KEY_THICKNESS = 0.002

    // Rough filed edge: ink band and its raggedness, as fractions of the short edge, capped
    // so the ink never runs off the paper.
    private const val ROUGH_BAND = 0.018
    private const val ROUGH_JITTER = 0.012

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

    /** The style's own margin, as a fraction of the short edge (0 for strips and none). */
    fun defaultWidth(style: FilmBorder): Float = when (style) {
        FilmBorder.PRINT_WHITE -> PRINT_MARGIN
        FilmBorder.FILED_BLACK -> FILED_MARGIN
        FilmBorder.INSTANT -> INSTANT_MARGIN
        FilmBorder.GALLERY_MAT -> MAT_MARGIN
        FilmBorder.ROUGH_FILED -> ROUGH_MARGIN
        else -> 0f
    }

    /** The style's own frame colour. */
    fun styleColor(style: FilmBorder): Int = when (style) {
        FilmBorder.FILED_BLACK -> FILED_BLACK
        FilmBorder.STRIP_135, FilmBorder.ROLL_120, FilmBorder.HALF_FRAME -> REBATE
        else -> PAPER_WHITE
    }

    /** Frame number printed on the rebate: stable per source, 1..36. */
    fun frameNumberFor(seed: String?): Int =
        if (seed.isNullOrEmpty()) 1 else Math.floorMod(seed.hashCode(), 36) + 1

    /** [layout] for a bare style with its original look. */
    fun layout(border: FilmBorder, width: Int, height: Int, frameNumber: Int = 1): BorderLayout? =
        layout(FrameSpec(border), width, height, frameNumber)

    /**
     * Layout for [spec] around a [width] x [height] photo, or null when nothing is added
     * ([FrameSpec.isNone]) or the photo is empty. Strip styles run along the photo's long edge
     * (portrait strips are vertical); half-frame runs along the short edge.
     */
    fun layout(spec: FrameSpec, width: Int, height: Int, frameNumber: Int = 1): BorderLayout? {
        if (width <= 0 || height <= 0 || spec.isNone) return null
        val style = spec.style
        val look = spec.look
        val frameColor = when {
            !style.takesColor && style != FilmBorder.NONE -> styleColor(style)
            look.color == FrameColor.FROM_PHOTO -> PHOTO_TONE
            else -> look.color.argb ?: styleColor(style)
        }
        val fraction = (look.width ?: defaultWidth(style)).coerceIn(FrameLook.WIDTH_RANGE)
        val base = when (style) {
            FilmBorder.NONE -> margin(width, height, 0, 0, 0, frameColor)
            FilmBorder.PRINT_WHITE, FilmBorder.FILED_BLACK -> {
                val m = marginPx(width, height, fraction)
                margin(width, height, m, m, m, frameColor)
            }
            FilmBorder.INSTANT -> {
                val m = marginPx(width, height, fraction)
                margin(width, height, m, m, max(m + 1, (m * INSTANT_BOTTOM).roundToInt()), frameColor)
            }
            FilmBorder.GALLERY_MAT -> mat(width, height, marginPx(width, height, fraction), frameColor)
            FilmBorder.ROUGH_FILED -> rough(width, height, marginPx(width, height, fraction), frameColor, frameNumber)
            FilmBorder.STRIP_135 -> strip(width, height, frameNumber, StripKind.S135)
            FilmBorder.ROLL_120 -> strip(width, height, frameNumber, StripKind.R120)
            FilmBorder.HALF_FRAME -> strip(width, height, frameNumber, StripKind.HALF)
        }
        // Aspect padding: the strip keeps its film base, the padding takes the chosen colour
        // (or paper white when the colour is the style's own and the style is a dark strip).
        val padColor = if (look.color == FrameColor.FROM_PHOTO) {
            PHOTO_TONE
        } else {
            look.color.argb ?: if (style.isStrip || style == FilmBorder.NONE) PAPER_WHITE else frameColor
        }
        return padToAspect(base, look.aspect, padColor)
    }

    /** Output size for [border] without building the whole layout. */
    fun outputSize(border: FilmBorder, width: Int, height: Int): Pair<Int, Int> =
        outputSize(FrameSpec(border), width, height)

    /** Output size for [spec] without keeping the layout. */
    fun outputSize(spec: FrameSpec, width: Int, height: Int): Pair<Int, Int> =
        layout(spec, width, height)?.let { it.outWidth to it.outHeight } ?: (width to height)

    /** [photoLongEdgeFor] for a bare style. */
    fun photoLongEdgeFor(border: FilmBorder, width: Int, height: Int, outputLongEdge: Int): Int =
        photoLongEdgeFor(FrameSpec(border), width, height, outputLongEdge)

    /**
     * Long edge to shrink a [width] x [height] photo to, so that photo + [spec] has an output
     * long edge of at most [outputLongEdge]. Never asks to enlarge the photo.
     */
    fun photoLongEdgeFor(spec: FrameSpec, width: Int, height: Int, outputLongEdge: Int): Int {
        val photoLong = max(width, height)
        val (ow, oh) = outputSize(spec, width, height)
        val outLong = max(ow, oh)
        if (outLong <= outputLongEdge) return photoLong
        var edge = min(photoLong, floor(outputLongEdge.toDouble() * photoLong / outLong).toInt())
        // Border sizes round to whole pixels, so the proportional guess can overshoot by one or
        // two; step down until the finished image truly fits.
        while (edge > 1) {
            val (w2, h2) = scaledDimensions(width, height, edge)
            val (fw, fh) = outputSize(spec, w2, h2)
            if (max(fw, fh) <= outputLongEdge) break
            edge--
        }
        return max(1, edge)
    }

    /**
     * A muted frame tone from the photo's average sRGB colour: less than half the saturation,
     * and lightness pulled toward the middle so it reads as a mat rather than as part of the
     * picture. Pure, for the renderer and the tests.
     */
    fun mutedTone(r: Int, g: Int, b: Int): Int {
        val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        val target = 0.2 + 0.6 * luma
        fun channel(c: Int): Int {
            val v = c / 255.0
            val muted = (v - luma) * 0.4 + target
            return (muted.coerceIn(0.0, 1.0) * 255).roundToInt()
        }
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /** Keyline / ink colour that reads on [argb]: dark on a light frame, light on a dark one. */
    fun contrastOn(argb: Int, dark: Int = FILED_BLACK, light: Int = PAPER_WHITE): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luma < 110) light else dark
    }

    private fun marginPx(w: Int, h: Int, fraction: Float): Int =
        max(1, (min(w, h) * fraction.toDouble()).roundToInt())

    private fun margin(w: Int, h: Int, side: Int, top: Int, bottom: Int, color: Int): BorderLayout =
        BorderLayout(
            outWidth = w + 2 * side,
            outHeight = h + top + bottom,
            image = BorderRect(side, top, side + w, top + h),
            background = color,
            holes = emptyList(),
            holeRadius = 0f,
            holeColor = color,
            texts = emptyList(),
            textColor = color,
        )

    private fun mat(w: Int, h: Int, m: Int, color: Int): BorderLayout {
        val base = margin(w, h, m, m, m, color)
        val t = max(1, (min(w, h) * MAT_KEY_THICKNESS).roundToInt())
        val k = max(1, (m * MAT_KEY_OFFSET).roundToInt())
        // The keyline only fits when the margin leaves room for it outside the photo.
        if (k + t > m) return base
        val img = base.image
        val l = img.left - k - t
        val tp = img.top - k - t
        val r = img.right + k + t
        val b = img.bottom + k + t
        return base.copy(
            rules = listOf(
                BorderRect(l, tp, r, tp + t), // top
                BorderRect(l, b - t, r, b), // bottom
                BorderRect(l, tp + t, l + t, b - t), // left
                BorderRect(r - t, tp + t, r, b - t), // right
            ),
        )
    }

    /**
     * Paper margin [m] around the photo with a ragged ink band hugging it. The band's outer edge
     * wanders smoothly (a few summed sines, phased by [seed] so each frame differs but a re-export
     * matches) and is capped to stay on the paper.
     */
    private fun rough(w: Int, h: Int, m: Int, paper: Int, seed: Int): BorderLayout {
        val base = margin(w, h, m, m, m, paper)
        val short = min(w, h).toDouble()
        val band = min(ROUGH_BAND * short, m * 0.5)
        val jitter = min(ROUGH_JITTER * short, m * 0.45)
        if (band < 1.0) return base
        val img = base.image
        val step = max(2, (short / 160).roundToInt())
        val p1 = seed * 1.7
        val p2 = seed * 2.3 + 1.0
        val p3 = seed * 0.9 + 2.0
        fun reach(s: Double): Int {
            val n = 0.55 * kotlin.math.sin(s / (short * 0.05) + p1) +
                0.3 * kotlin.math.sin(s / (short * 0.013) + p2) +
                0.15 * kotlin.math.sin(s / (short * 0.004) + p3)
            return (band + jitter * (0.5 + 0.5 * n)).roundToInt().coerceIn(1, m)
        }
        val pts = ArrayList<BorderPoint>()
        var s = 0.0
        var x = img.left
        while (x < img.right) { pts += BorderPoint(x, img.top - reach(s)); x += step; s += step }
        var y = img.top
        while (y < img.bottom) { pts += BorderPoint(img.right + reach(s), y); y += step; s += step }
        x = img.right
        while (x > img.left) { pts += BorderPoint(x, img.bottom + reach(s)); x -= step; s += step }
        y = img.bottom
        while (y > img.top) { pts += BorderPoint(img.left - reach(s), y); y -= step; s += step }
        return base.copy(inkOutline = pts)
    }

    /** Grow [base] to [aspect] by centring it on a [padColor] canvas. Nothing moves relative to the photo. */
    private fun padToAspect(base: BorderLayout, aspect: FrameAspect, padColor: Int): BorderLayout {
        if (aspect == FrameAspect.ORIGINAL) return base
        val w = base.outWidth.toLong()
        val h = base.outHeight.toLong()
        // Compare w/h with rw/rh in integers.
        val rw = aspect.ratioW.toLong()
        val rh = aspect.ratioH.toLong()
        val (ow, oh) = when {
            w * rh > h * rw -> w to (w * rh + rw - 1) / rw // too wide: add height
            w * rh < h * rw -> (h * rw + rh - 1) / rh to h // too tall: add width
            else -> return base
        }
        val dx = ((ow - w) / 2).toInt()
        val dy = ((oh - h) / 2).toInt()
        fun BorderRect.moved() = BorderRect(left + dx, top + dy, right + dx, bottom + dy)
        return base.copy(
            outWidth = ow.toInt(),
            outHeight = oh.toInt(),
            image = base.image.moved(),
            background = padColor,
            frameRect = base.frameRect.moved(),
            frameColor = base.frameColor,
            holes = base.holes.map { it.moved() },
            rules = base.rules.map { it.moved() },
            inkOutline = base.inkOutline.map { BorderPoint(it.x + dx, it.y + dy) },
            texts = base.texts.map { it.copy(x = it.x + dx, baseline = it.baseline + dy) },
        )
    }

    private enum class StripKind { S135, R120, HALF }

    /**
     * Strip layout computed in "along" (the strip's length) / "across" coordinates and
     * transposed for a vertical strip, so one code path draws both orientations. 135 and 120
     * frames are wider than tall, so their strip runs along the photo's long edge; a half
     * frame is 18 x 24 mm, so its strip runs along the short edge.
     */
    private fun strip(w: Int, h: Int, frameNumber: Int, kind: StripKind): BorderLayout {
        val is120 = kind == StripKind.R120
        val horizontal = if (kind == StripKind.HALF) w < h else w >= h
        val along = if (horizontal) w else h
        val across = if (horizontal) h else w
        val pxPerMm = across / if (is120) S120_SHORT else S135_SHORT
        fun mm(v: Double) = max(1, (v * pxPerMm).roundToInt())

        val rebate = mm(if (is120) S120_REBATE else S135_REBATE)
        val gap = mm(if (is120) S120_GAP else S135_GAP)
        val outAlong = along + 2 * gap
        val outAcross = across + 2 * rebate

        // Rectangles in (along, across) space: a = along axis, c = across axis.
        data class AC(val a0: Int, val c0: Int, val a1: Int, val c1: Int)
        val holesAc = ArrayList<AC>()
        if (!is120) {
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
        // on the rebate on 120. Cap height is ~60 % of the space it lives in (about 1 mm on 135).
        val textBand = if (!is120) mm(S135_HOLE_OUTER) else rebate
        val textSize = max(1, (textBand * 0.6).roundToInt())
        val baselineAcross = outAcross - (textBand - textSize) / 2
        val n = frameNumber.coerceIn(1, 99)
        val label = "$EDGE_LABEL ${if (is120) "120" else "135"}"
        // Each run starts at its preferred spot, pushed along when the run before it is still
        // there (the label is long next to a short frame number). If the set does not fit on
        // the strip that way it is packed tight from the start; if it still does not fit, the
        // frame marker and then the frame number are left off. Text is never drawn past the
        // end of the strip or on top of a neighbour.
        val spacing = 2 * textSize
        fun place(wanted: List<Pair<Double, String>>, packed: Boolean): List<Pair<Int, String>>? {
            var free = gap + spacing
            return wanted.map { (at, t) ->
                val a = if (packed) free else max((gap + along * at).roundToInt(), free)
                val end = a + textRunLength(t, textSize)
                if (end > outAlong - gap) return null
                free = end + spacing
                a to t
            }
        }
        val marker = 0.06 to "▸$n"
        val labelRun = 0.22 to label
        val number = 0.72 to "${n}A"
        val runs = listOf(listOf(marker, labelRun, number), listOf(labelRun, number), listOf(labelRun))
            .firstNotNullOfOrNull { set -> place(set, packed = false) ?: place(set, packed = true) }
            .orEmpty()

        fun rect(r: AC) = if (horizontal) {
            BorderRect(r.a0, r.c0, r.a1, r.c1)
        } else {
            // Vertical: along runs down the page, across runs left to right.
            BorderRect(r.c0, r.a0, r.c1, r.a1)
        }

        val texts = runs.map { (a, t) ->
            if (horizontal) {
                BorderText(x = a, baseline = baselineAcross, size = textSize, text = t, vertical = false)
            } else {
                // Vertical text reads bottom-to-top on the right rebate, so the run starts at
                // the same distance from the BOTTOM that a horizontal run starts from the left.
                BorderText(
                    x = baselineAcross,
                    baseline = outAlong - a,
                    size = textSize,
                    text = t,
                    vertical = true,
                )
            }
        }

        val outW = if (horizontal) outAlong else outAcross
        val outH = if (horizontal) outAcross else outAlong
        val image = if (horizontal) {
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
