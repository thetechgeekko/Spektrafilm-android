package com.spectrafilm.app

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [EditorViewModel] (audit A2, first slice): every editor composition gets fresh editing
 * state, as the old `remember { ParamsState() }` did, and the catalog loads once per
 * ViewModel, not once per composition. A real [EditorCatalog] needs the native engine, so the
 * loader here never finishes; that is enough to see when and how often it runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorViewModelTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun everySessionStartsFromDefaultsEvenAfterTheLastOneWasEdited() {
        val vm = EditorViewModel { CompletableDeferred<EditorCatalog>().await() }
        val defaults = Presets.toJsonString(ParamsState())

        val first = vm.beginSession()
        assertSame(first.params, vm.params)
        first.params.filmProfile = "some_other_film"
        first.params.exposureCompensationEv = 1.5f
        first.rotation.value = SourceRotation.fromDegrees(90)
        first.history.push(EditSnapshot("{}", 0))
        first.restoring.value = true
        assertNotEquals(defaults, Presets.toJsonString(first.params))

        val second = vm.beginSession()
        assertNotSame(first, second)
        assertSame(second.params, vm.params)
        assertEquals(defaults, Presets.toJsonString(second.params))
        assertEquals(SourceRotation.NONE, second.rotation.value)
        assertFalse(second.history.canUndo)
        assertNull(second.committedSnapshot.value)
        assertFalse(second.restoring.value)
    }

    @Test fun theCatalogLoadsOncePerViewModelAndIsNullUntilItArrives() {
        var loads = 0
        val vm = EditorViewModel {
            loads++
            CompletableDeferred<EditorCatalog>().await()
        }
        idle()
        vm.beginSession()
        vm.beginSession()
        idle()

        assertEquals(1, loads)
        assertNull(vm.catalog.value)
    }

    @Test fun settlePushesOneUndoStepPerRealEditAndUndoRedoRoundTrip() {
        val session = EditorSession(document = null, rotationDegrees = 0)
        // First settle adopts the baseline without an undo step.
        assertFalse(session.settle())
        val baseline = session.snapshot()
        assertEquals(baseline, session.committedSnapshot.value)
        assertFalse(session.history.canUndo)

        session.params.exposureCompensationEv = 1.5f
        session.rotation.value = SourceRotation.fromDegrees(90)
        assertTrue(session.settle())
        val edited = session.snapshot()
        assertTrue(session.history.canUndo)
        // Settling again without a change records nothing.
        assertFalse(session.settle())

        assertEquals(baseline, session.undo())
        assertEquals(baseline, session.snapshot())
        assertTrue(session.restoring.value)
        assertEquals(SourceRotation.NONE, session.rotation.value)
        // The restore's own settle is not a new edit.
        assertFalse(session.settle())
        assertFalse(session.restoring.value)
        assertFalse(session.history.canUndo)

        assertEquals(edited, session.redo())
        assertEquals(edited, session.snapshot())
        assertNull(session.redo())
    }

    @Test fun applyCatalogTakesFreshDefaultsBeforeDecodingTheCheckpoint() {
        val restored = sessionDocument(exposure = 1.5f)
        val session = EditorSession(restored, rotationDegrees = 0)
        val result = session.applyCatalog(
            profiles = listOf(PORTRA, ENDURA),
            applyDefaults = { params, _ -> params.exposureCompensationEv = 0.5f },
            restored = restored,
        )

        val defaults = ParamsState().also { Presets.decode(JSONObject(result.freshDefaults), it) }
        assertEquals(0.5f, defaults.exposureCompensationEv, 0f)
        assertEquals(1.5f, session.params.exposureCompensationEv, 0f)
        assertTrue(result.restored)
        assertFalse(result.rebaselined)
        assertTrue(session.history.canUndo)
        assertEquals(restored.committed, session.committedSnapshot.value)
    }

    @Test fun applyCatalogRebaselinesWhenTheSessionNamesAMissingProfile() {
        val restored = sessionDocument(exposure = 1.5f)
        val session = EditorSession(restored, rotationDegrees = 0)
        val result = session.applyCatalog(
            profiles = listOf("other_film", "other_paper"),
            applyDefaults = { _, _ -> },
            restored = restored,
        )

        assertTrue(result.rebaselined)
        assertEquals("other_film", session.params.filmProfile)
        assertEquals("other_film", session.params.printProfile)
        // Valid edits survive; only the history that could not render is dropped.
        assertEquals(1.5f, session.params.exposureCompensationEv, 0f)
        assertFalse(session.history.canUndo)
        assertNull(session.committedSnapshot.value)
    }

    @Test fun applyCatalogWithoutACheckpointOnlyAppliesDefaults() {
        val session = EditorSession(document = null, rotationDegrees = 0)
        val result = session.applyCatalog(
            profiles = listOf("only_film"),
            applyDefaults = { _, _ -> },
            restored = null,
        )

        assertFalse(result.restored)
        assertFalse(result.rebaselined)
        assertEquals("only_film", session.params.filmProfile)
        assertEquals(Presets.toJsonString(session.params), result.freshDefaults)
    }

    @Test fun theSessionStartsOnTheRestoredSourceAndChangesItAsOneValue() {
        val restored = EditorSourceState("content://media/1", SourceKind.PHOTO, "a.jpg", false)
        val session = EditorSession(document = null, rotationDegrees = 0, source = restored)
        assertEquals(restored, session.source.value)

        session.showSource("content://media/2", SourceKind.RAW, "b.dng")
        assertEquals(
            EditorSourceState("content://media/2", SourceKind.RAW, "b.dng", false),
            session.source.value,
        )

        session.requireSourceAuthorization()
        assertTrue(session.source.value.authorizationRequired)
        assertEquals("content://media/2", session.source.value.uri)

        session.showDemo("demo")
        assertEquals(EditorSourceState(null, SourceKind.DEMO, "demo", false), session.source.value)
    }

    @Test fun onlyARealSourceNeedsAuthorizationWhenItsStateIsUnknown() {
        val demo = EditorSession(document = null, rotationDegrees = 0)
        assertFalse(demo.requireAuthorizationIfRealSource())
        assertFalse(demo.source.value.authorizationRequired)

        val photo = EditorSession(
            document = null,
            rotationDegrees = 0,
            source = EditorSourceState("content://media/1", SourceKind.PHOTO, "a.jpg", false),
        )
        assertTrue(photo.requireAuthorizationIfRealSource())
        assertTrue(photo.source.value.authorizationRequired)
    }

    private fun sessionDocument(exposure: Float): EditorSessionDocument {
        fun json(ev: Float) = Presets.encode(ParamsState().apply { exposureCompensationEv = ev }).toString()
        val baseline = EditSnapshot(json(0f), 0)
        val current = EditSnapshot(json(exposure), 0)
        return EditorSessionDocument(
            source = EditorSourceState(
                uri = null,
                kind = SourceKind.DEMO,
                displayName = "synthetic demo image",
                authorizationRequired = false,
            ),
            current = current,
            committed = current,
            history = EditHistoryState(listOf(baseline), emptyList()),
            tool = EditorToolState(null, EditorOverlayTool.NONE, 0),
            preset = EditorPresetState(null, null, 1f, null, "", ""),
            export = EditorExportState(
                sheetOpen = false,
                options = ExportOptions(ExportFormat.JPEG, 95, ExportSize.FULL, 2048, ""),
                keepGps = false,
                phase = EditorExportPhase.IDLE,
                runtimeRunId = null,
            ),
        )
    }

    private companion object {
        const val PORTRA = "kodak_portra_400"
        const val ENDURA = "kodak_portra_endura"
    }
}
