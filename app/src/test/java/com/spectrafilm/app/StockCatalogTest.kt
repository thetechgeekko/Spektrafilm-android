/*
 * Spektrafilm for Android — unit tests for the slide/reversal predicate (slide-mode UX §6e). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * StockCatalog's catalog load needs an Android AssetManager, but the slide-detection rule itself is
 * pure (groupId == the reversal group), so it's checkable on the plain JVM via a constructed entry.
 * A second test reads the real catalog.json (org.json on the test classpath) so the const + rule
 * stay grounded in the shipped data — if the group were renamed, Slide mode would silently never fire.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StockCatalogTest {

    private fun entry(group: String) = StockEntry(
        id = "x", displayName = "X", manufacturer = "", groupId = group, kind = "film",
        iso = 100, balance = null, summary = "", order = 0,
    )

    @Test
    fun reversalGroupIsSlide() {
        assertTrue(entry(StockCatalog.GROUP_COLOR_REVERSAL).isReversal())
        assertTrue(entry("color_reversal").isReversal())
    }

    @Test
    fun negativeAndPrintGroupsAreNotSlide() {
        assertFalse(entry("color_negative").isReversal())
        assertFalse(entry("motion_picture_negative").isReversal())
        assertFalse(entry("print_paper").isReversal())
        assertFalse(entry("").isReversal())
    }

    @Test
    fun catalogActuallyUsesTheReversalGroupForSlideFilms() {
        val catalog = JSONObject(repoFile("engine/spektra-core/src/main/assets/spektra/catalog.json").readText())
        val groups = catalog.optJSONArray("groups")
        var hasGroup = false
        if (groups != null) for (i in 0 until groups.length()) {
            if (groups.getJSONObject(i).optString("id") == StockCatalog.GROUP_COLOR_REVERSAL) hasGroup = true
        }
        assertTrue("catalog.json missing group '${StockCatalog.GROUP_COLOR_REVERSAL}'", hasGroup)

        val stocks = catalog.getJSONObject("stocks")
        var reversalFilms = 0
        for (id in stocks.keys()) {
            val s = stocks.getJSONObject(id)
            if (s.optString("group") == StockCatalog.GROUP_COLOR_REVERSAL &&
                s.optString("kind", "film") == "film"
            ) reversalFilms++
        }
        assertTrue("expected >=1 reversal film in catalog.json", reversalFilms >= 1)
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
