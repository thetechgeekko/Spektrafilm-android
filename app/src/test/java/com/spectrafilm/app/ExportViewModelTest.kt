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

    @Test fun shareRequestSurvivesASuccessfulRun() {
        val vm = vm()
        vm.onAction(ExportAction.ShareWhenDone)
        vm.onAction(ExportAction.Running(3L))
        vm.onAction(ExportAction.Terminal(EditorExportPhase.SUCCESS))
        vm.onAction(ExportAction.SetShare("content://media/3" to "image/jpeg"))
        vm.onAction(ExportAction.SetPhase(EditorExportPhase.SUCCESS))
        assertTrue(vm.current.shareWhenDone)
        vm.onAction(ExportAction.ShareHandled)
        assertFalse(vm.current.shareWhenDone)
    }

    /** A failed or cancelled run must not leave a share armed for the next plain export. */
    @Test fun shareRequestDiesWithAFailedOrCancelledRun() {
        for (phase in listOf(EditorExportPhase.FAILURE, EditorExportPhase.CANCELLED, EditorExportPhase.RECONCILING)) {
            val terminal = vm()
            terminal.onAction(ExportAction.ShareWhenDone)
            terminal.onAction(ExportAction.Running(4L))
            terminal.onAction(ExportAction.Terminal(phase))
            assertFalse("Terminal($phase)", terminal.current.shareWhenDone)

            val refined = vm()
            refined.onAction(ExportAction.ShareWhenDone)
            refined.onAction(ExportAction.Running(4L))
            refined.onAction(ExportAction.SetPhase(phase))
            assertFalse("SetPhase($phase)", refined.current.shareWhenDone)
        }
    }

    @Test fun sheetExportAndDismissNeverInheritAShare() {
        val viaSheet = vm()
        viaSheet.onAction(ExportAction.ShareWhenDone)
        viaSheet.onAction(ExportAction.OpenSheet)
        assertFalse(viaSheet.current.shareWhenDone)

        val dismissed = vm()
        dismissed.onAction(ExportAction.ShareWhenDone)
        dismissed.onAction(ExportAction.DismissResult)
        assertFalse(dismissed.current.shareWhenDone)
    }

    /** Session-local, like lastShare: a restored editor never opens a share sheet by itself. */
    @Test fun restoreClearsAPendingShare() {
        val vm = vm()
        vm.onAction(ExportAction.ShareWhenDone)
        vm.restore(restored(EditorExportPhase.RUNNING, 2L))
        assertFalse(vm.current.shareWhenDone)
    }

    /** Codex on #535: Share must wait for the new run's file, never send the previous one. */
    @Test fun aNewRunDropsThePreviousResult() {
        val vm = vm()
        vm.onAction(ExportAction.Running(1L))
        vm.onAction(ExportAction.Terminal(EditorExportPhase.SUCCESS))
        vm.onAction(ExportAction.SetShare("content://media/1" to "image/jpeg"))
        vm.onAction(ExportAction.ShareWhenDone)
        vm.onAction(ExportAction.Running(2L))
        assertNull(vm.current.lastShare)
        assertTrue(vm.current.shareWhenDone)
    }
}
