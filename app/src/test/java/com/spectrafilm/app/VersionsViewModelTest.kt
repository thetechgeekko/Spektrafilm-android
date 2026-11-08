package com.spectrafilm.app

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The per-photo versions ViewModel over the real file repository (#280): binding, the events
 * each operation reports, Undo of a delete into the photo it came from, and a damaged file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VersionsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)
    private var now = 1_000L
    private val repo by lazy { FileVersionRepository(tmp.root) { now++ } }
    private fun params(ev: Double) = JSONObject().put("exposureEv", ev).toString()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val events = mutableListOf<VersionEvent>()

    private fun model() = VersionsViewModel(repo, Dispatchers.Unconfined).also { it.onEvent = { e -> events += e } }

    @Test fun noPhotoMeansNoVersionsAndNoLoading() {
        val model = model()
        model.bind(null); idle()
        assertEquals(VersionsUiState(), model.uiState.value)
        model.save("ignored", params(0.0), 0); idle()
        assertTrue(events.isEmpty())
    }

    @Test fun bindingLoadsTheSavedVersions() {
        repo.add(key, "Portra", params(0.5))
        val model = model()
        model.bind(key); idle()
        val state = model.uiState.value
        assertEquals(key, state.key)
        assertFalse(state.loading)
        assertEquals(listOf("Portra"), state.versions.map { it.name })
    }

    @Test fun saveReportsAndRefreshesNewestFirst() {
        val model = model()
        model.bind(key); idle()
        model.save("Portra", params(0.5), 0); idle()
        model.save("Ektar", params(-0.5), 90); idle()
        assertEquals(listOf("Ektar", "Portra"), model.uiState.value.versions.map { it.name })
        assertEquals(listOf("Portra", "Ektar"), events.filterIsInstance<VersionEvent.Saved>().map { it.version.name })
    }

    @Test fun deleteReportsItsPhotoAndUndoRestoresThereAfterASwitch() {
        val model = model()
        model.bind(key); idle()
        model.save("Portra", params(0.5), 0); idle()
        val id = model.uiState.value.versions.single().id
        model.delete(id); idle()
        val deleted = events.filterIsInstance<VersionEvent.Deleted>().single()
        assertEquals(key, deleted.key)
        assertTrue(model.uiState.value.versions.isEmpty())

        // The snackbar outlives a switch to another photo: Undo goes back to the first one.
        model.bind(otherKey); idle()
        model.restore(deleted.key, deleted.version); idle()
        assertTrue(model.uiState.value.versions.isEmpty())
        assertEquals(listOf("Portra"), (repo.list(key) as VersionListResult.Loaded).versions.map { it.name })
    }

    @Test fun aDamagedFileIsReportedAndShowsNoVersions() {
        tmp.root.resolve("$key.json").writeText("{ not json")
        val model = model()
        model.bind(key); idle()
        assertTrue(model.uiState.value.versions.isEmpty())
        assertFalse(model.uiState.value.loading)
        assertTrue(events.single() is VersionEvent.Quarantined)
    }
}
