/*
 * Spektrafilm for Android — unit tests for resetStockCharacter() (onboarding §6h). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The "Use its defaults" snackbar on profile switch resets the per-stock character (grain, halation,
 * DIR couplers, density-curve gamma) to neutral while preserving the user's global/creative edits.
 * This pins both halves of that contract.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamsStateResetTest {

    @Test
    fun resetStockCharacter_restoresCharacterToDefaults() {
        val s = ParamsState()
        // Tweak a representative field in each character group.
        s.grainActive = false
        s.grainParticleAreaUm2 = 1.7f
        s.grainBlur = 2.5f
        s.grainParticleScale = Triple(3f, 3f, 3f)
        s.halationActive = false
        s.halHalationAmount = 3.3f
        s.halScatterTailWeightPct = Triple(10f, 10f, 10f)
        s.halNBounces = 5
        s.couplersActive = false
        s.couplersAmount = 0.2f
        s.couplersInhibitionInterlayer = 3f
        s.couplersGammaSamelayer = Triple(1f, 1f, 1f)
        s.filmGammaFactor = 2.0f
        s.printGammaFactor = 0.3f

        s.resetStockCharacter()

        val d = ParamsState()
        assertEquals(d.grainActive, s.grainActive)
        assertEquals(d.grainParticleAreaUm2, s.grainParticleAreaUm2, 0f)
        assertEquals(d.grainBlur, s.grainBlur, 0f)
        assertEquals(d.grainParticleScale, s.grainParticleScale)
        assertEquals(d.halationActive, s.halationActive)
        assertEquals(d.halHalationAmount, s.halHalationAmount, 0f)
        assertEquals(d.halScatterTailWeightPct, s.halScatterTailWeightPct)
        assertEquals(d.halNBounces, s.halNBounces)
        assertEquals(d.couplersActive, s.couplersActive)
        assertEquals(d.couplersAmount, s.couplersAmount, 0f)
        assertEquals(d.couplersInhibitionInterlayer, s.couplersInhibitionInterlayer, 0f)
        assertEquals(d.couplersGammaSamelayer, s.couplersGammaSamelayer)
        assertEquals(d.filmGammaFactor, s.filmGammaFactor, 0f)
        assertEquals(d.printGammaFactor, s.printGammaFactor, 0f)
    }

    @Test
    fun resetStockCharacter_preservesGlobalAndCreativeEdits() {
        val s = ParamsState()
        s.filmProfile = "kodak_ektar_100"
        s.printProfile = "kodak_supra_endura"
        s.exposureCompensationEv = 1.5f
        s.saturation = -40f
        s.vibrance = 25f
        s.contrast = 30f
        s.gamutCompress = 50f
        s.creativeWbTemp = -20f
        s.crop = true
        s.toneCurveActive = true
        s.previewMaxSize = 800
        s.outputColorSpace = com.spectrafilm.engine.ColorSpace.REC2020
        s.scanFilm = true

        s.resetStockCharacter()

        assertEquals("kodak_ektar_100", s.filmProfile)
        assertEquals("kodak_supra_endura", s.printProfile)
        assertEquals(1.5f, s.exposureCompensationEv, 0f)
        assertEquals(-40f, s.saturation, 0f)
        assertEquals(25f, s.vibrance, 0f)
        assertEquals(30f, s.contrast, 0f)
        assertEquals(50f, s.gamutCompress, 0f)
        assertEquals(-20f, s.creativeWbTemp, 0f)
        assertTrue(s.crop)
        assertTrue(s.toneCurveActive)
        assertEquals(800, s.previewMaxSize)
        assertEquals(com.spectrafilm.engine.ColorSpace.REC2020, s.outputColorSpace)
        assertTrue(s.scanFilm)
    }

    @Test
    fun resetStockCharacter_isIdempotentOnFreshState() {
        val s = ParamsState()
        s.resetStockCharacter()
        val d = ParamsState()
        // A fresh state is already at defaults, so resetting changes nothing.
        assertEquals(d.grainParticleAreaUm2, s.grainParticleAreaUm2, 0f)
        assertEquals(d.couplersAmount, s.couplersAmount, 0f)
        assertFalse(s.crop)
    }
}
