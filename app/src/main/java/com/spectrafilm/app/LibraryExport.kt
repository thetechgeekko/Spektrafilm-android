/*
 * Spektrafilm for Android -- export selected Library photos, each with its own edit. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Select photos in the Library (folder, phone gallery or an album), tap Export, pick the format
 * and size, and every photo is rendered with ITS OWN saved edit: film, print, crop, turn and
 * masks, exactly what reopening it in the editor shows. A photo with no edit gets the settings a
 * first open gives it (the user's default film, paper and colour space).
 *
 * The run is a batch (BatchExport.kt) with [BatchEditSource.OwnEdits], so it shares everything
 * the editor's batch has: one photo at a time inside ExportWorkRuntime (single-flight with any
 * other export, foreground service, cancel), a failed photo counted and skipped, progress and
 * the result through [BatchExportTracker].
 */
package com.spectrafilm.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.engine.ColorSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The frames a Library export can render, in grid order, once each. */
internal fun libraryExportFrames(frames: List<LibraryEntry>): List<Pair<LibraryEntry, SourceKind>> =
    frames.distinctBy { it.documentUri }.mapNotNull { frame ->
        batchSourceKind(frame.name, frame.mimeType)?.let { frame to it }
    }

/**
 * The export options the Library's sheet opens with: the user's export defaults from Settings,
 * at full size. Scene-linear exports the decode, not an edit, so a batch cannot use it.
 */
internal fun libraryExportDefaults(format: ExportFormat, quality: Int, border: FilmBorder): ExportOptions =
    ExportOptions(
        format = if (format == ExportFormat.SCENE_LINEAR_TIFF) ExportFormat.JPEG else format,
        jpegQuality = quality,
        size = ExportSize.FULL,
        customLongEdge = 2048,
        customName = "",
        border = border,
    )

internal enum class LibraryExportStart { STARTED, BUSY, NOTHING }

/** Submit the run; never blocks on the engine, which is fetched inside the run. */
internal fun startLibraryExport(
    context: Context,
    frames: List<LibraryEntry>,
    options: ExportOptions,
    keepGps: Boolean,
): LibraryExportStart {
    val sources = libraryExportFrames(frames).map { (frame, kind) ->
        BatchExportSource(Uri.parse(frame.documentUri), frame.name, kind)
    }
    if (sources.isEmpty()) return LibraryExportStart.NOTHING
    val app = context.applicationContext
    val recipes = app.appContainer.recipes
    val settings = AppSettings.from(app)
    val io = ProcessDispatchers.io
    val started = System.currentTimeMillis()
    Diag.i("library export start: ${sources.size} photos format=${options.format.name}")
    ExportWorkRuntime.launch(app, options.format, started) {
        val engine = withContext(io) { EngineHolder.get(app) }
        val profiles = withContext(io) { runCatching { engine.listProfiles() }.getOrDefault(emptyList()) }
        val edits = BatchEditSource.OwnEdits { source ->
            // Read now, not at submit: an edit saved while earlier photos render is the one
            // exported. A corrupt recipe is quarantined and reported as the editor's restore
            // does, and the photo then exports as a first open would show it.
            val recipe = withContext(io) {
                Recipes.keyFor(source.uri)?.let { key ->
                    (runCatching { recipes.readResult(key) }.getOrNull() as? RecipeReadResult.Loaded)?.document
                }
            }
            ownEditFor(recipe, profiles, settings::applyDefaultsTo)
        }
        runBatchExport(app, engine, sources, edits, options, keepGps, started)
    } ?: return LibraryExportStart.BUSY
    BatchExportTracker.publish(BatchExportProgress(sources.size, 0, 0, sources.first().name, finished = false))
    return LibraryExportStart.STARTED
}

/**
 * The Library's export flow: the storage permission on API 28 and below, the export sheet, the
 * start and its snackbar. One per Library destination, shared by the folder, gallery and album
 * selection bars. [request] opens it for the selected frames; [Sheet] draws it.
 */
