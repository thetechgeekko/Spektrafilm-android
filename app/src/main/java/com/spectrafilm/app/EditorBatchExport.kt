/*
 * Spektrafilm for Android — editor batch export wiring. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * "Export more photos with this edit": the export sheet's menu action, the run it submits and
 * the progress it reports. The per-photo work lives in BatchExport.kt; this file is only the
 * editor side, kept out of EditorScreen.kt like EditorExportAction.kt (#273).
 */
package com.spectrafilm.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sheet's "Export more photos with this edit…" item: storage permission on API 28 and
 * below (as the single export does), then close the sheet and open the multi-select picker.
 */
internal fun requestBatchExport(
    exportVm: ExportViewModel,
    ctx: Context,
    legacyStoragePermission: ManagedActivityResultLauncher<String, Boolean>,
    batchPicker: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    setStatus: (String) -> Unit,
) {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        runCatching {
            legacyStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        setStatus(ctx.getString(R.string.editor_status_grant_storage))
        return
    }
    exportVm.onAction(ExportAction.CloseSheet)
    runCatching { batchPicker.launch(arrayOf("image/*", "*/*")) }
}

/**
 * The sheet's "Export as a half-frame pair…" item: the same storage check as a batch, then
 * the picker for the second frame. The photo open in the editor is the first.
 */
internal fun requestHalfFramePair(
    exportVm: ExportViewModel,
    ctx: Context,
    legacyStoragePermission: ManagedActivityResultLauncher<String, Boolean>,
    pairPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    setStatus: (String) -> Unit,
) {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        runCatching {
            legacyStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        setStatus(ctx.getString(R.string.editor_status_grant_storage))
        return
    }
    exportVm.onAction(ExportAction.CloseSheet)
    runCatching { pairPicker.launch(arrayOf("image/*", "*/*")) }
}

/**
 * Submit a half-frame pair: the editor's photo with its whole edit and turn, and [picked] with
 * its own saved edit (or this look). Edit and options are captured now, as for a batch.
 */
internal fun startHalfFramePair(
    dispatchers: AppDispatchers,
    exportVm: ExportViewModel,
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    engine: SpektraEngine?,
    sourceUri: Uri?,
    sourceName: String?,
    sourceKind: SourceKind,
    rotation: SourceRotation,
    picked: Uri,
    snackbarHost: SnackbarHostState,
    setStatus: (String) -> Unit,
) {
    val e = engine ?: return
    val uri = sourceUri ?: return
    if (sourceKind == SourceKind.DEMO) return
    val options = exportVm.current.options
    val keepGps = exportVm.current.keepGps
    val params = runCatching { Presets.encode(state) }.getOrElse {
        setStatus(ctx.getString(R.string.editor_status_unsupported_export))
        return
    }
    val first = HalfFrameSource(
        uri = uri,
        name = sourceName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "photo",
        kind = sourceKind,
        params = params,
        rotationDegrees = rotation.degrees,
    )
    val recipes = ctx.appContainer.recipes
    scope.launch {
        val second = withContext(dispatchers.io) {
            resolveBatchSources(ctx, listOf(picked)).firstOrNull()?.let { halfFrameSecondSource(recipes, it, first) }
        }
        if (second == null) {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(ctx.getString(R.string.editor_snack_batch_nothing))
            return@launch
        }
        val started = System.currentTimeMillis()
        val app = ctx.applicationContext
        Diag.i("half-frame pair export start format=${options.format.name}")
        val launched = ExportWorkRuntime.launch(app, options.format, started) {
            runHalfFramePairExport(app, e, first, second, options, keepGps, started)
        }
        if (launched == null) {
            setStatus(ctx.getString(R.string.editor_status_export_running))
        } else {
            BatchExportTracker.publish(BatchExportProgress(1, 0, 0, first.name, finished = false, pair = true))
        }
    }
}

/**
 * Submit the batch for the photos the picker returned. The look and the export options are
 * captured now, which is what the sheet showed when the action was chosen; editing during the
 * batch does not change it.
 */
