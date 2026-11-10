/*
 * Spektrafilm for Android — editor export result and sheet host. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.State
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The running export's full-screen mask, or a finished export's share sheet / "Saved" snackbar.
 * Split out of [EditorScreen] (#273).
 */
@Composable
internal fun EditorExportResult(
    exportInFlight: Boolean,
    exportDone: Boolean,
    preview: Bitmap?,
    onCancelExport: () -> Unit,
    exportLastShare: Pair<String, String>?,
    exportVm: ExportViewModel,
    ctx: Context,
    scope: CoroutineScope,
    snackbarHost: SnackbarHostState,
    setStatus: (String) -> Unit,
) {
    // --- full-screen export mask ---
    if (exportInFlight) {
        ExportMask(
            photo = preview,
            onCancel = onCancelExport,
        )
    } else if (exportDone) {
        // The result, without covering the photo: the pill shows its check, and either
        // the share sheet opens (the run came from Share) or a Snackbar says where the
        // file went, with Open. Keyed on the file, and remembered across recreation, so a
        // rotation does not announce the same save twice.
        val result = exportLastShare
        var announced by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(result) {
            if (result == null || announced == result.first) return@LaunchedEffect
            announced = result.first
            val (published, mime) = result
            if (exportVm.current.shareWhenDone) {
                exportVm.onAction(ExportAction.ShareHandled)
                if (!shareExportedFile(ctx, published, mime)) {
                    setStatus(ctx.getString(R.string.editor_status_no_share_target))
                }
            } else {
                scope.showEditorMessage(
                    snackbarHost,
                    ctx.getString(R.string.editor_export_saved),
                    actionLabel = ctx.getString(R.string.editor_export_open),
                ) {
                    if (!openExportedFile(ctx, published, mime)) {
                        setStatus(ctx.getString(R.string.editor_status_no_open_target))
                    }
                }
            }
        }
    }
}

/**
 * The export options sheet and the permission asks tied to it. A restartable composable of its
 * own that reads [exportUiState] itself, so sheet state never recomposes the editor (#270).
 * Split out of [EditorScreen] (#273).
 */
@Composable
internal fun EditorExportSheetHost(
    exportUiState: State<ExportUiState>,
    exportVm: ExportViewModel,
    exportPresetVm: ExportPresetViewModel,
    ctx: Context,
    state: ParamsState,
    preview: Bitmap?,
    engine: SpektraEngine?,
    sourceUri: Uri?,
    sourceKind: SourceKind,
    legacyStoragePermission: ManagedActivityResultLauncher<String, Boolean>,
    batchPicker: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    pairPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    requestMediaLocationIfMissing: () -> Unit,
    exportAskingNotificationsOnce: (() -> Unit) -> Unit,
    launchEditorExport: (ExportPresetDocument) -> Unit,
    setStatus: (String) -> Unit,
) {
    val exportUi by exportUiState
    // Legacy storage (API <= 28) is asked when the export SHEET opens -- NOT when
    // the export starts. A system permission dialog takes focus, and on the
    // measured device losing /top-app costs 3.90x (§15.1). Notifications are
    // asked by the first Export tap instead (exportAskingNotificationsOnce).
    LaunchedEffect(exportUi.sheetOpen) {
        val showExportSheet = exportUi.sheetOpen
        if (showExportSheet &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                legacyStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    // ACCESS_MEDIA_LOCATION is asked for at the same moment and for the same reason,
    // and again if "Include location (GPS)" is switched on inside the open sheet.
    LaunchedEffect(exportUi.sheetOpen, exportUi.keepGps) {
        if (exportVm.current.sheetOpen) {
            requestMediaLocationIfMissing()
        }
    }

    // --- Lightroom-style export options sheet ---
    if (exportUi.sheetOpen) {
        val exportPresets by exportPresetVm.uiState.collectAsStateWithLifecycle()
        // Opening the sheet: load the presets, then preselect the one used last so the
        // Export button is two taps for a repeat export.
        LaunchedEffect(Unit) {
            exportPresetVm.onError = { setStatus(ctx.getString(R.string.editor_status_export_preset_failed, it)) }
            exportPresetVm.load()
        }
        // Saveable, so a recreated Activity (theme or locale change) that restores the open
        // sheet with the user's own edits does not overwrite them with the preset again.
        var lastUsedApplied by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(exportPresets.lastUsed) {
            val last = exportPresets.lastUsedPreset
            if (!lastUsedApplied && last != null) {
                lastUsedApplied = true
                exportVm.onAction(ExportAction.SetOptions(last.options))
                exportVm.onAction(ExportAction.SetKeepGps(last.keepGps))
            }
        }
        val matchingPreset = exportPresets.presets.firstOrNull {
            it.matches(exportUi.options, exportUi.keepGps)
        }?.name
        ExportSheet(
            presetNames = exportPresets.presets.map { it.name },
            selectedPreset = matchingPreset,
            onSelectPreset = { name ->
                exportPresets.presets.firstOrNull { it.name == name }?.let { p ->
                    exportVm.onAction(ExportAction.SetOptions(p.options))
                    exportVm.onAction(ExportAction.SetKeepGps(p.keepGps))
                }
            },
            onSavePreset = { name ->
                exportPresetVm.save(name, exportVm.current.options, exportVm.current.keepGps)
                setStatus(ctx.getString(R.string.editor_status_export_preset_saved, name))
            },
            onDeletePreset = { name -> exportPresetVm.delete(name) },
            options = exportUi.options,
            onOptionsChange = { exportVm.onAction(ExportAction.SetOptions(it)) },
            colorSpace = state.outputColorSpace,
            onColorSpaceChange = { state.outputColorSpace = it },
            cctf = state.savingCctfEncoding,
            onCctfChange = { state.savingCctfEncoding = it },
            keepGps = exportUi.keepGps,
            onKeepGpsChange = { exportVm.onAction(ExportAction.SetKeepGps(it)) },
            onDismiss = { exportVm.onAction(ExportAction.CloseSheet) },
            previewBitmap = preview,
            frameCaption = rememberFrameCaption(exportUi.options.frame, sourceUri, state.filmProfile),
            onBatchExport = if (engine != null && batchSupports(exportUi.options)) {
                {
                    exportAskingNotificationsOnce {
                        requestBatchExport(exportVm, ctx, legacyStoragePermission, batchPicker, setStatus)
                    }
                }
            } else {
                null
            },
            onHalfFramePair = if (
                engine != null && sourceUri != null && sourceKind != SourceKind.DEMO &&
                halfFramePairSupports(exportUi.options)
            ) {
                {
                    exportAskingNotificationsOnce {
                        requestHalfFramePair(exportVm, ctx, legacyStoragePermission, pairPicker, setStatus)
                    }
                }
            } else {
                null
            },
            onExport = { exportAskingNotificationsOnce { launchEditorExport(exportPresets) } },
        )
    }
}
