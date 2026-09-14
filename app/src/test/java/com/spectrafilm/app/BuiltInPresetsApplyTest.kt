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
    fun dreamyProMistAlwaysSelectsBlackProMistWithoutResettingOmittedControls() {
        val preset = loadPreset("portra400_promist_dreamy")
        assertEquals(
            "the asset must author its promised diffusion family",
            "black_pro_mist",
            preset.params.getJSONObject("camera")
                .getJSONObject("diffusionFilter")
                .getString("filterFamily"),
        )
        val startingStates = listOf(
            "fresh" to ParamsState(),
            "Black Pro-Mist" to ParamsState().apply {
                cameraDiffusionState.family = "black_pro_mist"
            },
            "Glimmerglass" to ParamsState().apply {
                cameraDiffusionState.family = "glimmerglass"
            },
            "CineBloom" to ParamsState().apply {
                cameraDiffusionState.family = "cinebloom"
            },
        )

        for ((startingFamily, state) in startingStates) {
            state.cameraLensBlurUm = 12.345f
            state.crop = true
            state.printExposure = 0.271f
            state.printDiffusionState.family = "cinebloom"

            BuiltInPresets.apply(preset, state)

            assertEquals(
                "$startingFamily state leaked into the authored camera diffusion family",
                "black_pro_mist",
                state.cameraDiffusionState.family,
            )
            assertEquals("omitted camera.lensBlurUm was reset", 12.345f, state.cameraLensBlurUm)
            assertEquals("omitted io.crop was reset", true, state.crop)
            assertEquals("omitted enlarger.printExposure was reset", 0.271f, state.printExposure)
            assertEquals(
                "omitted enlarger.diffusionFilter.family was reset",
                "cinebloom",
                state.printDiffusionState.family,
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Built-ins are SPARSE overlays, so applying two in a row compounds them. The editor
    // rewinds to the look the browsing run started from before overlaying the next one;
    // these pin both halves of that -- the hazard, and the fix.
    // ---------------------------------------------------------------------------

    @Test
    fun applyingTwoBuiltInsInARowCompoundsThem() {
        val dreamy = loadPreset("portra400_promist_dreamy")
        val neutral = loadPreset("neutral_adobe_like")

        // camera.diffusionFilter is authored by exactly ONE of the 27 presets, so nothing
        // else can turn it back off.
        assertEquals(
            "only Dreamy Pro-Mist should author camera.diffusionFilter",
            false,
            neutral.params.optJSONObject("camera")?.has("diffusionFilter") ?: false,
        )

        val state = ParamsState()
        BuiltInPresets.apply(dreamy, state)
        assertEquals("Dreamy Pro-Mist must switch the filter on", true, state.cameraDiffusionState.active)

        BuiltInPresets.apply(neutral, state)
        assertEquals(
            "a sparse preset cannot clear a field it does not author -- this is the hazard " +
                "the editor's baseline rewind exists to work around",
            true,
            state.cameraDiffusionState.active,
        )
    }

    @Test
    fun rewindingToTheBaselineYieldsTheSecondPresetAlone() {
        val dreamy = loadPreset("portra400_promist_dreamy")
        val neutral = loadPreset("neutral_adobe_like")

        // The look the browsing run started from.
        val baseline = Presets.toJsonString(ParamsState())

        val browsed = ParamsState()
        BuiltInPresets.apply(dreamy, browsed)
        // What the editor does before overlaying the next built-in.
        Presets.decode(JSONObject(baseline), browsed)
        BuiltInPresets.apply(neutral, browsed)

        assertEquals(
            "Clean Baseline promises glare and couplers off; the Pro-Mist filter must be gone",
            false,
            browsed.cameraDiffusionState.active,
        )

        // Stronger than the flag: browsing to a preset must land on exactly the same look as
        // choosing it first, field for field.
        val direct = ParamsState()
        BuiltInPresets.apply(neutral, direct)
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
        val neutral = loadPreset("neutral_adobe_like")
        val start = ParamsState().apply {
            crop = true
            cameraLensBlurUm = 12.345f
        }
        val baseline = Presets.toJsonString(start)

        val state = ParamsState()
        Presets.decode(JSONObject(baseline), state)
        BuiltInPresets.apply(neutral, state)

        assertEquals("crop was lost across the rewind", true, state.crop)
        assertEquals("lens blur was lost across the rewind", 12.345f, state.cameraLensBlurUm)
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
