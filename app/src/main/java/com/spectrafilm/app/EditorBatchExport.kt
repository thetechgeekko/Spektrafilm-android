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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sheet's "Export more photos with this edit…" item: storage permission on API 28 and
 * below (as the single export does), then close the sheet and open the multi-select picker.
 */
internal fun MainActivity.requestBatchExport(
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
 * Submit the batch for the photos the picker returned. The look and the export options are
 * captured now, which is what the sheet showed when the action was chosen; editing during the
 * batch does not change it.
 */
internal fun MainActivity.startBatchExport(
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
            runBatchExport(app, e, sources, look, options, keepGps, started)
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
    LaunchedEffect(batchProgress) {
        val p = batchProgress ?: return@LaunchedEffect
        if (!p.finished) {
            setStatus(ctx.getString(R.string.editor_status_batch_progress, p.position, p.total))
            return@LaunchedEffect
        }
        val message = when {
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
