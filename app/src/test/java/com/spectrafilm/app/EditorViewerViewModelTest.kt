package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorViewerViewModelTest {
    @Test fun startsWithEveryModeOff() {
        assertEquals(EditorViewerUiState(false, false, false), EditorViewerViewModel().current)
    }

    @Test fun togglesAreIndependent() {
        val vm = EditorViewerViewModel()
        vm.onAction(EditorViewerAction.ToggleCompare)
        assertTrue(vm.current.compare); assertFalse(vm.current.histogram); assertFalse(vm.current.chromeHidden)
        vm.onAction(EditorViewerAction.ToggleHistogram)
        vm.onAction(EditorViewerAction.ToggleChrome)
        assertEquals(EditorViewerUiState(compare = true, histogram = true, chromeHidden = true), vm.current)
        vm.onAction(EditorViewerAction.ToggleCompare)
        vm.onAction(EditorViewerAction.ToggleHistogram)
        assertEquals(EditorViewerUiState(chromeHidden = true), vm.current)
        assertEquals(vm.current, vm.uiState.value)
    }

    @Test fun showChromeOnlyEverShows() {
        val vm = EditorViewerViewModel()
        vm.onAction(EditorViewerAction.ShowChrome)
        assertFalse(vm.current.chromeHidden)
        vm.onAction(EditorViewerAction.ToggleChrome)
        vm.onAction(EditorViewerAction.ShowChrome)
        assertFalse(vm.current.chromeHidden)
    }

    @Test fun resetMatchesAFreshEditorComposition() {
        val vm = EditorViewerViewModel()
        vm.onAction(EditorViewerAction.ToggleCompare)
        vm.onAction(EditorViewerAction.ToggleHistogram)
        vm.onAction(EditorViewerAction.ToggleChrome)
        vm.reset()
        assertEquals(EditorViewerUiState(), vm.current)
    }

    @Test fun holdingCompareShowsTheOriginalOnlyWhileHeld() {
        val vm = EditorViewerViewModel()
        vm.onAction(EditorViewerAction.SetOriginalPeek(true))
        assertTrue(vm.current.originalPeek)
        // A peek is not the latched split view.
        assertFalse(vm.current.compare)
        vm.onAction(EditorViewerAction.SetOriginalPeek(false))
        assertEquals(EditorViewerUiState(), vm.current)
    }
}
