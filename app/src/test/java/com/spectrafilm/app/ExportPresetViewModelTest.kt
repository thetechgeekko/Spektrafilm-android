package com.spectrafilm.app

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The export-preset ViewModel over the real file repository (#280): what it publishes, that
 * operations land in call order, and that a failed operation reports and leaves the state alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportPresetViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo get() = FileExportPresetRepository(tmp.root.resolve("export_presets.json"))
    private val options = ExportOptions(ExportFormat.JPEG, 90, ExportSize.MEDIUM, 0, "", sharpen = OutputSharpen.NONE)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun model() = ExportPresetViewModel(repo, Dispatchers.Unconfined)

    @Test fun startsEmptyAndLoadPublishesTheSavedDocument() {
        repo.save(ExportPreset("Print", options, keepGps = true))
        val model = model()
        assertTrue(model.uiState.value.presets.isEmpty())
        model.load(); idle()
        assertEquals(listOf("Print"), model.uiState.value.presets.map { it.name })
        assertEquals("Print", model.uiState.value.lastUsed)
    }

    @Test fun quickOperationsLandInCallOrder() {
        val model = model()
        model.save("A", options, keepGps = false)
        model.save("B", options, keepGps = false)
        model.delete("A")
        idle()
        // A deleted preset must not come back from an earlier operation publishing late.
        assertEquals(listOf("B"), model.uiState.value.presets.map { it.name })
        assertEquals(listOf("B"), repo.read().presets.map { it.name })
    }

    @Test fun markUsedOfAnUnknownNameClearsLastUsed() {
        val model = model()
        model.save("A", options, keepGps = false); idle()
        assertEquals("A", model.uiState.value.lastUsed)
        model.markUsed("gone"); idle()
        assertNull(model.uiState.value.lastUsed)
    }

    @Test fun aFailedOperationReportsAndKeepsTheState() {
        val model = model()
        val errors = mutableListOf<String>()
        model.onError = { errors += it }
        model.save("Kept", options, keepGps = false); idle()
        val before = model.uiState.value
        model.save("   ", options, keepGps = false); idle() // a blank name is refused
        assertEquals(1, errors.size)
        assertEquals(before, model.uiState.value)
    }
}
