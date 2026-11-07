/*
 * Spektrafilm for Android — the editor's Source panel. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Source panel and the two source actions it runs inline: switching to the demo image
 * and resetting the photo's edits. Moved verbatim out of EditorScreen (#273); the editor's
 * `var`s arrive as their state holders and its local functions as lambdas.
 */
@Composable
internal fun EditorSourcePanel(
    onToggleHistogram: () -> Unit,
    /** Drops the preset Amount blend (Reset makes the defaults the new baseline). */
    onClearPresetAmount: () -> Unit,
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    settings: AppSettings,
    snackbarHost: SnackbarHostState,
    showHistogram: Boolean,
    sourceRuntime: SourceAccessRuntime,
    recipeRepo: RecipeRepository,
    recipeKey: String?,
    recipeEditEpoch: RecipeEditEpoch,
    editHistory: EditHistory,
    photoPicker: ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>,
    rawPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    sourceState: State<EditorSourceState>,
    showDemoSource: () -> Unit,
    statusState: MutableState<String>,
    previewTickState: MutableIntState,
    rotationState: MutableState<SourceRotation>,
    hasRecipeState: MutableState<Boolean>,
    recipeAccessState: MutableState<EditorRecipeAccess>,
    committedSnapshotState: MutableState<EditSnapshot?>,
    restoringState: MutableState<Boolean>,
    profilesState: State<List<String>>,
    checkpointEditorSessionFn: () -> Boolean,
    retireSourceResourcesFn: () -> Unit,
    resetEditorCursorForSourceChangeFn: () -> Unit,
    reconcileVisibleSourceAfterFailureFn: (SourceRestoreResult?) -> Unit,
) {
    val source = sourceState.value
    var status by statusState
    var previewTick by previewTickState
    var rotation by rotationState
    var hasRecipe by hasRecipeState
    var recipeAccess by recipeAccessState
    var committedSnapshot by committedSnapshotState
    var restoring by restoringState
    val profiles by profilesState
    val sourceAccess = sourceRuntime.coordinator
    val sourceMutationGate = sourceRuntime.mutations
    fun checkpointEditorSession() = checkpointEditorSessionFn()
    fun retireSourceResources() = retireSourceResourcesFn()
    fun resetEditorCursorForSourceChange() = resetEditorCursorForSourceChangeFn()
    fun reconcileVisibleSourceAfterFailure(durable: SourceRestoreResult?) = reconcileVisibleSourceAfterFailureFn(durable)
    SourcePanel(
        sourceName = source.displayName,
        status = status,
        showHistogram = showHistogram,
        onToggleHistogram = onToggleHistogram,
        hasRecipe = hasRecipe,
        needsAuthorization = source.authorizationRequired,
        onReauthorize = {
            if (sourceState.value.kind == SourceKind.RAW) {
                rawPicker.launch(arrayOf("*/*"))
            } else {
                photoPicker.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            }
        },
        onPickPhoto = {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onOpenRaw = { rawPicker.launch(arrayOf("*/*")) },
        onUseDemo = {
            val selectionGeneration = sourceMutationGate.begin()
            // Commit an explicit demo tombstone before changing either the
            // visible source or its session checkpoint. A process kill at
            // any later instruction restores demo, never the older URI.
            val pendingDemo = sourceRuntime.submitReconciled(selectionGeneration) {
                sourceAccess.selectDemo()
            }
            scope.launch {
                val outcome = pendingDemo.await() ?: return@launch
                if (!sourceMutationGate.isCurrent(selectionGeneration)) {
                    return@launch
                }
                when (outcome) {
                    is ReconciledSourceMutation.Applied -> {
                        val shown = sourceState.value
                        if (shown.uri != null || shown.kind != SourceKind.DEMO) {
                            retireSourceResources()
                            resetEditorCursorForSourceChange()
                        }
                        showDemoSource()
                        rotation = SourceRotation.NONE
                        checkpointEditorSession()
                        previewTick++
                        status = ctx.getString(R.string.editor_status_demo_image)
                    }
                    is ReconciledSourceMutation.Rejected -> {
                        Diag.w("demo selection failed: ${outcome.failure.message}")
                        reconcileVisibleSourceAfterFailure(outcome.durableState)
                        snackbarHost.currentSnackbarData?.dismiss()
                        snackbarHost.showSnackbar(
                            ctx.getString(R.string.editor_snack_demo_failed),
                            withDismissAction = true,
                        )
                    }
                }
            }
        },
        onResetEdits = {
            // Invalidate every pre-reset debounce synchronously. A save that
            // was already submitted is FIFO-before delete; one not submitted
            // yet sees this epoch change and cannot queue behind delete.
            recipeEditEpoch.invalidate()
            // Submit before attaching a UI waiter: recreation can cancel the
            // waiter, never the durable delete or its position before restore.
            val pendingDelete = RecipeWorkRuntime.submit {
                recipeRepo.delete(recipeKey)
            }
            scope.launch {
                try {
                    pendingDelete.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Diag.w("explicit recipe reset delete failed: ${failure.message}")
                    status = ctx.getString(R.string.editor_status_recipe_clear_failed)
                    snackbarHost.currentSnackbarData?.dismiss()
                    snackbarHost.showSnackbar(
                        ctx.getString(R.string.editor_snack_recipe_clear_failed),
                    )
                    return@launch
                }
                recipeAccess = recipeKey?.let {
                    EditorRecipeAccess.Writable(
                        it,
                        recipeRepo.generation(it),
                    )
                } ?: EditorRecipeAccess.None
                Recipes.resetToDefaults(state, settings, profiles)
                onClearPresetAmount()
                hasRecipe = false; rotation = SourceRotation.NONE
                // Reset clears history: the defaults become the new
                // empty-history baseline (you can't undo back across a
                // reset). `restoring` makes the next settle adopt it.
                editHistory.clear(); committedSnapshot = null; restoring = true
                status = ctx.getString(R.string.editor_status_edits_reset); previewTick++
                snackbarHost.currentSnackbarData?.dismiss()
                snackbarHost.showSnackbar(ctx.getString(R.string.editor_snack_edits_reset))
            }
        },
    )
}
