/*
 * Spektrafilm for Android -- edit files beside Library photos. GPLv3.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric because skipped files are logged through android.util.Log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrarySidecarsTest {
    private fun params(film: String): JSONObject =
        JSONObject(Presets.toJsonString(ParamsState())).put("filmProfile", film)

    private fun row(name: String) = LibraryEntry("doc/$name", name, null, 0L, 1L)

    private class Repo : RecipeRepository {
        val docs = HashMap<String, RecipeDocument>()
        val cleared = HashSet<String>()
        override fun wasCleared(key: String) = key in cleared
        override fun generation(key: String) = 0L
        override fun readResult(key: String?): RecipeReadResult =
            docs[key]?.let { RecipeReadResult.Loaded(it) } ?: RecipeReadResult.Missing
        override fun saveJson(
            key: String,
            paramsJson: String,
            sourceName: String,
            rotationDegrees: Int,
            expectedGeneration: Long?,
        ): Boolean {
            docs[key] = RecipeDocument(key, sourceName, 0L, rotationDegrees, Presets.parseImportJson(paramsJson))
            return true
        }
        override fun delete(key: String?) { docs.remove(key) }
    }

    private fun keyOf(uri: String) = "k-$uri"

    @Test fun encodeDecodeRoundTripsTheEdit() {
        val text = LibrarySidecars.encode("IMG_1.dng", LibrarySidecars.Edit(params("kodak_portra_400"), 90), 5L)
        val edit = LibrarySidecars.decode(text)
        assertEquals(90, edit.manualRotationDeg)
        assertEquals("kodak_portra_400", edit.params.getString("filmProfile"))
        assertEquals("IMG_1.dng", JSONObject(text).getString("photo"))
    }

    @Test fun decodeRejectsForeignFutureAndBadFiles() {
        val good = JSONObject(LibrarySidecars.encode("a.jpg", LibrarySidecars.Edit(params("x"), 0), 1L))
        assertThrows(Exception::class.java) {
            LibrarySidecars.decode(JSONObject(good.toString()).put("schema", "other").toString())
        }
        assertThrows(Exception::class.java) {
            LibrarySidecars.decode(JSONObject(good.toString()).put("version", 2).toString())
        }
        assertThrows(Exception::class.java) {
            LibrarySidecars.decode(JSONObject(good.toString()).put("manualRotationDeg", 45).toString())
        }
        assertThrows(Exception::class.java) {
            LibrarySidecars.decode(JSONObject(good.toString()).put("version", 1.5).toString())
        }
        assertThrows(Exception::class.java) {
            LibrarySidecars.decode(JSONObject(good.toString()).put("manualRotationDeg", 4294967386L).toString())
        }
        assertThrows(Exception::class.java) { LibrarySidecars.decode("{not json") }
    }

    @Test fun sidecarNamesPairWithTheirPhotoOnly() {
        assertEquals("IMG.dng.spektra.json", LibrarySidecars.nameFor("IMG.dng"))
        assertEquals("IMG.dng", LibrarySidecars.photoNameOf("IMG.dng.spektra.json"))
        assertNull(LibrarySidecars.photoNameOf("IMG.dng"))
        assertNull(LibrarySidecars.photoNameOf(".spektra.json"))
        val frames = listOf(row("a.jpg"), row("b.dng"))
        val rows = frames + row("a.jpg.spektra.json") + row("c.jpg.spektra.json") + row("b.xmp")
        assertEquals(mapOf("doc/a.jpg" to "doc/a.jpg.spektra.json"), LibrarySidecars.pair(frames, rows))
    }

    @Test fun importFillsOnlyFramesWithNoEditAndSkipsBadFiles() {
        val repo = Repo()
        repo.docs[keyOf("doc/b.jpg")] = RecipeDocument(keyOf("doc/b.jpg"), "b.jpg", 0L, 0, params("mine"))
        val files = mapOf(
            "doc/a.jpg.spektra.json" to LibrarySidecars.encode("a.jpg", LibrarySidecars.Edit(params("side"), 180), 1L),
            "doc/b.jpg.spektra.json" to LibrarySidecars.encode("b.jpg", LibrarySidecars.Edit(params("side"), 0), 1L),
            "doc/c.jpg.spektra.json" to "{\"schema\":\"org.spektrafilm.sidecar\",\"version\":9}",
        )
        val frames = listOf(row("a.jpg"), row("b.jpg"), row("c.jpg"))
        val imported = LibrarySidecars.importMissing(
            repo,
            frames,
            LibrarySidecars.pair(frames, frames + files.keys.map { row(it.removePrefix("doc/")) }),
            ::keyOf,
        ) { files.getValue(it) }
        assertEquals(1, imported)
        assertEquals("side", repo.docs.getValue(keyOf("doc/a.jpg")).params.getString("filmProfile"))
        assertEquals(180, repo.docs.getValue(keyOf("doc/a.jpg")).manualRotationDeg)
        // An existing edit is authoritative.
        assertEquals("mine", repo.docs.getValue(keyOf("doc/b.jpg")).params.getString("filmProfile"))
        assertTrue(keyOf("doc/c.jpg") !in repo.docs)
    }

    @Test fun importNeverUndoesAReset() {
        val repo = Repo()
        repo.cleared += keyOf("doc/a.jpg")
        val frames = listOf(row("a.jpg"))
        val text = LibrarySidecars.encode("a.jpg", LibrarySidecars.Edit(params("old"), 0), 1L)
        val imported = LibrarySidecars.importMissing(
            repo,
            frames,
            mapOf("doc/a.jpg" to "doc/a.jpg.spektra.json"),
            ::keyOf,
        ) { text }
        assertEquals(0, imported)
        assertTrue(repo.docs.isEmpty())
    }

    private class Folder(val canWrite: Boolean) : LibrarySidecars.Folder {
        val writes = mutableListOf<Triple<String?, String, String>>()
        override fun writable() = canWrite
        override fun write(existingSidecarUri: String?, name: String, text: String) {
            if (name.startsWith("bad")) error("disk full")
            writes += Triple(existingSidecarUri, name, text)
        }
    }

    @Test fun saveWritesEditedFramesReusingAnExistingSidecar() {
        val repo = Repo()
        for (name in listOf("a.jpg", "bad.jpg")) {
            repo.docs[keyOf("doc/$name")] = RecipeDocument(keyOf("doc/$name"), name, 0L, 90, params("look"))
        }
        val frames = listOf(row("a.jpg"), row("bad.jpg"), row("plain.jpg"))
        val folder = Folder(canWrite = true)
        val result = LibrarySidecars.saveAll(
            repo,
            folder,
            frames,
            mapOf("doc/a.jpg" to "doc/a.jpg.spektra.json"),
            ::keyOf,
        ) { 7L }
        assertEquals(LibrarySidecars.SaveResult(saved = 1, failed = 1), result)
        val (existing, name, text) = folder.writes.single()
        assertEquals("doc/a.jpg.spektra.json", existing)
        assertEquals("a.jpg.spektra.json", name)
        assertEquals(90, LibrarySidecars.decode(text).manualRotationDeg)
    }

    @Test fun saveWithoutWriteAccessWritesNothingAndSaysSo() {
        val folder = Folder(canWrite = false)
        val result = LibrarySidecars.saveAll(Repo(), folder, listOf(row("a.jpg")), emptyMap(), ::keyOf)
        assertTrue(result.needsWriteAccess)
        assertTrue(folder.writes.isEmpty())
    }
}
