/*
 * Spektrafilm for Android — the editor's Export and Share entry points. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273): the sheet's Export run, the deferral of an Export
 * or Share tap while a preview render runs, and the export probe. Engine, preview-busy and the
 * source are read through their state holders, so the deferred run sees them live.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineScope

internal class EditorExportActions(
    val launchEditorExport: (ExportPresetDocument) -> Unit,
    val requestExport: () -> Unit,
    val requestShare: () -> Unit,
)

@Composable
internal fun rememberEditorExportActions(
    dispatchers: AppDispatchers,
    exportVm: ExportViewModel,
    exportPresetVm: ExportPresetViewModel,
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    engineState: State<SpektraEngine?>,
    previewBusyState: State<Boolean>,
    sourceRenderAllowed: Boolean,
    sourceUriState: State<Uri?>,
    sourceNameState: State<String>,
    exportSourceIdentity: ExportSourceIdentity,
    snackbarHost: SnackbarHostState,
    legacyStoragePermission: ManagedActivityResultLauncher<String, Boolean>,
    mediaLocationPermissionMissing: () -> Boolean,
    currentSourceDecodeRequest: () -> SourceDecodeRequest,
    checkpointEditorSession: () -> Boolean,
    onSettingsAction: (SettingsAction) -> Unit,
    statusState: MutableState<String>,
): EditorExportActions {
    val engine by engineState
    val previewBusy by previewBusyState
    val sourceUri by sourceUriState
    val sourceName by sourceNameState
    var status by statusState
    // What the sheet's Export button (and the pill's Share) runs: the same checks and the
    // same run either way. [rememberPreset] off for Share, which uses the last preset without
    // re-marking it (the preset list may not have loaded yet).
    fun launchEditorExport(exportPresets: ExportPresetDocument, rememberPreset: Boolean = true) {
        startEditorExport(
            dispatchers = dispatchers,
            exportVm = exportVm,
            exportPresetVm = exportPresetVm,
            ctx = ctx,
            scope = scope,
            state = state,
            engine = engine,
            sourceRenderAllowed = sourceRenderAllowed,
            sourceUri = sourceUri,
            sourceName = sourceName,
            exportSourceIdentity = exportSourceIdentity,
            exportPresets = exportPresets,
            snackbarHost = snackbarHost,
            legacyStoragePermission = legacyStoragePermission,
            mediaLocationPermissionMissing = mediaLocationPermissionMissing,
            currentSourceDecodeRequest = currentSourceDecodeRequest,
            checkpointEditorSession = checkpointEditorSession,
            onSettingsAction = onSettingsAction,
            setStatus = { status = it },
            rememberPreset = rememberPreset,
        )
    }
    fun openExportSheetIfAllowed() {
        if (engine != null && sourceRenderAllowed && !previewBusy && !exportVm.current.inFlight) {
            exportVm.onAction(ExportAction.OpenSheet)
        }
    }
    // The Export FAB stays enabled while a preview render runs (a FAB is never disabled),
    // so a tap during a render is held and opens the sheet as soon as the render settles.
    var exportPending by remember { mutableStateOf(false) }
    fun requestExport() {
        if (previewBusy) exportPending = true else openExportSheetIfAllowed()
    }
    // The pill's Share half: send the file just saved if nothing changed since, otherwise
    // export with the last-used choices (no sheet) and share the result when it lands.
    var sharePending by remember { mutableStateOf(false) }
    fun shareExportIfAllowed() {
        val current = exportVm.current
        val last = current.lastShare
        if (current.done && last != null) {
            if (!shareExportedFile(ctx, last.first, last.second)) {
                status = ctx.getString(R.string.editor_status_no_share_target)
            }
            return
        }
        if (engine == null || !sourceRenderAllowed || previewBusy || current.inFlight || current.sheetOpen) return
        exportPresetVm.uiState.value.lastUsedPreset?.let { p ->
            exportVm.onAction(ExportAction.SetOptions(p.options))
            exportVm.onAction(ExportAction.SetKeepGps(p.keepGps))
        }
        exportVm.onAction(ExportAction.ShareWhenDone)
        launchEditorExport(exportPresetVm.uiState.value, rememberPreset = false)
        // Nothing started (a permission or settings check stopped it): drop the request so a
        // later plain export does not open the share sheet.
        if (!exportVm.current.inFlight) exportVm.onAction(ExportAction.ShareHandled)
    }
    fun requestShare() {
        if (previewBusy) sharePending = true else shareExportIfAllowed()
    }
    LaunchedEffect(exportPending, previewBusy) {
        if (exportPending && !previewBusy) {
            exportPending = false
            openExportSheetIfAllowed()
        }
    }
    LaunchedEffect(sharePending, previewBusy) {
        if (sharePending && !previewBusy) {
            sharePending = false
            shareExportIfAllowed()
        }
    }
    // Share reads the last-used preset without opening the sheet, so load the presets once.
    LaunchedEffect(exportPresetVm) { exportPresetVm.load() }
    LaunchedEffect(Unit) {
        if (!editorProbe.isArmed()) return@LaunchedEffect
        editorProbe.exportProbeRequests.collect { request ->
            openExportSheetIfAllowed()
            editorProbe.publishExportProbeResult(
                request.sequence,
                exportVm.current.sheetOpen,
            )
        }
    }

    return EditorExportActions(
        launchEditorExport = { launchEditorExport(it) },
        requestExport = ::requestExport,
        requestShare = ::requestShare,
    )
}
