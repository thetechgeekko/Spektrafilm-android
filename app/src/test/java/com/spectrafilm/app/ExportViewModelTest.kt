package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportViewModelTest {
    private val options = ExportOptions(ExportFormat.PNG, 90, ExportSize.FULL, 2048, "name")

    private fun restored(
        phase: EditorExportPhase = EditorExportPhase.IDLE,
        runId: Long? = null,
        sheetOpen: Boolean = false,
    ) = EditorExportState(
        sheetOpen = sheetOpen,
        options = options,
        keepGps = true,
        phase = phase,
        runtimeRunId = runId,
    )

    private fun vm(state: EditorExportState = restored()) = ExportViewModel().apply { restore(state) }

    @Test fun restoreSeedsEveryJournalledFieldAndClearsShare() {
        val vm = vm(restored(EditorExportPhase.SUCCESS, null, sheetOpen = true))
        vm.onAction(ExportAction.SetShare("content://x" to "image/png"))
        vm.restore(restored(EditorExportPhase.SUCCESS, null, sheetOpen = true))
        val s = vm.current
        assertTrue(s.sheetOpen)
        assertEquals(options, s.options)
        assertTrue(s.keepGps)
        assertEquals(EditorExportPhase.SUCCESS, s.phase)
        assertNull(s.lastShare)
        assertEquals(s, vm.uiState.value)
    }

    @Test fun sessionStateRoundTripsWithoutShare() {
        val saved = restored(EditorExportPhase.RUNNING, 7L, sheetOpen = true)
        assertEquals(saved, ExportUiState.from(saved).toSessionState())
    }

    @Test fun derivedFlagsFollowThePhase() {
        val vm = vm()
        assertFalse(vm.current.inFlight); assertFalse(vm.current.maskVisible)
        vm.onAction(ExportAction.Running(3L))
        assertTrue(vm.current.inFlight); assertTrue(vm.current.maskVisible); assertFalse(vm.current.done)
        vm.onAction(ExportAction.Terminal(EditorExportPhase.SUCCESS))
        assertFalse(vm.current.inFlight); assertTrue(vm.current.done); assertTrue(vm.current.maskVisible)
        vm.onAction(ExportAction.Terminal(EditorExportPhase.FAILURE))
        assertFalse(vm.current.maskVisible)
    }

    @Test fun sheetAndChoices() {
        val vm = vm()
        vm.onAction(ExportAction.OpenSheet)
        assertTrue(vm.current.sheetOpen)
        val jpeg = options.copy(format = ExportFormat.JPEG)
        vm.onAction(ExportAction.SetOptions(jpeg))
        vm.onAction(ExportAction.SetKeepGps(false))
        vm.onAction(ExportAction.CloseSheet)
        assertFalse(vm.current.sheetOpen)
        assertEquals(jpeg, vm.current.options)
        assertFalse(vm.current.keepGps)
    }

    @Test fun runningTracksTheRunAndTerminalReleasesIt() {
        val vm = vm()
        vm.onAction(ExportAction.Running(42L))
        assertEquals(EditorExportPhase.RUNNING, vm.current.phase)
        assertEquals(42L, vm.current.runtimeRunId)
        vm.onAction(ExportAction.Terminal(EditorExportPhase.CANCELLED))
        assertEquals(EditorExportPhase.CANCELLED, vm.current.phase)
        assertNull(vm.current.runtimeRunId)
    }

    @Test fun disowningOnlyAffectsTheTrackedRun() {
        val vm = vm()
        vm.onAction(ExportAction.Running(5L))
        vm.onAction(ExportAction.RunDisowned(6L))
        assertEquals(EditorExportPhase.RUNNING, vm.current.phase)
        assertEquals(5L, vm.current.runtimeRunId)
        vm.onAction(ExportAction.RunDisowned(5L))
        assertEquals(EditorExportPhase.RECONCILING, vm.current.phase)
        assertNull(vm.current.runtimeRunId)
    }

    @Test fun runtimeIdleClearsRunIdOnlyWhileReconciling() {
        val running = vm(restored(EditorExportPhase.RUNNING, 9L))
        running.onAction(ExportAction.RuntimeIdle)
        assertEquals(9L, running.current.runtimeRunId)

        val reconciling = vm(restored(EditorExportPhase.RECONCILING, 9L))
        reconciling.onAction(ExportAction.RuntimeIdle)
        assertEquals(EditorExportPhase.RECONCILING, reconciling.current.phase)
        assertNull(reconciling.current.runtimeRunId)
    }

    @Test fun setPhaseKeepsRunTrackingAndDismissResets() {
        val vm = vm()
        vm.onAction(ExportAction.Running(1L))
        vm.onAction(ExportAction.SetPhase(EditorExportPhase.SUCCESS))
        assertEquals(1L, vm.current.runtimeRunId)
        vm.onAction(ExportAction.SetShare("content://media/1" to "image/jpeg"))
        assertEquals("content://media/1" to "image/jpeg", vm.current.lastShare)
        vm.onAction(ExportAction.DismissResult)
        assertEquals(EditorExportPhase.IDLE, vm.current.phase)
        assertNull(vm.current.runtimeRunId)
        assertNull(vm.current.lastShare)
    }
}
