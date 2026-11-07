/*
 * Spektrafilm for Android — unit tests for black-and-white printing (SilverPrint). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * SilverPrint names the silver stocks in code (ParamsState.toParams has no Context to read the
 * catalog), so these tests tie that code to the shipped assets: the catalog's monochrome flags,
 * the profiles' channel_model, and the paper's filter-2 entries in neutral_print_filters.json.
 * The rest pins the grade wiring and the film/paper pairing rule.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SilverPrintTest {

    private val assets = "engine/spektra-core/src/main/assets/spektra"

    private fun json(rel: String) = JSONObject(repoFile("$assets/$rel").readText())

    @Test
    fun `the code's silver stocks are exactly the catalog's monochrome stocks`() {
        val stocks = json("catalog.json").getJSONObject("stocks")
        val films = mutableSetOf<String>()
        val papers = mutableSetOf<String>()
        for (id in stocks.keys()) {
            val s = stocks.getJSONObject(id)
            if (!s.optBoolean("monochrome", false)) continue
            if (s.getString("kind") == "film") films += id else papers += id
        }
        assertEquals(SilverPrint.FILMS, films)
        assertEquals(SilverPrint.PAPERS, papers)
    }

    @Test
    fun `every silver stock ships a bw profile and every bw profile is listed`() {
        val bwProfiles = repoFile("$assets/profiles").listFiles { f -> f.extension == "json" }!!
            .filter { JSONObject(it.readText()).getJSONObject("info").optString("channel_model") == "bw" }
            .map { it.nameWithoutExtension }
            .toSet()
        assertEquals(SilverPrint.FILMS + SilverPrint.PAPERS, bwProfiles)
    }

    @Test
    fun `grade 2 is the paper's database pack for every silver film`() {
        val grade2 = SilverPrint.grade("2")
        val byFilm = json("filters/neutral_print_filters.json")
            .getJSONObject(SilverPrint.MULTIGRADE).getJSONObject("TH-KG3")
        for (film in SilverPrint.FILMS) {
            val cmy = byFilm.getJSONArray(film)
            assertEquals("$film C", 0.0, cmy.getDouble(0), 0.0)
            assertEquals("$film M", grade2.mFilter.toDouble(), cmy.getDouble(1), 1e-6)
            assertEquals("$film Y", grade2.yFilter.toDouble(), cmy.getDouble(2), 1e-6)
        }
        assertEquals(1f, grade2.printExposure, 0f)
    }

    @Test
    fun `grades run soft to hard and an unknown label falls back to grade 2`() {
        assertEquals(listOf("00", "0", "1", "2", "3", "4", "5"), SilverPrint.GRADES.map { it.label })
        // Harder grades need more light to hold mid-grey on this paper.
        SilverPrint.GRADES.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.label}->${b.label}", b.printExposure > a.printExposure)
        }
        assertEquals("2", SilverPrint.grade("").label)
        assertEquals("2", SilverPrint.grade("7").label)
    }

    @Test
    fun `a silver paper sends its grade instead of the database pack`() {
        val s = ParamsState().apply {
            filmProfile = "kodak_tri_x_400"
            printProfile = SilverPrint.MULTIGRADE
            paperGrade = "4"
            printExposure = 1.5f
        }
        val p = s.toParams()
        assertFalse(p.settings.neutralPrintFiltersFromDatabase)
        assertEquals(0f, p.enlarger.cFilterNeutral, 0f)
        assertEquals(39.1f, p.enlarger.mFilterNeutral, 0f)
        assertEquals(0f, p.enlarger.yFilterNeutral, 0f)
        assertEquals(1.5f * 1.334f, p.enlarger.printExposure, 1e-6f)
        // One grain layer on a silver film, whatever the sub-layer switch says.
        assertTrue(s.grainSublayersActive)
        assertFalse(p.filmRender.grain.sublayersActive)
    }

    @Test
    fun `a colour paper ignores the grade and renders exactly as before`() {
        val plain = ParamsState().apply { printProfile = "kodak_portra_endura" }
        val graded = ParamsState().apply { printProfile = "kodak_portra_endura"; paperGrade = "5" }
        assertEquals(plain.toParams(), graded.toParams())
        val p = plain.toParams()
        assertTrue(p.settings.neutralPrintFiltersFromDatabase)
        assertEquals(1f, p.enlarger.printExposure, 0f)
    }

    @Test
    fun `a stock preview renders the candidate exactly as selecting it would`() {
        // The stock tiles pass the candidate as an override; the result must equal the params
        // of a state that actually has that stock selected, grade and grain layers included.
        val colour = ParamsState().apply {
            filmProfile = "kodak_portra_400"
            printProfile = "kodak_portra_endura"
            paperGrade = "4"
        }
        val onMultigrade = ParamsState().apply {
            filmProfile = "kodak_portra_400"
            printProfile = SilverPrint.MULTIGRADE
            paperGrade = "4"
        }
        assertEquals(onMultigrade.toParams(), colour.toParams(printOverride = SilverPrint.MULTIGRADE))
        assertEquals(colour.toParams(), onMultigrade.toParams(printOverride = "kodak_portra_endura"))

        val triX = ParamsState().apply {
            filmProfile = "kodak_tri_x_400"
            printProfile = SilverPrint.MULTIGRADE
            paperGrade = "4"
        }
        assertEquals(
            triX.toParams(),
            colour.toParams(filmOverride = "kodak_tri_x_400", printOverride = SilverPrint.MULTIGRADE),
        )
    }

    @Test
    fun `the remembered colour paper applies only to the move that replaced it`() {
        val endura = "kodak_portra_endura"
        val mg = SilverPrint.MULTIGRADE
        // Colour film on Endura -> Tri-X moves the print to Multigrade: remember Endura.
        var memo = SilverPrint.colourPrintToRemember("kodak_tri_x_400", endura, mg, null)
        assertEquals(endura, memo)
        // Tri-X -> HP5 stays on silver film and paper: keep it for the way back.
        memo = SilverPrint.colourPrintToRemember("ilford_hp5_plus_400", mg, mg, memo)
        assertEquals(endura, memo)
        // Back to a colour film (restored to Endura): consumed.
        memo = SilverPrint.colourPrintToRemember("kodak_portra_400", mg, endura, memo)
        assertEquals(null, memo)
        // Colour film deliberately on Multigrade -> Tri-X -> colour film again: nothing moved
        // the paper, so nothing is remembered and Multigrade stays.
        memo = SilverPrint.colourPrintToRemember("kodak_tri_x_400", mg, mg, memo)
        assertEquals(null, memo)
        assertEquals(
            mg,
            SilverPrint.printAfterFilmChange("kodak_tri_x_400", "kodak_portra_400", mg, memo, listOf(mg, endura)),
        )
    }

    @Test
    fun `the grade round-trips through a recipe`() {
        val s = ParamsState().apply { paperGrade = "00" }
        val back = ParamsState()
        Presets.decode(JSONObject(Presets.toJsonString(s)), back)
        assertEquals("00", back.paperGrade)
    }

    @Test
    fun `a silver film moves a colour print onto Multigrade`() {
        val available = listOf("kodak_portra_endura", SilverPrint.MULTIGRADE)
        assertEquals(
            SilverPrint.MULTIGRADE,
            SilverPrint.printAfterFilmChange("kodak_portra_400", "kodak_tri_x_400", "kodak_portra_endura", null, available),
        )
        // No silver paper installed: leave the print alone rather than invent one.
        assertEquals(
            "kodak_portra_endura",
            SilverPrint.printAfterFilmChange("kodak_portra_400", "kodak_tri_x_400", "kodak_portra_endura", null, listOf("kodak_portra_endura")),
        )
        // Between two silver films the paper stays.
        assertEquals(
            SilverPrint.MULTIGRADE,
            SilverPrint.printAfterFilmChange("kodak_tri_x_400", "kodak_tmax_100", SilverPrint.MULTIGRADE, "kodak_portra_endura", available),
        )
    }

    @Test
    fun `leaving a silver film restores the colour paper it replaced`() {
        val available = listOf("kodak_portra_endura", SilverPrint.MULTIGRADE)
        assertEquals(
            "kodak_portra_endura",
            SilverPrint.printAfterFilmChange("kodak_tri_x_400", "kodak_portra_400", SilverPrint.MULTIGRADE, "kodak_portra_endura", available),
        )
        // A colour film the user put on silver paper themselves stays there.
        assertEquals(
            SilverPrint.MULTIGRADE,
            SilverPrint.printAfterFilmChange("kodak_gold_200", "kodak_portra_400", SilverPrint.MULTIGRADE, "kodak_portra_endura", available),
        )
        assertTrue(SilverPrint.isMismatched("kodak_tri_x_400", "kodak_portra_endura"))
        assertFalse(SilverPrint.isMismatched("kodak_portra_400", SilverPrint.MULTIGRADE))
    }

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
}
