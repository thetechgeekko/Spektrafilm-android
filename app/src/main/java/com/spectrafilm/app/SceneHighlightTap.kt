/*
 * Spektrafilm for Android — scene-referred highlight tap for the Ultra HDR gain map. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Scene-linear luminance of the export's source, sampled on the gain map's grid.
 *
 * Why: the film render is display-referred and, with the engine's default output handling
 * (LEGACY_CLIP), clipped to [0,1], and a print look's shoulder rolls highlights off below white
 * anyway. So the render alone almost never carries values above SDR white and the render-derived
 * gain map (#140) comes out flat: an "Ultra HDR" file with no HDR in it. The highlight detail the
 * film compressed is still in the SCENE (a RAW decode keeps it above 1.0). rawr's gain map taps
 * the scene the same way (exposure-matched scene light in the highlights); this is the same idea
 * on our pipeline, kept in the highlights only so the SDR film look is untouched below them.
 *
 * Geometry: the engine renders the source cut to its crop box (io.crop) and may resample it to
 * the output size, so map cell (mx, my) covers the same normalized area of that box. Each cell
 * averages a 2x2 grid of source samples, enough for a quarter-resolution, low-frequency map.
 */
internal class SceneLuma(val width: Int, val height: Int, val y: FloatArray)

internal object SceneHighlightTap {
    // Linear ProPhoto (ROMM, D50) RGB -> Y.
    private const val KR = 0.2880402f
    private const val KG = 0.7118741f
    private const val KB = 0.0000857f

    /**
     * Sample [src] (interleaved linear ProPhoto RGB, [srcW]x[srcH]) inside [box] onto a
     * [mapW]x[mapH] grid. [box] null means the whole frame.
     */
    fun capture(
        src: FloatBuffer,
        srcW: Int,
        srcH: Int,
        box: CropBox?,
        mapW: Int,
        mapH: Int,
    ): SceneLuma {
        require(srcW > 0 && srcH > 0 && mapW > 0 && mapH > 0) { "scene tap needs non-empty sizes" }
        val bx0 = box?.x0 ?: 0
        val by0 = box?.y0 ?: 0
        val bw = (box?.width ?: srcW).coerceIn(1, srcW - bx0)
        val bh = (box?.height ?: srcH).coerceIn(1, srcH - by0)
        val out = FloatArray(mapW * mapH)
        for (my in 0 until mapH) {
            for (mx in 0 until mapW) {
                var sum = 0f
                var n = 0
                for (sy in 0 until 2) {
                    val fy = (my + (sy + 0.5f) / 2f) / mapH
                    val py = by0 + min(bh - 1, (fy * bh).toInt())
                    for (sx in 0 until 2) {
                        val fx = (mx + (sx + 0.5f) / 2f) / mapW
                        val px = bx0 + min(bw - 1, (fx * bw).toInt())
                        val o = (py * srcW + px) * 3
                        val lum = KR * src.get(o) + KG * src.get(o + 1) + KB * src.get(o + 2)
                        if (lum.isFinite()) {
                            sum += max(0f, lum)
                            n++
                        }
                    }
                }
                out[my * mapW + mx] = if (n == 0) 0f else sum / n
            }
        }
        return SceneLuma(mapW, mapH, out)
    }
}
