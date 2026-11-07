/*
 * Spektrafilm for Android — the HDR scene glow follows the halation the engine renders. GPLv3.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.HalationParams
import com.spectrafilm.engine.SpektraParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrSceneGlowTest {

    @Test
    fun `presets mirror the engine's digest_halation_params table`() {
        val vision3 = FilmHalationPreset.of("cine", "strong")!!
        assertEquals(50f, vision3.sigmaUm)
        assertEquals(Triple(0.015f, 0.005f, 0f), vision3.strength)
        val still = FilmHalationPreset.of("still", "weak")!!
        assertEquals(65f, still.sigmaUm)
        assertEquals(Triple(0.08f, 0.02f, 0f), still.strength)
        assertEquals(Triple(0.30f, 0.10f, 0.015f), FilmHalationPreset.of("still", "no")!!.strength)
    }

    @Test
    fun `an unknown tag means no halation, as in the engine`() {
        assertNull(FilmHalationPreset.of("", "strong"))
        assertNull(FilmHalationPreset.of("still", ""))
    }

    private val tap = SceneLuma(9, 9, FloatArray(81) { if (it == 40) 50f else 0.1f })

    private fun glow(params: SpektraParams, preset: FilmHalationPreset?) =
        withFilmHalationGlow(tap, params, preset, 4000, 4000, 4000, 4)

    @Test
    fun `no preset or halation off leaves the tap alone`() {
        val p = SpektraParams(filmProfile = "x", printProfile = "y")
        assertTrue(glow(p, null) === tap)
        val off = p.copy(
            filmRender = p.filmRender.copy(halation = HalationParams(active = false)),
        )
        assertTrue(glow(off, FilmHalationPreset.of("still", "no")) === tap)
    }

    @Test
    fun `a weaker antihalation layer spreads less light`() {
        val p = SpektraParams(filmProfile = "x", printProfile = "y")
        val strong = glow(p, FilmHalationPreset.of("still", "strong")).y[41]
        val none = glow(p, FilmHalationPreset.of("still", "no")).y[41]
        assertTrue("$strong vs $none", none > strong)
        assertTrue(strong > 0.1f)
    }
}
