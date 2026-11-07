/*
 * Spektrafilm for Android — spatial HDR gain map derived from the render (issue #140). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Replaces the 1x1 uniform placeholder that made "Ultra HDR" an over-promise: it produced a valid
 * Ultra HDR container wrapping an ordinary SDR image with a flat global boost, carrying no
 * per-pixel information from the render at all.
 *
 * The HDR signal is already in the render and was simply being discarded. The engine emits
 * display-referred float RGB, and `packToArgb` clamps it with `min(1f, max(0f, v))` on the way to
 * 8-bit — so every value above SDR white is real, rendered detail that the SDR container cannot
 * hold. The gain map encodes exactly that, per pixel, and nothing else. Where the render never
 * exceeds white, the map is flat and the file honestly says there is no extra headroom.
 *
 * Encoding follows the Android Gainmap contract. For a stored gain value g in [0,1]:
 *
 *     HDR = (SDR + epsilonSdr) * 2^(lerp(log2(ratioMin), log2(ratioMax), g^(1/gamma))) - epsilonHdr
 *
 * so with ratioMin = 1 and gamma = 1 the inverse this file computes is
 *
 *     g = log2((hdr + epsilonHdr) / (sdr + epsilonSdr)) / log2(ratioMax)
 *
 * Two details matter for correctness. The ratio is computed in LINEAR light, decoding the
 * CCTF first, because a ratio of gamma-encoded values is not a ratio of luminances. And a
 * single-channel map takes the maximum across channels rather than a luminance, so recovering a
 * blown highlight cannot leave one channel still clipped.
 *
 * MULTICHANNEL (the default for Ultra HDR export): one gain per colour channel. A single max-gain
 * map multiplies the channels that were NOT clipped by the gain of the one that was, so a
 * coloured highlight -- a red halation glow, a warm sunset -- is restored brighter but shifted
 * toward white. Per-channel gains restore each channel's own above-white value, which is what the
 * film rendered. The scene-referred highlight gain is a luminance, so it applies equally to all
 * three channels.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object HdrGainMap {

    private const val LOG2 = 0.6931471805599453

    /**
     * The computed map plus what it says about the render.
     *
     * [ratioMax] is the headroom actually present, not a constant: the encoder needs it to write
     * the metadata, and it is the honest answer to "how much HDR is in this image". [gainedPixels]
     * and [maxRatio] exist so the pipeline can report whether a given render had any headroom at
     * all rather than leaving it to be assumed.
     */
    class Result(
        /** [channels] bytes per map cell, interleaved (1 = one shared gain, 3 = R,G,B). */
        val alpha: ByteArray,
        val width: Int,
        val height: Int,
        val ratioMax: Float,
        val maxRatio: Float,
        val gainedPixels: Int,
        /** Map cells where the scene tap raised the gain (0 when no scene was used). */
        val sceneCells: Int = 0,
        /** Scene-to-render exposure factor, or NaN when no scene was used. */
        val exposureMatch: Float = Float.NaN,
        val channels: Int = 1,
    ) {
        /** True when the render genuinely exceeded SDR white somewhere. */
        val hasHeadroom: Boolean get() = ratioMax > 1.0001f
    }

    /**
     * Extend [map] (made for the photo, `layout.image`) to cover a film border laid out by
     * [layout] around that photo. The border is SDR paint, so it gets the neutral gain:
     * stored 0, i.e. a ratio of ratioMin = 1 under this file's contract. The photo's map is
     * copied unchanged at the same relative scale, so its gains stay over the same pixels.
     */
    fun padForBorder(map: Result, layout: BorderLayout): Result {
        val imageWidth = layout.image.width
        val imageHeight = layout.image.height
        val sx = map.width.toDouble() / imageWidth
        val sy = map.height.toDouble() / imageHeight
        val left = (layout.image.left * sx).roundToInt()
        val top = (layout.image.top * sy).roundToInt()
        val outW = max(left + map.width, (layout.outWidth * sx).roundToInt())
        val outH = max(top + map.height, (layout.outHeight * sy).roundToInt())
        val n = map.channels
        val alpha = ByteArray(outW * outH * n)
        for (y in 0 until map.height) {
            System.arraycopy(
                map.alpha, y * map.width * n, alpha, ((top + y) * outW + left) * n, map.width * n,
            )
        }
        return Result(
            alpha, outW, outH, map.ratioMax, map.maxRatio, map.gainedPixels,
            map.sceneCells, map.exposureMatch, n,
        )
    }

    /**
     * Build the gain map for [rgb], interleaved display-referred float RGB of [width]x[height].
     *
     * Read through a FloatBuffer with absolute indexing rather than a float array: the export
     * buffer is ~150 MB at 12 MP and peak RSS already reaches 1.8 GB, so copying it to build a
     * gain map would be the single most expensive thing in the export. Absolute gets also leave
     * the buffer's position untouched, so the caller's lease is unaffected.
     *
     * [downsample] shrinks the map relative to the image; gain maps are low-frequency by nature
     * and full-resolution ones waste space for no visible benefit. [ratioMaxCeiling] caps how much
     * headroom may be claimed, so a few stray overshooting pixels cannot stretch the whole map's
     * scale and flatten the real detail.
     */
    fun compute(
        rgb: FloatBuffer,
        width: Int,
        height: Int,
        colorSpace: ColorSpace,
        cctfEncoded: Boolean,
        downsample: Int,
        ratioMaxCeiling: Float,
        epsilonSdr: Float,
        epsilonHdr: Float,
        scene: SceneLuma? = null,
        multichannel: Boolean = false,
    ): Result {
        require(width > 0 && height > 0) { "gain map needs a non-empty image" }
        require(downsample >= 1) { "downsample must be >= 1" }
        val mapW = max(1, (width + downsample - 1) / downsample)
        val mapH = max(1, (height + downsample - 1) / downsample)
        val n = if (multichannel) 3 else 1
        if (scene != null && scene.width == mapW && scene.height == mapH) {
            computeWithScene(
                rgb, width, height, colorSpace, cctfEncoded, downsample, mapW, mapH,
                ratioMaxCeiling, epsilonSdr, epsilonHdr, scene, n,
            )?.let { return it }
        }

        // Pass 1: the headroom actually present. Encoding against a fixed ceiling instead would
        // waste most of the 8-bit range on an image that only just exceeds white.
        var maxRatio = 1f
        var gained = 0
        var i = 0
        val pixels = width * height
        while (i < pixels) {
            val ratio = pixelRatio(rgb, i * 3, colorSpace, cctfEncoded, epsilonSdr, epsilonHdr)
            if (ratio > 1.0001f) gained++
            if (ratio > maxRatio) maxRatio = ratio
            i++
        }
        val ratioMax = min(max(maxRatio, 1f), ratioMaxCeiling)

        val alpha = ByteArray(mapW * mapH * n)
        if (ratioMax <= 1.0001f) {
            // No headroom: a zero map is a correct, no-op gain map. The container stays valid and
            // truthfully claims nothing.
            return Result(alpha, mapW, mapH, 1f, maxRatio, gained, channels = n)
        }

        val logRatioMax = ln(ratioMax.toDouble()) / LOG2
        for (my in 0 until mapH) {
            val y0 = my * downsample
            val y1 = min(y0 + downsample, height)
            for (mx in 0 until mapW) {
                val x0 = mx * downsample
                val x1 = min(x0 + downsample, width)
                // Average the gain over the block rather than point-sampling, so a single bright
                // pixel does not become a hard edge in the map.
                for (c in 0 until n) {
                    var sum = 0.0
                    var count = 0
                    for (y in y0 until y1) {
                        val row = y * width
                        for (x in x0 until x1) {
                            val o = (row + x) * 3
                            val ratio = if (n == 1) {
                                pixelRatio(rgb, o, colorSpace, cctfEncoded, epsilonSdr, epsilonHdr)
                            } else {
                                channelRatio(rgb, o, c, colorSpace, cctfEncoded, epsilonSdr, epsilonHdr)
                            }
                            val g = (ln(ratio.toDouble()) / LOG2) / logRatioMax
                            sum += if (g < 0.0) 0.0 else if (g > 1.0) 1.0 else g
                            count++
                        }
                    }
                    val g = if (count == 0) 0.0 else sum / count
                    alpha[(my * mapW + mx) * n + c] = quantize(g.toFloat(), mx, my)
                }
            }
        }
        return Result(alpha, mapW, mapH, ratioMax, maxRatio, gained, channels = n)
    }

    /** Midtone band used to match the scene's exposure to the render's. */
    private const val MATCH_LO = 0.10f
    private const val MATCH_HI = 0.50f
    /** Fewest midtone cells for a trustworthy exposure match; fewer and the scene is ignored. */
    private const val MIN_MATCH_CELLS = 64
    private const val LUT_SIZE = 4096
    /** The scene contributes only from here (SDR linear luminance) up, fully from [SCENE_FULL]. */
    private const val SCENE_START = 0.55f
    private const val SCENE_FULL = 0.90f

    /**
     * The render-derived gain combined with a scene-referred highlight gain.
     *
     * Per map cell: `sdr` = the render's linear luminance (clipped to SDR, as the 8-bit base
     * holds it), `sceneY` = the source's scene-linear luminance. An exposure factor `k` (median
     * of sdr/sceneY over midtone cells) puts the scene on the render's scale; the scene gain is
     * then `k*sceneY/sdr`, applied only as the render rises into its highlights (smoothstep from
     * [SCENE_START] to [SCENE_FULL]), so the film's tone below them is unchanged on any display.
     * The cell's gain is the larger of that and the render's own above-white gain. Returns null
     * when the midtones are too few to match exposure (the caller falls back to render-only).
     */
    private fun computeWithScene(
        rgb: FloatBuffer,
        width: Int,
        height: Int,
        colorSpace: ColorSpace,
        cctfEncoded: Boolean,
        downsample: Int,
        mapW: Int,
        mapH: Int,
        ratioMaxCeiling: Float,
        epsilonSdr: Float,
        epsilonHdr: Float,
        scene: SceneLuma,
        n: Int,
    ): Result? {
        val cells = mapW * mapH
        // One CCTF decode per channel per pixel is ~36M pow() calls at 12 MP; a LUT over the
        // clipped [0,1] range is plenty for luminance that only gates and matches exposure.
        val decodeLut = FloatArray(LUT_SIZE) { i ->
            OutputCctf.decode(colorSpace, i / (LUT_SIZE - 1f), cctfEncoded)
        }
        val sdrY = FloatArray(cells)
        val renderLog = FloatArray(cells * n)
        // One map row per task, in parallel: this pass reads every pixel of the export (12-50 MP)
        // and was ~0.8 s single-threaded on a phone. Every cell is computed by one task in a
        // fixed order and the per-row tallies are reduced in row order afterwards, so the result
        // does not depend on the thread count or scheduling.
        val rowGained = IntArray(mapH)
        val rowMax = FloatArray(mapH) { 1f }
        java.util.stream.IntStream.range(0, mapH).parallel().forEach { my ->
            val logSum = DoubleArray(n)
            var gainedRow = 0
            var maxRow = 1f
            val y0 = my * downsample
            val y1 = min(y0 + downsample, height)
            for (mx in 0 until mapW) {
                val x0 = mx * downsample
                val x1 = min(x0 + downsample, width)
                var lumSum = 0.0
                logSum.fill(0.0)
                var count = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        val o = (row + x) * 3
                        val ratio = pixelRatio(rgb, o, colorSpace, cctfEncoded, epsilonSdr, epsilonHdr)
                        if (ratio > 1.0001f) gainedRow++
                        if (ratio > maxRow) maxRow = ratio
                        if (n == 1) {
                            if (ratio > 1f) logSum[0] += ln(ratio.toDouble()) / LOG2
                        } else if (ratio > 1f) {
                            for (ch in 0 until 3) {
                                val r = channelRatio(rgb, o, ch, colorSpace, cctfEncoded, epsilonSdr, epsilonHdr)
                                if (r > 1f) logSum[ch] += ln(r.toDouble()) / LOG2
                            }
                        }
                        lumSum += sdrLuminance(rgb, o, decodeLut)
                        count++
                    }
                }
                val c = my * mapW + mx
                if (count > 0) {
                    sdrY[c] = (lumSum / count).toFloat()
                    for (ch in 0 until n) renderLog[c * n + ch] = (logSum[ch] / count).toFloat()
                }
            }
            rowGained[my] = gainedRow
            rowMax[my] = maxRow
        }
        var maxRatio = 1f
        var gained = 0
        for (my in 0 until mapH) {
            gained += rowGained[my]
            if (rowMax[my] > maxRatio) maxRatio = rowMax[my]
        }

        // Exposure match on the midtones.
        val ratios = ArrayList<Float>()
        for (c in 0 until cells) {
            val sy = scene.y[c]
            if (sdrY[c] in MATCH_LO..MATCH_HI && sy > 1e-5f) ratios.add(sdrY[c] / sy)
        }
        if (ratios.size < MIN_MATCH_CELLS) return null
        ratios.sort()
        val k = ratios[ratios.size / 2]
        if (!(k > 0f) || !k.isFinite()) return null

        val logs = FloatArray(cells * n)
        var maxLog = 0f
        var sceneGained = 0
        for (c in 0 until cells) {
            val sdr = sdrY[c]
            val w = smoothstep(SCENE_START, SCENE_FULL, sdr)
            var sceneLog = 0f
            if (w > 0f) {
                val g = (k * scene.y[c] + epsilonHdr) / (sdr + epsilonSdr)
                if (g > 1f) sceneLog = w * (ln(g.toDouble()) / LOG2).toFloat()
            }
            if (sceneLog > 0.001f) sceneGained++
            for (ch in 0 until n) {
                val l = max(renderLog[c * n + ch], sceneLog)
                logs[c * n + ch] = l
                if (l > maxLog) maxLog = l
            }
        }
        val logCeiling = (ln(ratioMaxCeiling.toDouble()) / LOG2).toFloat()
        val logRatioMax = min(min(maxLog, highlightPercentileLog(logs) + PERCENTILE_MARGIN_EV), logCeiling)
        val alpha = ByteArray(cells * n)
        if (logRatioMax <= 0.0001f) {
            return Result(alpha, mapW, mapH, 1f, maxRatio, gained, sceneCells = 0, exposureMatch = k, channels = n)
        }
        // Anything the scale does not reach rolls off through a soft shoulder rather than
        // clipping flat; with no such outliers (maxLog within the scale) the map is linear.
        val shoulder = maxLog > logRatioMax + 0.0001f
        val knee = SHOULDER_KNEE * logRatioMax
        for (i in 0 until cells * n) {
            var l = logs[i]
            if (shoulder && l > knee) l = knee + (logRatioMax - knee) * (1f - exp(-(l - knee) / (logRatioMax - knee)))
            val g = (l / logRatioMax).coerceIn(0f, 1f)
            val c = i / n
            alpha[i] = quantize(g, c % mapW, c / mapW)
        }
        val ratioMax = Math.pow(2.0, logRatioMax.toDouble()).toFloat()
        return Result(alpha, mapW, mapH, ratioMax, maxRatio, gained, sceneGained, k, n)
    }

    /**
     * The headroom scale is fitted to the bright END of the highlights, not their single brightest
     * cell: the 99.9th percentile of the cells that gain at all, plus [PERCENTILE_MARGIN_EV]. A
     * sun or a specular glint then cannot stretch the scale and leave every other highlight in
     * the bottom of the 8-bit range (and, on a panel with less headroom than the file claims,
     * scaled down with it). Percentile over GAINED cells, not all cells, so a small light source
     * in a dark frame still gets its headroom. rawr fits its ceiling the same way.
     */
    private fun highlightPercentileLog(logs: FloatArray): Float {
        var count = 0
        for (l in logs) if (l > 0.001f) count++
        if (count == 0) return 0f
        val gainedLogs = FloatArray(count)
        var j = 0
        for (l in logs) if (l > 0.001f) gainedLogs[j++] = l
        gainedLogs.sort()
        return gainedLogs[min(count - 1, (count * HIGHLIGHT_PERCENTILE).toInt())]
    }

    /**
     * 8-bit code for a normalized gain [g], with a 4x4 ordered dither: a smooth HDR sky spans few
     * codes at quarter resolution, and plain rounding bands it. Deterministic (no RNG), and exact
     * at both ends, so a no-gain cell stays 0 and a full-gain cell stays 255.
     */
    private fun quantize(g: Float, x: Int, y: Int): Byte {
        val t = (BAYER4[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f
        return floor(g.coerceIn(0f, 1f) * 255f + t).toInt().coerceIn(0, 255).toByte()
    }

    private val BAYER4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private const val HIGHLIGHT_PERCENTILE = 0.999f
    private const val PERCENTILE_MARGIN_EV = 0.25f
    private const val SHOULDER_KNEE = 0.8f

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Linear luminance of one pixel as the SDR base holds it (each channel clipped to white).
     * Rec.709 weights for every output space: an approximation, but this only gates where the
     * scene contributes and matches exposure, both of which are ratios within one image.
     */
    private fun sdrLuminance(rgb: FloatBuffer, offset: Int, decodeLut: FloatArray): Float {
        fun ch(i: Int): Float {
            val e = rgb.get(offset + i)
            if (e.isNaN()) return 0f
            return decodeLut[(e.coerceIn(0f, 1f) * (LUT_SIZE - 1) + 0.5f).toInt()]
        }
        return 0.2126f * ch(0) + 0.7152f * ch(1) + 0.0722f * ch(2)
    }

    /**
     * Linear-light HDR-to-SDR ratio for one pixel, taken as the largest of the three channels so
     * that restoring a highlight cannot leave a channel clipped behind.
     */
    private fun pixelRatio(
        rgb: FloatBuffer,
        offset: Int,
        colorSpace: ColorSpace,
        cctfEncoded: Boolean,
        epsilonSdr: Float,
        epsilonHdr: Float,
    ): Float {
        var worst = 1f
        for (channel in 0 until 3) {
            val ratio = channelRatio(rgb, offset, channel, colorSpace, cctfEncoded, epsilonSdr, epsilonHdr)
            if (ratio > worst) worst = ratio
        }
        return worst
    }

    /** Linear-light HDR-to-SDR ratio of one channel of one pixel (1 at or below white). */
    private fun channelRatio(
        rgb: FloatBuffer,
        offset: Int,
        channel: Int,
        colorSpace: ColorSpace,
        cctfEncoded: Boolean,
        epsilonSdr: Float,
        epsilonHdr: Float,
    ): Float {
        val encoded = rgb.get(offset + channel)
        if (encoded.isNaN()) return 1f
        if (encoded <= 1f) return 1f // at or below white: this channel needs no gain
        val hdrLinear = OutputCctf.decode(colorSpace, encoded, cctfEncoded)
        val sdrLinear = OutputCctf.decode(colorSpace, 1f, cctfEncoded)
        return (hdrLinear + epsilonHdr) / (sdrLinear + epsilonSdr)
    }
}
