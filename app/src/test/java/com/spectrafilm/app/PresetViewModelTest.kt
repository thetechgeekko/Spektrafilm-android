package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetViewModelTest {
    private val saved = EditorPresetState(
        baseJson = "{\"a\":0}",
        fullJson = "{\"a\":1}",
        amount = 0.4f,
        clipboardJson = "{\"clip\":1}",
        selectedPreset = "Portra warm",
        presetName = "draft",
    )

    @Test fun nullRestoreIsAFreshSession() {
        val vm = PresetViewModel().apply { restore(saved); restore(null) }
        assertEquals(PresetUiState(), vm.current)
        assertFalse(vm.current.amountEnabled)
        assertFalse(vm.current.canPaste)
        assertEquals(1f, vm.current.amount)
    }

    @Test fun restoreRoundTripsThroughTheSessionState() {
        val vm = PresetViewModel().apply { restore(saved) }
        assertEquals(saved, vm.current.toSessionState())
        assertTrue(vm.current.amountEnabled)
        assertTrue(vm.current.canPaste)
        assertEquals(vm.current, vm.uiState.value)
    }

    @Test fun sessionStateBoundsFreeText() {
        val long = "x".repeat(200)
        val s = PresetUiState(name = long, selected = long).toSessionState()
        assertEquals(96, s.presetName.length)
        assertEquals(96, s.selectedPreset.length)
    }

    @Test fun applyAnchorsTheBlendAtFullAmount() {
        val vm = PresetViewModel().apply { restore(null) }
        vm.onAction(PresetAction.SetAmount(0.2f))
        vm.onAction(PresetAction.Applied("base", "full"))
        assertEquals("base", vm.current.baseJson)
        assertEquals("full", vm.current.fullJson)
        assertEquals(1f, vm.current.amount)
        vm.onAction(PresetAction.SetAmount(0.5f))
        assertEquals(0.5f, vm.current.amount)
    }

    @Test fun clearAmountDropsAnchorsButKeepsClipboardAndSelection() {
        val vm = PresetViewModel().apply { restore(saved) }
        vm.onAction(PresetAction.ClearAmount)
        assertNull(vm.current.baseJson)
        assertNull(vm.current.fullJson)
        assertEquals(1f, vm.current.amount)
        assertEquals(saved.clipboardJson, vm.current.clipboardJson)
        assertEquals(saved.selectedPreset, vm.current.selected)
    }

    @Test fun nameSelectionAndClipboard() {
        val vm = PresetViewModel().apply { restore(null) }
        vm.onAction(PresetAction.SetName("mine"))
        vm.onAction(PresetAction.Select("theirs"))
        vm.onAction(PresetAction.SetClipboard("{}"))
        assertEquals("mine", vm.current.name)
        assertEquals("theirs", vm.current.selected)
        assertTrue(vm.current.canPaste)
        vm.onAction(PresetAction.SetClipboard(null))
        assertFalse(vm.current.canPaste)
    }
}
