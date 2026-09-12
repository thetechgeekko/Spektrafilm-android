/*
 * Spektrafilm for Android — unit tests for the "Undo" on preset deletion. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Deleting a saved preset is guarded by an Undo snackbar that holds the preset's JSON,
 * read back with Presets.read before the delete and re-committed with Presets.saveJson
 * if the user taps Undo. Both of those funnel through parseImportJson(...).toString(2),
 * so "Undo restores it byte-for-byte" is true only while that normalisation is
 * IDEMPOTENT on its own output. It is today because migrate() short-circuits at the
 * current version — this guards the day someone adds a migration that does not.
 *
 * Runs on the plain JVM with the real org.json on the test classpath — no device.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetUndoRoundTripTest {

    /** Awkward values: long decimal lexemes and negatives are where a re-parse would drift. */
    private fun sampleState() = ParamsState().apply {
        filmProfile = "kodak_portra_400"
        exposureCompensationEv = 0.30000001192092896f
        rawTemperature = 4237.5f
        scanUnsharpMask = 0.4f to 0.9f
        cameraLensBlurUm = 3.5f
    }

    /** What `saveJson` commits to disk for a given input document. */
    private fun onDisk(json: String): String = Presets.parseImportJson(json).toString(2)

    @Test
    fun deleteThenUndo_restoresTheSameBytes() {
        val disk = onDisk(Presets.toJsonString(sampleState()))

        // Presets.read() hands the caller parseImportJson(diskText).toString(2) ...
        val backup = onDisk(disk)
        // ... and Undo feeds exactly that back through saveJson().
        val restored = onDisk(backup)

        assertEquals("read() must return the document already on disk", disk, backup)
        assertEquals("Undo must restore the preset byte-for-byte", disk, restored)
    }

    @Test
    fun undo_isStableForALegacyDocumentThatMigratesOnRead() {
        // A version-1 preset (no schema key) migrates on the read that precedes the delete.
        // The migration must not re-apply on the write that Undo performs.
        val legacy = JSONObject(Presets.toJsonString(sampleState()))
            .put("version", 1)
            .also { it.remove("schema") }
            .toString(2)

        val backup = onDisk(legacy)
        val restored = onDisk(backup)

        assertEquals("migration must be a no-op the second time", backup, restored)
        assertEquals(PRESET_VERSION, JSONObject(restored).getInt("version"))
        assertEquals(PRESET_SCHEMA_ID, JSONObject(restored).getString("schema"))
    }
}
