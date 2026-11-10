/*
 * Spektrafilm for Android — seeding the editor's export state at entry. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * #582: moved out of EditorScreen. Each editor composition seeds ExportViewModel once from
 * the reconciled checkpoint, so the session document stays the only restoration authority,
 * and picks the status line the editor opens with.
 */
package com.spectrafilm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource

/**
 * True once the editor has restored in this process. Distinguishes a cold start (the first
 * restore) from an Activity recreation (every later one), which is what decides whether a
 * checkpointed export sheet comes back on screen — see [exportSheetVisibleAtRestore].
 */
private var editorRestoredInThisProcess = false

/**
 * Deliberately non-composed one-shot read: the durable restore adopts an already-running
 * export only when its bound source identity is owned by the editor's exact current
 * binding (ownership, not publication — a revoked source still tracks its own run).
 * Called once from a keyless remember at EditorScreen entry.
 */
internal fun authorizedRunningExportId(editorIdentity: ExportSourceIdentity): Long? =
    (ExportWorkRuntime.state.value as? ExportRuntimeState.Running)
        ?.takeIf { running ->
            exportRunOwnedByEditor(running.sourceIdentity, editorIdentity)
        }
        ?.runId

/**
 * Seeds [exportVm] from the checkpoint's [restoredExport] (or the Settings defaults) once per
 * editor composition, and returns the status line the editor opens with.
 */
@Composable
internal fun rememberEditorExportRestore(
    exportVm: ExportViewModel,
    settings: AppSettings,
    restoredExport: EditorExportState?,
    initialSessionRestoreNotice: String?,
    exportSourceIdentity: ExportSourceIdentity,
): String {
    // One-shot at-restore snapshot (remember, no keys): later runtime transitions reach the
    // UI through its Compose observer, never through this initializer.
    val activeExportRunAtRestore = remember { authorizedRunningExportId(exportSourceIdentity) }
    val initialExportState = reconcileRestoredExport(
        restoredExport ?: EditorExportState(
            sheetOpen = false,
            options = ExportOptions(
                settings.exportFormat,
                settings.exportQuality,
                ExportSize.FULL,
                2048,
                "",
                settings.exportBorder,
                frame = settings.exportFrame,
            ),
            keepGps = settings.exportKeepGps,
            phase = EditorExportPhase.IDLE,
            runtimeRunId = null,
        ),
        activeExportRunAtRestore,
    )
    val initialEditorStatus = initialSessionRestoreNotice ?: when (initialExportState.phase) {
        EditorExportPhase.IDLE -> stringResource(R.string.editor_status_initializing)
        EditorExportPhase.RUNNING -> stringResource(R.string.editor_status_resuming_export)
        EditorExportPhase.SUCCESS -> stringResource(R.string.editor_status_prev_export_saved)
        EditorExportPhase.FAILURE -> stringResource(R.string.editor_status_prev_export_failed)
        EditorExportPhase.CANCELLED -> stringResource(R.string.editor_status_prev_export_cancelled)
        EditorExportPhase.RECONCILING -> stringResource(R.string.editor_status_prev_export_reconciling)
    }
    // Lightroom-style export sheet (per-export format / quality / size / colour / name,
    // seeded from Settings and remembered back on export) and the export phase machine
    // live in ExportViewModel (#272). Each editor composition seeds it once from the
    // reconciled checkpoint, so the session document stays the only restoration authority.
    // Process-lifetime, not remember: the FIRST editor restore in a process is a cold start,
    // every later one is an Activity recreation (which keeps the process).
    val coldStart = remember {
        val first = !editorRestoredInThisProcess
        editorRestoredInThisProcess = true
        first
    }
    remember {
        exportVm.restore(
            initialExportState.copy(
                sheetOpen = exportSheetVisibleAtRestore(
                    initialExportState.sheetOpen, initialExportState.phase, coldStart,
                ),
            ),
        )
        true
    }
    return initialEditorStatus
}
