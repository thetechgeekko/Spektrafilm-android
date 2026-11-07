/*
 * Spektrafilm for Android — scene-referred highlight tap for the Ultra HDR gain map. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import java.nio.FloatBuffer
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneHighlightTapTest {

    private fun srgbEncode(l: Float): Float =
        if (l <= 0.0031308f) 12.92f * l else (1.055f * l.toDouble().pow(1 / 2.4) - 0.055).toFloat()

    @Test
    fun captureAveragesInsideTheCropBox() {
        // 4x2 source: left half luminance 1, right half 9 (grey pixels, so Y == value).
        val w = 4
        val h = 2
        val src = FloatArray(w * h * 3) { i -> if ((i / 3) % w < 2) 1f else 9f }
        val whole = SceneHighlightTap.capture(FloatBuffer.wrap(src), w, h, null, 2, 1)
        assertEquals(1f, whole.y[0], 1e-4f)
        assertEquals(9f, whole.y[1], 1e-4f)
        // Box over the right half only: both cells see 9.
        val right = SceneHighlightTap.capture(FloatBuffer.wrap(src), w, h, CropBox(2, 0, 2, 2), 2, 1)
        assertEquals(9f, right.y[0], 1e-4f)
        assertEquals(9f, right.y[1], 1e-4f)
    }

    /**
     * A 3-band render: midtones (linear 0.2), a bright band the film rolled off below white
     * (linear 0.935, encoded 0.97, so the render-only map is flat), and a mid-grey band whose
     * scene is very bright (must stay untouched: the scene only acts in the render's highlights).
     */
    private fun bands(w: Int, h: Int): Pair<FloatArray, SceneLuma> {
        val mid = srgbEncode(0.2f)
        val hi = 0.97f
        val rgb = FloatArray(w * h * 3)
        val scene = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val band = x * 3 / w
            val enc = if (band == 1) hi else mid
            val p = (y * w + x) * 3
            rgb[p] = enc; rgb[p + 1] = enc; rgb[p + 2] = enc
            scene[y * w + x] = when (band) {
                0 -> 0.2f
                1 -> 3.0f
                else -> 10f
            }
        }
        return rgb to SceneLuma(w, h, scene)
    }

    private fun gain(rgb: FloatArray, w: Int, h: Int, scene: SceneLuma?) = HdrGainMap.compute(
        rgb = FloatBuffer.wrap(rgb),
        width = w,
        height = h,
        colorSpace = ColorSpace.SRGB,
        cctfEncoded = true,
        downsample = 1,
        ratioMaxCeiling = 8f,
        epsilonSdr = 0.015625f,
        epsilonHdr = 0.015625f,
        scene = scene,
    )

    @Test
    fun withoutTheSceneARolledOffRenderHasNoHeadroom() {
        val (rgb, _) = bands(30, 10)
        val r = gain(rgb, 30, 10, null)
        assertEquals(1f, r.ratioMax, 1e-6f)
    }

    @Test
    fun theSceneRestoresHighlightsOnly() {
        val w = 30
        val h = 10
        val (rgb, scene) = bands(w, h)
        val r = gain(rgb, w, h, scene)
        assertTrue("scene should add headroom, ratioMax=${r.ratioMax}", r.ratioMax > 2.5f)
        assertEquals(1f, r.exposureMatch, 0.02f)
        for (y in 0 until h) for (x in 0 until w) {
            val a = r.alpha[y * w + x].toInt() and 0xFF
            when (x * 3 / w) {
                1 -> assertEquals("highlight band saturates the map", 255, a)
                else -> assertEquals("midtone bands stay at identity ($x,$y)", 0, a)
            }
        }
        assertEquals(h * w / 3, r.sceneCells)
    }

    @Test
    fun tooFewMidtonesFallsBackToTheRenderOnlyMap() {
        val w = 4
        val h = 4
        val rgb = FloatArray(w * h * 3) { 0.97f }
        val r = gain(rgb, w, h, SceneLuma(w, h, FloatArray(w * h) { 5f }))
        assertEquals(1f, r.ratioMax, 1e-6f)
        assertEquals(0, r.sceneCells)
    }

    @Test
    fun aSceneOfTheWrongSizeIsIgnored() {
        val (rgb, _) = bands(30, 10)
        val r = gain(rgb, 30, 10, SceneLuma(3, 1, floatArrayOf(1f, 1f, 1f)))
        assertEquals(1f, r.ratioMax, 1e-6f)
    }

    private fun glow(scene: SceneLuma, strength: Float = 0.05f, sigma: Float = 2f) =
        SceneHighlightTap.withHalationGlow(scene, strength, sigma, bounces = 3, decay = 0.5f, renormalize = true)

    @Test
    fun noHalationLeavesTheSceneTapUntouched() {
        val scene = SceneLuma(3, 1, floatArrayOf(1f, 9f, 2f))
        assertTrue(glow(scene, strength = 0f) === scene)
        assertTrue(glow(scene, sigma = 0f) === scene)
    }

    @Test
    fun aRenormalizedGlowLeavesAFlatSceneFlat() {
        val out = glow(SceneLuma(8, 6, FloatArray(48) { 0.7f }))
        for (v in out.y) assertEquals(0.7f, v, 1e-5f)
    }

    @Test
    fun aBrightSourceSpreadsAHaloThatConservesLight() {
        val w = 41
        val h = 41
        val y = FloatArray(w * h) { 0.1f }
        val c = 20 * w + 20
        y[c] = 64f
        val out = glow(SceneLuma(w, h, y))
        assertTrue("the source gives some light to its halo", out.y[c] < 64f)
        assertTrue("a neighbour two cells away is lifted", out.y[c + 2] > 0.13f)
        assertTrue("the halo fades with distance", out.y[c + 2] > out.y[c + 8])
        assertEquals(y.sum(), out.y.sum(), y.sum() * 1e-4f)
    }

    @Test
    fun oneOutlierCellDoesNotSetTheHeadroomScale() {
        // The bands scene (highlight band at ~3x) plus one cell 64x brighter, as a sun would be.
        val w = 150 // 3000 highlight cells, so one cell is under 0.1% of them
        val h = 60
        val (rgb, scene) = bands(w, h)
        val hot = 5 * w + w / 2 // inside the highlight band
        val y = scene.y.copyOf()
        y[hot] = 192f
        val r = HdrGainMap.compute(
            rgb = FloatBuffer.wrap(rgb), width = w, height = h, colorSpace = ColorSpace.SRGB,
            cctfEncoded = true, downsample = 1, ratioMaxCeiling = 16f,
            epsilonSdr = 0.015625f, epsilonHdr = 0.015625f, scene = SceneLuma(w, h, y),
        )
        val bandOnly = gain(rgb, w, h, scene)
        // Scale fitted to the band (+0.25 EV), not to the outlier (which would hit the 16x cap).
        assertEquals(bandOnly.ratioMax * 2f.pow(0.25f), r.ratioMax, 0.05f)
        // The band keeps most of the 8-bit range instead of being squashed toward 0.
        val bandCode = r.alpha[5 * w + w / 2 + 1].toInt() and 0xFF
        assertTrue("band code $bandCode", bandCode > 190)
        // The outlier rolls off through the shoulder to the top of the range, not past it.
        val hotCode = r.alpha[hot].toInt() and 0xFF
        assertTrue("outlier code $hotCode", hotCode in 240..255)
    }

    @Test
    fun theDitherKeepsTheMeanCode() {
        // A uniform mid gain must average to the same code with or without dither.
        val w = 32
        val h = 32
        val (rgb, scene) = bands(w, h)
        val r = gain(rgb, w, h, SceneLuma(w, h, FloatArray(w * h) { i ->
            if ((i % w) * 3 / w == 1) 1.7f else scene.y[i]
        }))
        val codes = (0 until w * h).filter { (it % w) * 3 / w == 1 }.map { r.alpha[it].toInt() and 0xFF }
        assertTrue("codes ${codes.toSet()}", codes.toSet().size <= 2)
    }
}
