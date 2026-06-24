/*
 * Spektrafilm for Android — unit tests for the recipe/preset JSON round-trip. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Presets is the non-destructive editing layer: edits are serialized to JSON and
 * re-applied on open/export. This guards that a serialize → parse → decode round-trip
 * preserves the editing state. Runs on the plain JVM with the real org.json on the
 * test classpath (see app/build.gradle.kts) — no device/Robolectric.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.Rgb2Raw
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsRoundTripTest {

    @Test
    fun roundTrip_preservesRepresentativeFields() {
        val src = ParamsState().apply {
            filmProfile = "kodak_ektar_100"
            printProfile = "kodak_supra_endura"
            spectralUpsampling = Rgb2Raw.MALLETT2019      // non-default enum
            crop = true
            rawTemperature = 4200f
            exposureCompensationEv = 1.5f
            autoExposure = true
            cameraLensBlurUm = 3.5f
            scanUnsharpMask = 0.4f to 0.9f
            outputColorSpace = ColorSpace.ADOBE_RGB       // non-default enum
        }

        // serialize → text → parse → decode into a fresh state
        val json = Presets.toJsonString(src)
        val dst = ParamsState()
        Presets.decode(JSONObject(json), dst)

        assertEquals("kodak_ektar_100", dst.filmProfile)
        assertEquals("kodak_supra_endura", dst.printProfile)
        assertEquals(Rgb2Raw.MALLETT2019, dst.spectralUpsampling)
        assertTrue(dst.crop)
        assertEquals(4200f, dst.rawTemperature, 1e-4f)
        assertEquals(1.5f, dst.exposureCompensationEv, 1e-4f)
        assertTrue(dst.autoExposure)
        assertEquals(3.5f, dst.cameraLensBlurUm, 1e-4f)
        assertEquals(0.4f, dst.scanUnsharpMask.first, 1e-4f)
        assertEquals(0.9f, dst.scanUnsharpMask.second, 1e-4f)
        assertEquals(ColorSpace.ADOBE_RGB, dst.outputColorSpace)
    }

    @Test
    fun decode_missingKeys_keepDefaults() {
        val dst = ParamsState()
        val defFilm = dst.filmProfile
        val defCs = dst.outputColorSpace
        // a minimal/old preset with only the version present must not clobber defaults
        Presets.decode(JSONObject("""{"version":1}"""), dst)
        assertEquals(defFilm, dst.filmProfile)
        assertEquals(defCs, dst.outputColorSpace)
        assertFalse(dst.crop)
    }

    @Test
    fun roundTrip_isStableAcrossTwoPasses() {
        val a = ParamsState().apply {
            exposureCompensationEv = -0.75f
            outputColorSpace = ColorSpace.REC2020
            scanUnsharpMask = 0.25f to 0.6f
        }
        val firstJson = Presets.toJsonString(a)
        val b = ParamsState().also { Presets.decode(JSONObject(firstJson), it) }
        val secondJson = Presets.toJsonString(b)
        // re-serializing the decoded state reproduces the same JSON (idempotent)
        assertEquals(firstJson, secondJson)
    }

    @Test
    fun decode_futureVersion_decodesKnownFieldsBestEffort() {
        // A preset written by a NEWER app (higher schema version) must still decode the fields this
        // build understands rather than being rejected — migrate() passes it through and the opt*
        // reads apply. Guards the version-aware decode that replaced the ignored PRESET_VERSION.
        val dst = ParamsState()
        Presets.decode(JSONObject("""{"version":999,"filmProfile":"kodak_ektar_100"}"""), dst)
        assertEquals("kodak_ektar_100", dst.filmProfile)
    }

    @Test
    fun decode_noVersionField_stillDecodes() {
        // A version-less JSON is treated as the current schema and decodes normally.
        val dst = ParamsState()
        Presets.decode(JSONObject("""{"filmProfile":"kodak_ektar_100"}"""), dst)
        assertEquals("kodak_ektar_100", dst.filmProfile)
    }

    @Test
    fun roundTrip_preservesLocalAdjustmentMasks() {
        val src = ParamsState().apply {
            localAdjustments = listOf(
                com.spectrafilm.app.masks.LocalAdjustment(
                    com.spectrafilm.app.masks.Mask(
                        listOf(com.spectrafilm.app.masks.Mask.Component(
                            com.spectrafilm.app.masks.BlendMode.ADD,
                            com.spectrafilm.app.masks.MaskComponent.Radial(0.4f, 0.6f, 0.3f, 0.2f, 0.5f, angleDeg = 15f),
                            value = 0.8f,
                        )),
                        opacity = 0.9f,
                    ),
                    com.spectrafilm.app.masks.TierADelta(exposureEv = 0.75f, saturation = 25f, contrast = -10f),
                ),
            )
        }
        val dst = ParamsState()
        Presets.decode(JSONObject(Presets.toJsonString(src)), dst)
        assertEquals(src.localAdjustments, dst.localAdjustments)
        assertEquals(1, dst.localAdjustments.size)
    }
}
