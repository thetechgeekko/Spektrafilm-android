/*
 * Spektrafilm for Android — unit tests for the bundled built-in preset asset. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The built-in presets ship as engine/spektra-core/.../assets/spektra/presets.json and are
 * only applied at runtime (via AssetManager), so they had no automated coverage. This guards
 * the asset's structural invariants on the plain JVM with the real org.json on the test
 * classpath (see app/build.gradle.kts): valid JSON, unique ids, every film/print profile a
 * real catalog entry, and — importantly — that no preset sets a field the engine BAKES per
 * profile or doesn't expose (which would be a silent no-op / cosmetic lie). See
 * spektra.cpp apply_user_halation/apply_user_dir_couplers/apply_user_diffusion_filter.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuiltInPresetsAssetTest {

    private val assetBase = "engine/spektra-core/src/main/assets/spektra"

    /** Locate a repo-relative file by walking up from the test working dir (repo root or app/). */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $rel from ${System.getProperty("user.dir")}")
    }

    private fun presetsArray() =
        JSONObject(repoFile("$assetBase/presets.json").readText()).getJSONArray("presets")

    @Test
    fun presets_parseHaveMetadataAndReferenceRealProfiles() {
        val catalog = JSONObject(repoFile("$assetBase/catalog.json").readText())
        val stocks = catalog.getJSONObject("stocks").keys().asSequence().toSet()
        val arr = presetsArray()
        assertTrue("expected at least one preset", arr.length() > 0)

        val ids = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val id = p.optString("id")
            assertTrue("blank id at index $i", id.isNotBlank())
            assertTrue("duplicate preset id: $id", ids.add(id))
            assertTrue("$id missing name", p.optString("name").isNotBlank())
            assertTrue("$id missing group", p.optString("group").isNotBlank())
            assertTrue("$id missing description", p.optString("description").isNotBlank())

            val params = p.getJSONObject("params")
            for (key in listOf("filmProfile", "printProfile")) {
                val ref = params.optString(key, "")
                assertTrue("$id $key='$ref' is not a catalog profile", ref.isEmpty() || ref in stocks)
            }
        }
    }

    @Test
    fun theSixLooksShipInOrder() {
        // The roster is a product decision (docs/PRESETS.md), not an accident of the asset:
        // the six films photographers most often buy, name and emulate. Changing it should
        // fail here and be made on purpose.
        val arr = presetsArray()
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        assertEquals(
            listOf("portra_400", "gold_200", "ektar_100", "cinestill_800t", "kodachrome_64", "tri_x_400"),
            ids,
        )
    }

    @Test
    fun everyLookAuthorsThePrintColourShift() {
        // A sparse overlay keeps any field it omits, so a look that left the enlarger's
        // filter shift alone would inherit the previous look's print colour (CineStill's
        // tungsten correction leaking into Portra, say). Every look sets both shifts.
        val arr = presetsArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val enlarger = p.getJSONObject("params").optJSONObject("enlarger")
            for (key in listOf("yFilterShift", "mFilterShift")) {
                assertTrue("${p.optString("id")} does not author enlarger.$key", enlarger?.has(key) == true)
            }
        }
    }

    @Test
    fun blackAndWhiteLookPrintsOnSilverPaper() {
        // A silver film on colour paper renders strong orange (SilverPrint header), so the
        // look must carry its own paper rather than rely on the film picker's re-pairing.
        val arr = presetsArray()
        for (i in 0 until arr.length()) {
            val params = arr.getJSONObject(i).getJSONObject("params")
            val film = params.optString("filmProfile")
            if (SilverPrint.isSilverFilm(film)) {
                assertTrue(
                    "$film look is not on silver paper",
                    SilverPrint.isSilverPaper(params.optString("printProfile")),
                )
                val grade = params.getJSONObject("enlarger").optString("paperGrade")
                assertTrue("unknown paper grade '$grade'", SilverPrint.GRADES.any { it.label == grade })
            }
        }
    }

    @Test
    fun presets_doNotSetEngineIgnoredFields() {
        // Baked per-profile by digest_halation_params / _apply_film_specifics. Diffusion
        // filterFamily is intentionally absent: BuiltInPresets, JNI, and native all honor it.
        val ignored = listOf(
            "halationStrength", "halationFirstSigmaUm",
            "gammaSamelayerRgb", "gammaInterlayerRToGb",
            "gammaInterlayerGToRb", "gammaInterlayerBToRg",
        )
        val arr = presetsArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val params = p.getJSONObject("params").toString()
            for (k in ignored) {
                assertFalse(
                    "${p.optString("id")} sets engine-ignored field '$k' (no-op)",
                    params.contains("\"$k\""),
                )
            }
        }
    }

    @Test
    fun presets_onlyUseEngineHonoredDiffusionFamilies() {
        validateDiffusionFamilies(presetsArray())
    }

    @Test
    fun diffusionFamilyValidationRejectsUnknownFamily() {
        val arr = presetsArray()
        val preset = arr.getJSONObject(0)
        preset.getJSONObject("params")
            .put("camera", JSONObject().put(
                "diffusionFilter",
                JSONObject().put("filterFamily", "unknown_filter"),
            ))

        val error = assertThrows(AssertionError::class.java) {
            validateDiffusionFamilies(arr)
        }
        assertTrue(error.message.orEmpty().contains("unknown_filter"))
    }

    private fun validateDiffusionFamilies(presets: org.json.JSONArray) {
        val known = DIFFUSION_FAMILIES.toSet()
        for (i in 0 until presets.length()) {
            val preset = presets.getJSONObject(i)
            val params = preset.getJSONObject("params")
            for (stage in listOf("camera", "enlarger")) {
                val diffusion = params.optJSONObject(stage)
                    ?.optJSONObject("diffusionFilter")
                    ?: continue
                if (!diffusion.has("filterFamily")) continue
                val family = diffusion.optString("filterFamily")
                assertTrue(
                    "${preset.optString("id")} $stage.diffusionFilter.filterFamily=" +
                        "'$family' is not engine-honored",
                    family in known,
                )
            }
        }
    }
    // ---------------------------------------------------------------------------
    // The picker's spec line. GroupedDropdown renders spec + summary under every row;
    // PresetPanel now fills them, so these guard the data the rows depend on.
    // ---------------------------------------------------------------------------

    @Test
    fun everyPresetCarriesTheMetadataTheRowsShow() {
        val arr = presetsArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val id = p.optString("id")
            val params = p.getJSONObject("params")
            assertTrue("$id has no filmProfile", params.optString("filmProfile").isNotBlank())
            assertTrue("$id has no printProfile", params.optString("printProfile").isNotBlank())
            assertTrue("$id has no description", p.optString("description").isNotBlank())
        }
    }

    @Test
    fun onlyReversalPresetsScanTheFilmDirectly() {
        // The spec line hides the paper for exactly these. If a preset starts or stops
        // scanning as a positive, the row would silently name a paper that never runs.
        val arr = presetsArray()
        val scanning = buildList {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                if (p.getJSONObject("params").optJSONObject("io")?.optBoolean("scanFilm") == true) {
                    add(p.optString("id"))
                }
            }
        }
        assertEquals(listOf("kodachrome_64"), scanning)
        for (id in scanning) {
            val group = (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .first { it.optString("id") == id }
                .optString("group")
            assertEquals("$id scans as positive but is not in the Slide group", "Slide", group)
        }
    }

    @Test
    fun specLine_namesFilmAndPaper() {
        assertEquals(
            "Kodak Portra 160 · Kodak Portra Endura",
            BuiltInPresets.specLine("Kodak Portra 160", "Kodak Portra Endura", false, "positive"),
        )
    }

    @Test
    fun specLine_hidesThePaperWhenThePrintStageNeverRuns() {
        assertEquals(
            "Velvia 100 · scanned as positive",
            BuiltInPresets.specLine("Velvia 100", "Kodak Portra Endura", true, "scanned as positive"),
        )
    }

    @Test
    fun specLine_degradesRatherThanShowingAStraySeparator() {
        assertEquals("Velvia 100", BuiltInPresets.specLine("Velvia 100", "", false, "positive"))
        assertEquals("", BuiltInPresets.specLine("", "Some Paper", false, "positive"))
    }
}
