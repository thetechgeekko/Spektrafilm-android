package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorToolViewModelTest {
    private fun vm(overlay: EditorOverlayTool = EditorOverlayTool.NONE, mask: Int = 0) =
        EditorToolViewModel().apply { restore(overlay, mask) }

    @Test fun everyOverlayRoundTripsThroughRestore() {
        for (tool in EditorOverlayTool.entries) {
            val vm = vm(tool, 2)
            assertEquals(tool, vm.current.overlay)
            assertEquals(2, vm.current.maskIndex)
            assertEquals(tool != EditorOverlayTool.NONE, vm.current.anyOverlayOpen)
        }
    }

    @Test fun overlayKeepsTheOldCapturePriority() {
        val all = EditorToolUiState(
            cropOpen = true, maskGeometryOpen = true, sampleOpen = true,
            sampleLuminance = true, sampleWb = true,
        )
        assertEquals(EditorOverlayTool.CROP, all.overlay)
        assertEquals(EditorOverlayTool.MASK_GEOMETRY, all.copy(cropOpen = false).overlay)
        val sample = all.copy(cropOpen = false, maskGeometryOpen = false)
        assertEquals(EditorOverlayTool.WHITE_BALANCE_SAMPLE, sample.overlay)
        assertEquals(EditorOverlayTool.MASK_SAMPLE_LUMINANCE, sample.copy(sampleWb = false).overlay)
        assertEquals(
            EditorOverlayTool.MASK_SAMPLE_COLOR,
            sample.copy(sampleWb = false, sampleLuminance = false).overlay,
        )
        assertEquals(EditorOverlayTool.NONE, sample.copy(sampleOpen = false).overlay)
    }

    @Test fun cropAndMaskGeometry() {
        val vm = vm()
        vm.onAction(EditorToolAction.OpenCrop)
        assertTrue(vm.current.cropOpen)
        vm.onAction(EditorToolAction.CloseCrop)
        vm.onAction(EditorToolAction.EditMaskOnPhoto(3))
        assertEquals(EditorOverlayTool.MASK_GEOMETRY, vm.current.overlay)
        assertEquals(3, vm.current.maskIndex)
        vm.onAction(EditorToolAction.CloseMaskGeometry)
        assertFalse(vm.current.anyOverlayOpen)
        assertEquals(3, vm.current.maskIndex)
    }

    @Test fun eyedropperModes() {
        val vm = vm()
        vm.onAction(EditorToolAction.PickNeutral)
        assertEquals(EditorOverlayTool.WHITE_BALANCE_SAMPLE, vm.current.overlay)
        vm.onAction(EditorToolAction.CloseSample)
        assertFalse(vm.current.sampleOpen); assertFalse(vm.current.sampleWb)

        vm.onAction(EditorToolAction.SampleMask(1, luminance = true))
        assertEquals(EditorOverlayTool.MASK_SAMPLE_LUMINANCE, vm.current.overlay)
        assertEquals(1, vm.current.maskIndex)
        vm.onAction(EditorToolAction.MaskSampleApplied)
        assertFalse(vm.current.sampleOpen)
        assertTrue(vm.current.sampleLuminance)

        vm.onAction(EditorToolAction.SampleMask(0, luminance = false))
        assertEquals(EditorOverlayTool.MASK_SAMPLE_COLOR, vm.current.overlay)
    }

    @Test fun maskSelectionClampsAndResets() {
        val vm = vm(mask = 4)
        vm.onAction(EditorToolAction.ClampMask(2))
        assertEquals(1, vm.current.maskIndex)
        vm.onAction(EditorToolAction.ClampMask(0))
        assertEquals(0, vm.current.maskIndex)
        vm.onAction(EditorToolAction.SelectMask(5))
        vm.onAction(EditorToolAction.OpenCrop)
        vm.onAction(EditorToolAction.PickNeutral)
        vm.onAction(EditorToolAction.ResetForSourceChange)
        assertEquals(EditorToolUiState(), vm.current)
    }
}
