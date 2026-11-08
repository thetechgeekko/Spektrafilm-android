/*
 * Spektrafilm for Android — applying the loaded engine catalog to the editor, and the offer to
 * choose an expired source again. GPLv3. Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273).
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * One-time engine init. EditorViewModel loads the catalog (process-wide engine from
 * EngineHolder, profile ids, built-in looks) once, off the main thread; this applies it:
 * engine, profiles and the checkpoint restore land in the same main-thread step, so no render
 * can start with un-restored params.
 */
internal fun applyEditorCatalog(
    screen: EditorScreenState,
    loaded: EditorCatalog,
    ctx: Context,
    session: EditorSession,
    settings: AppSettings,
    toolVm: EditorToolViewModel,
    presetVm: PresetViewModel,
    initialRestoredSession: EditorSessionDocument?,
    initialSessionRestoreNotice: String?,
    sourceUri: Uri?,
    sourceKind: SourceKind,
    refreshPresets: () -> Unit,
    statusState: MutableState<String>,
) {
    val defaultsJsonState = screen.defaultsJsonState
    val recipeReadyState = screen.recipeReadyState
    val previewTickState = screen.previewTickState
    val state = session.params
    var status by statusState
    var defaultsJson by defaultsJsonState
    var recipeReady by recipeReadyState
    var previewTick by previewTickState
    val list = loaded.profiles
    val restore = session.applyCatalog(
        profiles = list,
        applyDefaults = settings::applyDefaultsTo,
        restored = initialRestoredSession?.takeIf { restored ->
            sourceUri?.toString() == restored.source.uri &&
                sourceKind == restored.source.kind
        },
    )
    val freshDefaults = restore.freshDefaults
    if (restore.restored) toolVm.onAction(EditorToolAction.ClampMask(state.localAdjustments.size))
    if (restore.rebaselined) {
        presetVm.onAction(PresetAction.ClearAmount)
        presetVm.onAction(PresetAction.SetClipboard(null))
        status = ctx.getString(R.string.editor_status_session_migrated)
    }
    session.adoptCatalog(loaded)
    refreshPresets()
    if (
        initialSessionRestoreNotice == null &&
        status == ctx.getString(R.string.editor_status_initializing)
    ) {
        status = if (initialRestoredSession == null) {
            ctx.resources.getQuantityString(R.plurals.editor_status_ready_profiles, list.size, list.size)
        } else {
            ctx.resources.getQuantityString(R.plurals.editor_status_session_restored_profiles, list.size, list.size)
        }
    }
    defaultsJson = freshDefaults
    recipeReady = true
    previewTick++
}

@Composable
@NonRestartableComposable
internal fun EditorSourceExpiredEffect(
    sourceAuthorizationRequired: Boolean,
    sourceKind: SourceKind,
    ctx: Context,
    exportVm: ExportViewModel,
    snackbarHost: SnackbarHostState,
    rawPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    photoPicker: ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>,
) {
    LaunchedEffect(sourceAuthorizationRequired, sourceKind) {
        if (!sourceAuthorizationRequired) return@LaunchedEffect
        exportVm.onAction(ExportAction.CloseSheet)
        snackbarHost.currentSnackbarData?.dismiss()
        val result = snackbarHost.showSnackbar(
            message = ctx.getString(R.string.editor_snack_source_expired),
            actionLabel = ctx.getString(R.string.editor_snack_choose_again),
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            if (sourceKind == SourceKind.RAW) {
                rawPicker.launch(arrayOf("*/*"))
            } else {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
        }
    }
}
