/*
 * Spektrafilm for Android — built-in preset application tests. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BuiltInPresetsApplyTest {

    @Test
    fun aLookLeavesOmittedControlsAlone() {
        val preset = loadPreset("portra_400")
        val state = ParamsState().apply {
            cameraLensBlurUm = 12.345f
            crop = true
            printExposure = 0.271f
            printDiffusionState.family = "cinebloom"
        }

        BuiltInPresets.apply(preset, state)

        assertEquals("kodak_portra_400", state.filmProfile)
        assertEquals("omitted camera.lensBlurUm was reset", 12.345f, state.cameraLensBlurUm)
        assertEquals("omitted io.crop was reset", true, state.crop)
        assertEquals("omitted enlarger.printExposure was reset", 0.271f, state.printExposure)
        assertEquals(
            "omitted enlarger.diffusionFilter.family was reset",
            "cinebloom",
            state.printDiffusionState.family,
        )
    }

    @Test
    fun triXSwitchesToSilverPaperAtItsGrade() {
        val state = ParamsState()
        BuiltInPresets.apply(loadPreset("tri_x_400"), state)
        assertEquals("kodak_tri_x_400", state.filmProfile)
        assertEquals(SilverPrint.MULTIGRADE, state.printProfile)
        assertEquals("3", state.paperGrade)
        assertEquals(false, state.couplersActive)
    }

    @Test
    fun aColourLookAfterTriXLeavesSilverPaper() {
        val state = ParamsState()
        BuiltInPresets.apply(loadPreset("tri_x_400"), state)
        BuiltInPresets.apply(loadPreset("portra_400"), state)
        assertEquals("kodak_portra_endura", state.printProfile)
        assertEquals(true, state.couplersActive)
    }

    // ---------------------------------------------------------------------------
    // Built-ins are SPARSE overlays, so applying two in a row compounds them. The editor
    // rewinds to the look the browsing run started from before overlaying the next one;
    // these pin both halves of that -- the hazard, and the fix.
    // ---------------------------------------------------------------------------

    @Test
    fun applyingTwoBuiltInsInARowCompoundsThem() {
        val triX = loadPreset("tri_x_400")
        val portra = loadPreset("portra_400")

        // enlarger.paperGrade is authored only by the black-and-white look, so no colour
        // look can put it back.
        assertEquals(
            "only Tri-X should author enlarger.paperGrade",
            false,
            portra.params.optJSONObject("enlarger")?.has("paperGrade") ?: false,
        )

        val state = ParamsState()
        BuiltInPresets.apply(triX, state)
        assertEquals("3", state.paperGrade)

        BuiltInPresets.apply(portra, state)
        assertEquals(
            "a sparse preset cannot clear a field it does not author -- this is the hazard " +
                "the editor's baseline rewind exists to work around",
            "3",
            state.paperGrade,
        )
    }

    @Test
    fun rewindingToTheBaselineYieldsTheSecondPresetAlone() {
        val triX = loadPreset("tri_x_400")
        val portra = loadPreset("portra_400")

        // The look the browsing run started from.
        val baseline = Presets.toJsonString(ParamsState())

        val browsed = ParamsState()
        BuiltInPresets.apply(triX, browsed)
        // What the editor does before overlaying the next built-in.
        Presets.decode(JSONObject(baseline), browsed)
        BuiltInPresets.apply(portra, browsed)

        assertEquals("Tri-X's paper grade must be gone", SilverPrint.DEFAULT_GRADE, browsed.paperGrade)

        // Stronger than one field: browsing to a preset must land on exactly the same look
        // as choosing it first, field for field.
        val direct = ParamsState()
        BuiltInPresets.apply(portra, direct)
        assertEquals(
            "browsing to a preset must equal applying it from the same baseline",
            Presets.toJsonString(direct),
            Presets.toJsonString(browsed),
        )
    }

    @Test
    fun theRewindPreservesPhotoSpecificWorkTheLookDoesNotOwn() {
        // The baseline is a full encode of live state, so crop and the other per-photo
        // fields ride through the rewind. No built-in authors them.
        val look = loadPreset("cinestill_800t")
        val start = ParamsState().apply {
            crop = true
            cameraLensBlurUm = 12.345f
        }
        val baseline = Presets.toJsonString(start)

        val state = ParamsState()
        Presets.decode(JSONObject(baseline), state)
        BuiltInPresets.apply(look, state)

        assertEquals("crop was lost across the rewind", true, state.crop)
        assertEquals("lens blur was lost across the rewind", 12.345f, state.cameraLensBlurUm)
    }

    @Test
    fun anEditMadeWithARemovedLookStillDecodes() {
        // Saved edits (recipes, versions, user presets) store the full settings a look
        // produced, never the look's id, so retiring a look cannot strand a photo. This is
        // a recipe written by the old "Velvia 100 -- Chrome Landscape" look (27-look era):
        // it must decode to the same film, paper and slide route with nothing missing.
        val saved = ParamsState().apply {
            filmProfile = "fujifilm_velvia_100"
            printProfile = "kodak_2383"
            scanFilm = true
            filmGammaFactor = 1.18f
            couplersAmount = 1.25f
        }
        val json = Presets.toJsonString(saved)

        val reopened = ParamsState()
        Presets.decode(JSONObject(json), reopened)

        assertEquals("fujifilm_velvia_100", reopened.filmProfile)
        assertEquals("kodak_2383", reopened.printProfile)
        assertEquals(true, reopened.scanFilm)
        assertEquals(json, Presets.toJsonString(reopened))
    }

    private fun loadPreset(id: String): BuiltInPreset {
        val arr = JSONObject(repoFile(
            "engine/spektra-core/src/main/assets/spektra/presets.json",
        ).readText()).getJSONArray("presets")
        for (i in 0 until arr.length()) {
            val value = arr.getJSONObject(i)
            if (value.optString("id") == id) {
                return BuiltInPreset(
                    id = id,
                    name = value.getString("name"),
                    group = value.getString("group"),
                    description = value.getString("description"),
                    params = value.getJSONObject("params"),
                )
            }
        }
        throw AssertionError("Missing built-in preset '$id'")
    }

    /** Locate a repo-relative file by walking up from the test working dir (repo root or app/). */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.exists()) return candidate
            directory = directory.parentFile
        }
        throw AssertionError(
            "Could not locate $relativePath from ${System.getProperty("user.dir")}",
        )
    }
}
