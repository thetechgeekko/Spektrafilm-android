package com.spectrafilm.app

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
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
}