@Stable
internal class LibraryExportFlow(
    private val context: Context,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /** The frames the open sheet will export, and what to do once it starts; null = closed. */
    var pending by mutableStateOf<Pair<List<LibraryEntry>, () -> Unit>?>(null)
        private set
    private var waitingForPermission: Pair<List<LibraryEntry>, () -> Unit>? = null
    internal var askStoragePermission: () -> Unit = {}

    var options by mutableStateOf(
        AppSettings.from(context).let { libraryExportDefaults(it.exportFormat, it.exportQuality, it.exportBorder) },
    )
    var keepGps by mutableStateOf(AppSettings.from(context).exportKeepGps)

    /** Open the sheet for [frames]; [onStarted] runs once their export has started. */
    fun request(frames: List<LibraryEntry>, onStarted: () -> Unit) {
        if (frames.isEmpty()) return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            waitingForPermission = frames to onStarted
            askStoragePermission()
            return
        }
        pending = frames to onStarted
    }

    internal fun onStoragePermission(granted: Boolean) {
        val waiting = waitingForPermission ?: return
        waitingForPermission = null
        if (granted) {
            pending = waiting
        } else {
            show(context.getString(R.string.editor_toast_storage_permission))
        }
    }

    fun dismiss() {
        pending = null
    }

    fun export() {
        val (frames, onStarted) = pending ?: return
        pending = null
        when (startLibraryExport(context, frames, options, keepGps)) {
            LibraryExportStart.STARTED -> onStarted()
            LibraryExportStart.BUSY -> show(context.getString(R.string.library_export_busy))
            LibraryExportStart.NOTHING -> show(context.getString(R.string.editor_snack_batch_nothing))
        }
    }

    private fun show(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
}

@Composable
internal fun rememberLibraryExportFlow(snackbar: SnackbarHostState, scope: CoroutineScope): LibraryExportFlow {
    val ctx = LocalContext.current
    val flow = remember(snackbar, scope) { LibraryExportFlow(ctx.applicationContext, snackbar, scope) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        flow.onStoragePermission(granted)
    }
    flow.askStoragePermission = {
        runCatching { permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            .onFailure { flow.onStoragePermission(false) }
    }
    return flow
}

/** The export sheet for the pending frames, when there are any. */
@Composable
internal fun LibraryExportSheet(flow: LibraryExportFlow) {
    val (frames, _) = flow.pending ?: return
    val count = libraryExportFrames(frames).size.coerceAtLeast(1)
    ExportSheet(
        options = flow.options,
        onOptionsChange = { flow.options = it },
        // Each photo's own colour space is part of its edit; the sheet hides this section.
        colorSpace = ColorSpace.SRGB,
        onColorSpaceChange = {},
        cctf = true,
        onCctfChange = {},
        keepGps = flow.keepGps,
        onKeepGpsChange = { flow.keepGps = it },
        onDismiss = flow::dismiss,
        onExport = flow::export,
        photoCount = count,
    )
}

/**
 * While a batch runs, where it is and a way to stop it; once it ends, one snackbar with the
 * result. Shows the editor's batches too: they are the same single run.
 */
@Composable
internal fun LibraryExportProgress(modifier: Modifier = Modifier) {
    val progress by BatchExportTracker.state.collectAsStateWithLifecycle()
    val p = progress ?: return
    if (p.finished || p.pair) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.library_export_progress, p.position, p.total),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    p.current?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        (ExportWorkRuntime.state.value as? ExportRuntimeState.Running)
                            ?.let { ExportWorkRuntime.cancel(it.runId) }
                    },
                ) { Text(stringResource(R.string.library_export_stop)) }
            }
            LinearProgressIndicator(
                progress = { ((p.done + p.failed).toFloat() / p.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
        }
    }
}

/** The finished batch's one snackbar, while the Library is on screen. */
@Composable
internal fun LibraryExportResultEffect(snackbar: SnackbarHostState, scope: CoroutineScope) {
    val ctx = LocalContext.current
    val progress by BatchExportTracker.state.collectAsStateWithLifecycle()
    LaunchedEffect(progress) {
        val p = progress ?: return@LaunchedEffect
        if (!p.finished || p.pair) return@LaunchedEffect
        val message = when {
            p.cancelled -> ctx.getString(R.string.editor_snack_batch_cancelled, p.done, p.total)
            p.failed > 0 -> ctx.getString(R.string.editor_snack_batch_done_failed, p.done, p.total, p.failed)
            else -> ctx.getString(R.string.editor_snack_batch_done, p.done, p.total)
        }
        // Outside this effect: acknowledging changes its key, which would cancel a snackbar
        // shown from in here.
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
        BatchExportTracker.acknowledge()
    }
}
