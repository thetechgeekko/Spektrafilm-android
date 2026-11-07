package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Per-photo named versions: storage round-trip, ordering, limits, Undo of a delete, and that a
 * damaged document is set aside rather than overwritten.
 */
class PhotoVersionsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val key = "a".repeat(64)
    private var now = 1_000L
    private fun repo() = FileVersionRepository(tmp.root) { now++ }
    private fun params(ev: Double) = JSONObject().put("exposureEv", ev).toString()

    private fun loaded(r: VersionRepository): List<PhotoVersion> =
        (r.list(key) as VersionListResult.Loaded).versions

    @Test fun aPhotoWithNothingSavedHasNoVersions() {
        assertTrue(loaded(repo()).isEmpty())
    }

    @Test fun savedVersionsRoundTripNewestFirst() {
        val r = repo()
        r.add(key, "Portra", params(0.5))
        r.add(key, "Ektar", params(-0.5))
        val fresh = loaded(repo())
        assertEquals(listOf("Ektar", "Portra"), fresh.map { it.name })
        assertEquals(-0.5, JSONObject(fresh[0].paramsJson).getDouble("exposureEv"), 0.0)
    }

    @Test fun rotationIsKeptWithTheVersion() {
        val r = repo()
        val v = r.add(key, "turned", params(0.0), rotationDegrees = 90)
        assertEquals(90, loaded(repo()).single().rotationDegrees)
        r.replaceParams(key, v.id, params(0.0), rotationDegrees = 270)
        assertEquals(270, loaded(r).single().rotationDegrees)
        // Anything that is not a quarter turn normalises to none.
        r.add(key, "odd", params(0.0), rotationDegrees = 45)
        assertEquals(0, loaded(r).first().rotationDegrees)
    }

    @Test fun aBlankNameGetsTheNextFreeDefault() {
        val r = repo()
        r.add(key, "Version 2", params(0.0))
        val v = r.add(key, "   ", params(0.0))
        assertEquals("Version 3", v.name)
    }

    @Test fun namesAreTrimmedToOneBoundedLine() {
        val v = repo().add(key, "  warm \n   look  ", params(0.0))
        assertEquals("warm look", v.name)
        val long = repo().add(key, "x".repeat(200), params(0.0))
        assertEquals(PhotoVersionCodec.MAX_NAME_CHARS, long.name.length)
    }

    @Test fun renameAndReplaceKeepTheId() {
        val r = repo()
        val v = r.add(key, "A", params(0.0))
        r.rename(key, v.id, "B")
        r.replaceParams(key, v.id, params(1.0))
        val after = loaded(r).single()
        assertEquals(v.id, after.id)
        assertEquals("B", after.name)
        assertEquals(1.0, JSONObject(after.paramsJson).getDouble("exposureEv"), 0.0)
        assertTrue(after.updatedAtMillis > v.updatedAtMillis)
    }

    @Test fun deleteThenRestorePutsItBackInPlace() {
        val r = repo()
        r.add(key, "old", params(0.0))
        val middle = r.add(key, "middle", params(0.0))
        r.add(key, "new", params(0.0))
        val gone = r.delete(key, middle.id)
        assertEquals(middle, gone)
        assertEquals(listOf("new", "old"), loaded(r).map { it.name })
        r.restore(key, gone!!)
        assertEquals(listOf("new", "middle", "old"), loaded(r).map { it.name })
        // Restoring twice must not duplicate it.
        r.restore(key, gone)
        assertEquals(3, loaded(r).size)
    }

    @Test fun deletingTheLastVersionRemovesTheFile() {
        val r = repo()
        val v = r.add(key, "only", params(0.0))
        assertNull(r.delete(key, "missing"))
        r.delete(key, v.id)
        assertFalse(tmp.root.resolve("$key.json").exists())
    }

    @Test(expected = VersionLimitException::class)
    fun aPhotoIsCappedAtTheLimit() {
        val r = repo()
        repeat(PhotoVersionCodec.MAX_VERSIONS) { r.add(key, "v$it", params(0.0)) }
        r.add(key, "one too many", params(0.0))
    }

    @Test(expected = org.json.JSONException::class)
    fun aMalformedPayloadIsRefusedBeforeWriting() {
        repo().add(key, "bad", "{not json")
    }

    @Test fun aDamagedDocumentIsQuarantinedNotOverwritten() {
        val f = tmp.root.resolve("$key.json")
        f.writeText("{\"schema\":\"something else\"}")
        val r = repo()
        assertTrue(r.list(key) is VersionListResult.CorruptQuarantined)
        assertFalse(f.exists())
        // The photo is usable again afterwards.
        r.add(key, "fresh", params(0.0))
        assertEquals(1, loaded(r).size)
    }

    @Test fun aDocumentForAnotherPhotoIsRejected() {
        val text = PhotoVersionCodec.encode("b".repeat(64), emptyList())
        try {
            PhotoVersionCodec.decode(text, key)
            throw AssertionError("expected a key mismatch")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("key"))
        }
    }

    @Test fun defaultNameSkipsTakenNumbers() {
        val existing = listOf(
            PhotoVersion("1", "Version 1", 0, 0, "{}"),
            PhotoVersion("2", "version 3", 0, 0, "{}"),
        )
        assertEquals("Version 4", PhotoVersionCodec.defaultName(existing))
    }
}
