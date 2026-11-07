/*
 * Spektrafilm for Android — unit tests for the derived film profiles. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A derived stock (tools/upstream/derive_profiles.py) is a bundled profile with a few info tags
 * changed and nothing else: CineStill 800T is Kodak Vision3 500T without its rem-jet backing.
 * These tests read the shipped assets and pin that contract, so a regenerated base, a hand edit
 * of the copy or a missing print-filter entry fails here rather than as a silently different look.
 */
package com.spectrafilm.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DerivedProfilesTest {

    private val assets = "engine/spektra-core/src/main/assets/spektra"

    private fun profile(id: String) = JSONObject(repoFile("$assets/profiles/$id.json").readText())

    @Test
    fun `CineStill 800T carries Vision3 500T's data unchanged`() {
        val base = profile("kodak_vision3_500t")
        val derived = profile("cinestill_800t")
        // Compared by value, so a re-serialised but equal array still passes, while any changed
        // number fails.
        assertTrue("data differs from kodak_vision3_500t", sameJson(base.getJSONObject("data"), derived.getJSONObject("data")))
        assertEquals("kodak_vision3_500t", derived.getJSONObject("metadata").getString("derived_from"))
    }

    @Test
    fun `CineStill 800T differs from 500T only in the rem-jet and still-camera tags`() {
        val base = profile("kodak_vision3_500t").getJSONObject("info")
        val info = profile("cinestill_800t").getJSONObject("info")
        val expected = mapOf(
            "stock" to "cinestill_800t",
            "name" to "CineStill 800T",
            "use" to "still",
            // The one physical difference: no anti-halation backing, the engine's strongest preset.
            "antihalation" to "no",
            "target_print" to "kodak_portra_endura",
        )
        assertEquals(keysOf(base), keysOf(info))
        for (key in keysOf(base)) {
            val want = expected[key] ?: base.get(key)
            assertEquals("info.$key", want, info.get(key))
        }
        // The film is still tungsten balanced, which FilmStockBalance reads from this tag.
        assertEquals("T", info.getString("reference_illuminant"))
    }

    @Test
    fun `every paper prints CineStill 800T with 500T's neutral filters`() {
        val filters = JSONObject(repoFile("$assets/filters/neutral_print_filters.json").readText())
        var pairs = 0
        for (paper in filters.keys()) {
            val byIlluminant = filters.getJSONObject(paper)
            for (illuminant in byIlluminant.keys()) {
                val byFilm = byIlluminant.getJSONObject(illuminant)
                if (!byFilm.has("kodak_vision3_500t")) continue
                assertTrue("$paper/$illuminant has no cinestill_800t entry", byFilm.has("cinestill_800t"))
                assertTrue(
                    "$paper/$illuminant filters differ",
                    sameJson(byFilm.getJSONArray("kodak_vision3_500t"), byFilm.getJSONArray("cinestill_800t")),
                )
                pairs++
            }
        }
        assertTrue("no print filter entries checked", pairs > 0)
    }

    @Test
    fun `CineStill 800T is listed with the still colour negatives`() {
        val stock = JSONObject(repoFile("$assets/catalog.json").readText())
            .getJSONObject("stocks").getJSONObject("cinestill_800t")
        assertEquals("color_negative", stock.getString("group"))
        assertEquals("film", stock.getString("kind"))
        assertEquals("tungsten", stock.getString("balance"))
    }

    // The unit-test compile classpath sees android.jar's org.json, which has neither
    // JSONObject.similar nor keySet, so value equality is spelled out here.
    private fun keysOf(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    private fun sameJson(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject ->
            keysOf(a) == keysOf(b) && keysOf(a).all { sameJson(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { sameJson(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
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