internal fun startBatchExport(
    dispatchers: AppDispatchers,
    exportVm: ExportViewModel,
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    engine: SpektraEngine?,
    uris: List<Uri>,
    snackbarHost: SnackbarHostState,
    setStatus: (String) -> Unit,
) {
    val e = engine ?: return
    val options = exportVm.current.options
    val keepGps = exportVm.current.keepGps
    val look = runCatching { Presets.encode(state) }.getOrElse {
        setStatus(ctx.getString(R.string.editor_status_unsupported_export))
        return
    }
    scope.launch {
        val sources = withContext(dispatchers.io) { resolveBatchSources(ctx, uris) }
        if (sources.isEmpty()) {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(ctx.getString(R.string.editor_snack_batch_nothing))
            return@launch
        }
        val started = System.currentTimeMillis()
        val app = ctx.applicationContext
        Diag.i("batch export start: ${sources.size} photos format=${options.format.name}")
        val launched = ExportWorkRuntime.launch(app, options.format, started) {
            runBatchExport(app, e, sources, BatchEditSource.SharedLook(look), options, keepGps, started)
        }
        if (launched == null) {
            setStatus(ctx.getString(R.string.editor_status_export_running))
        } else {
            BatchExportTracker.publish(
                BatchExportProgress(sources.size, 0, 0, sources.first().name, finished = false),
            )
        }
    }
}

/** Batch progress on the status line, and one snackbar with the result. */
@Composable
internal fun BatchExportProgressEffect(
    ctx: Context,
    scope: CoroutineScope,
    snackbarHost: SnackbarHostState,
    setStatus: (String) -> Unit,
) {
    val batchProgress by BatchExportTracker.state.collectAsStateWithLifecycle()
    // The in-app Cancel. A batch runs unbound, so the editor's export mask (and its Cancel)
    // never shows for it, and the notification may be denied or its channel off.
    val cancelPrompt = remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(batchProgress) {
        val p = batchProgress ?: return@LaunchedEffect
        if (!p.finished) {
            setStatus(
                if (p.pair) {
                    ctx.getString(R.string.editor_snack_pair_running)
                } else {
                    ctx.getString(R.string.editor_status_batch_progress, p.position, p.total)
                },
            )
            // Shown again after any other snackbar replaced it.
            if (cancelPrompt.value?.isActive != true && snackbarHost.currentSnackbarData == null) {
                cancelPrompt.value = scope.launch {
                    val result = snackbarHost.showSnackbar(
                        message = if (p.pair) {
                            ctx.getString(R.string.editor_snack_pair_running)
                        } else {
                            ctx.getString(R.string.editor_snack_batch_running, p.total)
                        },
                        actionLabel = ctx.getString(R.string.editor_cancel),
                        duration = SnackbarDuration.Indefinite,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        (ExportWorkRuntime.state.value as? ExportRuntimeState.Running)
                            ?.let { ExportWorkRuntime.cancel(it.runId) }
                    }
                }
            }
            return@LaunchedEffect
        }
        cancelPrompt.value?.cancel()
        cancelPrompt.value = null
        val message = when {
            p.pair && p.cancelled -> ctx.getString(R.string.editor_snack_pair_cancelled)
            p.pair && p.done == 0 -> ctx.getString(R.string.editor_snack_pair_failed)
            p.pair -> ctx.getString(R.string.editor_snack_pair_done)
            p.cancelled -> ctx.getString(R.string.editor_snack_batch_cancelled, p.done, p.total)
            p.failed > 0 ->
                ctx.getString(R.string.editor_snack_batch_done_failed, p.done, p.total, p.failed)
            else -> ctx.getString(R.string.editor_snack_batch_done, p.done, p.total)
        }
        setStatus(message)
        // Outside this effect: acknowledging changes its key, which would cancel a snackbar
        // shown from in here.
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
        }
        BatchExportTracker.acknowledge()
    }
}
