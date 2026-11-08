/*
 * Spektrafilm for Android — the editor's preset and LUT file launchers. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The four document launchers [rememberEditorPresetFiles] registers. */
internal class EditorPresetFiles(
    val presetImporter: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val presetExporter: ManagedActivityResultLauncher<String, Uri?>,
    val savedPresetExporter: ManagedActivityResultLauncher<String, Uri?>,
    val lutExporter: ManagedActivityResultLauncher<String, Uri?>,
)

/**
 * Preset import, live-state and saved-preset export, and the baked LUT's save. Split out of
 * [EditorScreen] (#273). [pendingPresetExportState] and [pendingLutTextState] are the editor's:
 * the panel sets them just before launching the matching exporter.
 */
@Composable
internal fun rememberEditorPresetFiles(
    ctx: Context,
    scope: CoroutineScope,
    dispatchers: AppDispatchers,
    state: ParamsState,
    presetRepo: PresetRepository,
    pendingPresetExportState: MutableState<String?>,
    pendingLutTextState: MutableState<String?>,
    onImported: () -> Unit,
    setStatus: (String) -> Unit,
): EditorPresetFiles {
    var pendingLutText by pendingLutTextState
    val presetImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Read the SAF stream off-main; decode (Compose-state write) on main.
            scope.launch {
                val text = withContext(dispatchers.io) {
                    runCatching { presetRepo.readUri(uri) }.getOrNull()
                }
                if (text == null) { setStatus(ctx.getString(R.string.editor_status_preset_import_failed)); return@launch }
                runCatching {
                    // Decode into a detached clone first. The live Compose state changes only
                    // after the entire bounded/versioned import has validated successfully.
                    val candidate = ParamsState()
                    Presets.decode(Presets.encode(state), candidate)
                    Presets.decode(org.json.JSONObject(text), candidate)
                    Presets.decode(Presets.encode(candidate), state)
                }
                    .onSuccess { setStatus(ctx.getString(R.string.editor_status_preset_imported)); onImported() }
                    .onFailure { setStatus(ctx.getString(R.string.editor_status_preset_import_failed_reason, it.message)) }
            }
        }
    }
    val presetExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // Serialize on main (live Compose-state read); write off-main.
                val json = runCatching { Presets.toJsonString(state) }.getOrNull()
                if (json == null) { setStatus(ctx.getString(R.string.editor_status_preset_export_failed)); return@launch }
                val r = withContext(dispatchers.io) { runCatching { presetRepo.exportJson(uri, json) } }
                r.onSuccess { setStatus(ctx.getString(R.string.editor_status_preset_exported)) }
                    .onFailure { setStatus(ctx.getString(R.string.editor_status_preset_export_failed_reason, it.message)) }
            }
        }
    }
    // Exports the SELECTED SAVED preset, byte-for-byte as stored. Distinct from
    // [presetExporter] above, which serialises live editor state — that one sat under
    // the saved-preset dropdown and looked like it exported the selection.
    var pendingPresetExport by pendingPresetExportState
    val savedPresetExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val name = pendingPresetExport
        pendingPresetExport = null
        if (uri != null && name != null) {
            scope.launch {
                val r = withContext(dispatchers.io) {
                    runCatching { presetRepo.exportJson(uri, presetRepo.read(name)) }
                }
                r.onSuccess { setStatus(ctx.getString(R.string.editor_status_preset_exported)) }
                    .onFailure {
                        setStatus(
                            ctx.getString(R.string.editor_status_preset_export_failed_reason, it.message),
                        )
                    }
            }
        }
    }
    val lutExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val text = pendingLutText
        if (uri != null && text != null) {
            scope.launch {
                // SAF write off-main; status (Compose state) lands back on main.
                val r = withContext(dispatchers.io) { runCatching { saveTextToUri(ctx, uri, text) } }
                r.onSuccess { setStatus(ctx.getString(R.string.editor_status_lut_saved)) }
                    .onFailure { setStatus(ctx.getString(R.string.editor_status_lut_save_failed, it.message)) }
            }
        }
        pendingLutText = null
    }
    return EditorPresetFiles(presetImporter, presetExporter, savedPresetExporter, lutExporter)
}
