package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviousEditTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Test fun newestOtherPhotoComesFirst() {
        val files = listOf(
            RecipeFileStamp(a, 100),
            RecipeFileStamp(b, 300),
            RecipeFileStamp(c, 200),
        )
        assertEquals(listOf(c, a), PreviousEdits.candidates(files, excludeKey = b))
    }

    @Test fun nonRecipeFilesAndTheCurrentPhotoAreSkipped() {
        val files = listOf(
            RecipeFileStamp("not-a-key", 999),
            RecipeFileStamp("A".repeat(64), 998), // keys are lowercase hex
            RecipeFileStamp(a, 1),
        )
        assertEquals(listOf(a), PreviousEdits.candidates(files, excludeKey = null))
        assertTrue(PreviousEdits.candidates(files, excludeKey = a).isEmpty())
    }

    @Test fun tiesAreStableAndTheListIsCapped() {
        val files = listOf(RecipeFileStamp(c, 5), RecipeFileStamp(a, 5), RecipeFileStamp(b, 5))
        assertEquals(listOf(a, b, c), PreviousEdits.candidates(files, null))
        assertEquals(listOf(a), PreviousEdits.candidates(files, null, limit = 1))
    }

    @Test fun stampsListOnlyJsonFiles() {
        tmp.newFile("$a.json").setLastModified(10_000)
        tmp.newFile("$b.json.corrupt-1").setLastModified(20_000)
        tmp.newFolder("$c.json")
        val stamps = PreviousEdits.stamps(tmp.root)
        assertEquals(listOf(a), stamps.map { it.key })
    }

    @Test fun firstReadableSkipsDamagedAndMissingRecipes() {
        val doc = RecipeDocument(
            sourceKey = c,
            sourceName = "IMG_1234.DNG",
            updatedAtMillis = 42L,
            manualRotationDeg = 90,
            params = JSONObject().put("exposure", 1.5),
        )
        val reads = mutableListOf<String>()
        val prev = PreviousEdits.firstReadable(listOf(a, b, c)) { key ->
            reads += key
            when (key) {
                a -> RecipeReadResult.CorruptQuarantined(null, "bad")
                b -> RecipeReadResult.Missing
                else -> RecipeReadResult.Loaded(doc)
            }
        }
        assertEquals(listOf(a, b, c), reads)
        requireNotNull(prev)
        assertEquals(c, prev.sourceKey)
        assertEquals("IMG_1234.DNG", prev.sourceName)
        assertEquals(90, prev.rotationDegrees)
        assertEquals(1.5, JSONObject(prev.paramsJson).getDouble("exposure"), 0.0)
    }

    @Test fun nothingReadableMeansNoPreviousEdit() {
        assertNull(PreviousEdits.firstReadable(listOf(a)) { RecipeReadResult.IoFailure("io") })
        assertNull(PreviousEdits.firstReadable(emptyList()) { error("not called") })
    }

    @Test fun repositoryEndToEnd() {
        val dir = tmp.newFolder("recipes")
        fun write(key: String, name: String, at: Long) {
            val f = java.io.File(dir, "$key.json")
            f.writeText(
                RecipeDocumentCodec.encode(
                    sourceKey = key,
                    paramsJson = Presets.toJsonString(ParamsState()),
                    sourceName = name,
                    manualRotationDeg = 0,
                    updatedAtMillis = at,
                ),
            )
            f.setLastModified(at)
        }
        write(a, "first.jpg", 1_000_000)
        write(b, "second.jpg", 2_000_000)
        val prev = PreviousEdits.firstReadable(PreviousEdits.candidates(PreviousEdits.stamps(dir), b)) { key ->
            RecipeReadResult.Loaded(RecipeDocumentCodec.decode(java.io.File(dir, "$key.json").readText(), key))
        }
        assertEquals("first.jpg", prev?.sourceName)
    }
}
