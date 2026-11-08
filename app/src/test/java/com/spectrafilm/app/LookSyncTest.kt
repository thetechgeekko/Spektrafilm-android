/*
 * Spektrafilm for Android -- Library look paste (multi-select sync) tests. GPLv3.
 */
package com.spectrafilm.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookSyncTest {
    /** A complete, valid preset document, as the editor saves it. */
    private fun fullParams(film: String, ev: Double, crop: Boolean, center: Double, masks: JSONArray): JSONObject {
        val o = JSONObject(Presets.toJsonString(ParamsState()))
        o.put("filmProfile", film)
        o.getJSONObject("camera").put("exposureCompensationEv", ev)
        o.getJSONObject("input").put("crop", crop)
        o.getJSONObject("input").put("cropCenter", JSONArray(listOf(center, center)))
        o.put("masks", masks)
        return o
    }

    private val sourceMasks = JSONArray()
    private val targetMasks = JSONArray()

    @Test fun mergeTakesTheLookAndKeepsTheTargetsFraming() {
        val source = fullParams("kodak_portra_400", 1.5, crop = false, center = 0.5, masks = sourceMasks)
        val target = fullParams("fujifilm_c200", -1.0, crop = true, center = 0.3, masks = targetMasks)
        val merged = LookSync.merge(source, target)
        assertEquals("kodak_portra_400", merged.getString("filmProfile"))
        assertEquals(1.5, merged.getJSONObject("camera").getDouble("exposureCompensationEv"), 0.0)
        assertTrue(merged.getJSONObject("input").getBoolean("crop"))
        assertEquals(0.3, merged.getJSONObject("input").getJSONArray("cropCenter").getDouble(0), 0.0)
        assertEquals(targetMasks.toString(), merged.getJSONArray("masks").toString())
        // Neither input changed.
        assertEquals("fujifilm_c200", target.getString("filmProfile"))
        assertFalse(source.getJSONObject("input").getBoolean("crop"))
    }

    @Test fun mergeOntoAFrameWithNoRecipeCarriesNoFraming() {
        val source = fullParams("kodak_portra_400", 1.5, crop = true, center = 0.2, masks = sourceMasks)
        val merged = LookSync.merge(source, null)
        assertFalse(merged.has("masks"))
        assertFalse(merged.getJSONObject("input").has("crop"))
        assertFalse(merged.getJSONObject("input").has("cropCenter"))
        assertFalse(merged.getJSONObject("input").has("cropSize"))
        assertFalse(merged.getJSONObject("input").has("straighten"))
        // Still a document the recipe codec accepts.
        Presets.parseImportJson(merged.toString())
    }

    /** In-memory store that validates what it is given exactly as the file codec does. */
    private class FakeRepo : RecipeRepository {
        val docs = HashMap<String, RecipeDocument>()
        val unreadable = HashSet<String>()
        val generations = HashMap<String, Long>()
        var bumpOnRead: String? = null
        override fun generation(key: String) = generations[key] ?: 0L
        override fun readResult(key: String?): RecipeReadResult {
            if (key == bumpOnRead) generations[key!!] = generation(key) + 1
            if (key in unreadable) return RecipeReadResult.IoFailure("boom")
            return docs[key]?.let { RecipeReadResult.Loaded(it) } ?: RecipeReadResult.Missing
        }
        override fun saveJson(
            key: String,
            paramsJson: String,
            sourceName: String,
            rotationDegrees: Int,
            expectedGeneration: Long?,
        ): Boolean {
            if (expectedGeneration != null && expectedGeneration != generation(key)) return false
            docs[key] = RecipeDocument(key, sourceName, 0L, rotationDegrees, Presets.parseImportJson(paramsJson))
            return true
        }
        override fun delete(key: String?) { docs.remove(key) }
    }

    private fun keyOf(uri: String) = "k-$uri"

    @Test fun pasteWritesEveryTargetKeepsRotationAndSkipsTheSource() {
        val repo = FakeRepo()
        val source = fullParams("kodak_portra_400", 1.0, crop = false, center = 0.5, masks = sourceMasks)
        repo.docs[keyOf("b")] = RecipeDocument(
            keyOf("b"), "b.dng", 0L, 90,
            fullParams("fujifilm_c200", 0.0, crop = true, center = 0.4, masks = targetMasks),
        )
        val result = LookSync.pasteParams(
            repo,
            Presets.parseImportJson(source.toString()),
            sourceKey = keyOf("src"),
            targets = listOf(
                LookSync.Target("b", "b.dng"),
                LookSync.Target("a", "a.jpg"),
                LookSync.Target("src", "src.jpg"),
                LookSync.Target("a", "a.jpg"),
            ),
            keyOf = ::keyOf,
        )
        assertEquals(LookSync.Result(applied = 2, skipped = 0), result)
        assertFalse(repo.docs.containsKey(keyOf("src")))
        val b = repo.docs.getValue(keyOf("b"))
        assertEquals(90, b.manualRotationDeg)
        assertEquals("kodak_portra_400", b.params.getString("filmProfile"))
        assertTrue(b.params.getJSONObject("input").getBoolean("crop"))
        val a = repo.docs.getValue(keyOf("a"))
        assertEquals(0, a.manualRotationDeg)
        assertEquals("a.jpg", a.sourceName)
    }

    @Test fun anUnreadableOrRacedTargetIsSkippedNotOverwritten() {
        val repo = FakeRepo()
        repo.unreadable += keyOf("bad")
        repo.bumpOnRead = keyOf("raced")
        val source = Presets.parseImportJson(
            fullParams("kodak_portra_400", 1.0, crop = false, center = 0.5, masks = sourceMasks).toString(),
        )
        val result = LookSync.pasteParams(
            repo,
            source,
            sourceKey = keyOf("src"),
            targets = listOf(LookSync.Target("bad", "bad.jpg"), LookSync.Target("raced", "raced.jpg")),
            keyOf = ::keyOf,
        )
        assertEquals(LookSync.Result(applied = 0, skipped = 2), result)
        assertTrue(repo.docs.isEmpty())
    }
}
