package com.spectrafilm.app

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        assertSame(first, vm.params)
        first.filmProfile = "some_other_film"
        first.exposureCompensationEv = 1.5f
        assertNotEquals(defaults, Presets.toJsonString(first))

        val second = vm.beginSession()
        assertNotSame(first, second)
        assertSame(second, vm.params)
        assertEquals(defaults, Presets.toJsonString(second))
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
}
