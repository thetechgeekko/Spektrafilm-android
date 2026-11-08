/*
 * Spektrafilm for Android — editor recipe (sidecar) effects. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Two LaunchedEffect bodies of EditorScreen, moved verbatim (#273): restoring the
 * non-destructive recipe when the source changes, and its debounced auto-save. Each still runs
 * in its LaunchedEffect's own scope with the same keys. Editor `var`s are passed as their state
 * holders and re-bound as local delegates, so reads after a suspension stay live.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Restores the photo's recipe when the source changes; see the comment at its LaunchedEffect in EditorScreen. */
internal suspend fun CoroutineScope.runRecipeRestore(
    screen: EditorScreenState,
    session: EditorSession,
    ctx: Context,
    settings: AppSettings,
    recipeKey: String?,
    recipeRepo: RecipeRepository,
    restoredRecipeKey: String?,
    lastRestoredKeyState: MutableState<String?>,
    profilesState: State<List<String>>,
    recipeAccessState: MutableState<EditorRecipeAccess>,
    statusState: MutableState<String>,
    offerPreviousEdit: suspend () -> Unit = {},
) {
    val snackbarHost = screen.snackbarHost
    val hasRecipeState = screen.hasRecipeState
    val previewTickState = screen.previewTickState
    val recipeReadyState = screen.recipeReadyState
    val state = session.params
    val editHistory = session.history
    val committedSnapshotState = session.committedSnapshot
    val restoringState = session.restoring
    val rotationState = session.rotation
    var committedSnapshot by committedSnapshotState
    var hasRecipe by hasRecipeState
    var lastRestoredKey by lastRestoredKeyState
    var previewTick by previewTickState
    val profiles by profilesState
    var recipeAccess by recipeAccessState
    var recipeReady by recipeReadyState
    var restoring by restoringState
    var rotation by rotationState
    var status by statusState
    if (!recipeReady) return
    if (recipeKey == null) {
        hasRecipe = false
        recipeAccess = EditorRecipeAccess.None
        // Switched to a keyless source (the demo image): still drop cross-image
        // history and re-baseline once for the demo's current state.
        if (lastRestoredKey != null) {
            lastRestoredKey = null
            editHistory.clear()
            committedSnapshot = null
            restoring = true
        }
        return
    }
    val preserveSessionCursor = recipeKey == restoredRecipeKey
    val sourceChanged = recipeKey != lastRestoredKey
    lastRestoredKey = recipeKey
    // New source: undo must never cross images. Drop the history and re-baseline so
    // the just-restored/default state for THIS image is the empty-history baseline
    // (canUndo=false). `restoring` makes the next capture settle adopt the new
    // baseline without recording it as an edit.
    if (sourceChanged) {
        editHistory.clear()
        committedSnapshot = null
        restoring = true
    }
    // Submission itself happens on Main and is process-owned. If an old Activity already
    // submitted an autosave, FIFO guarantees this recreated restore reads after its commit.
    var restored: RecipeReadResult
    while (true) {
        val pendingRestore = RecipeWorkRuntime.submit {
            val before = recipeRepo.generation(recipeKey)
            val result = recipeRepo.readResult(recipeKey)
            Triple(before, result, recipeRepo.generation(recipeKey))
        }
        val (before, result, after) = pendingRestore.await()
        val classified = classifyEditorRecipeAccess(recipeKey, before, after, result)
        recipeAccess = classified
        if (classified is EditorRecipeAccess.Pending) continue
        restored = result
        break
    }
    when (restored) {
        is RecipeReadResult.Loaded -> {
            hasRecipe = true
            if (!preserveSessionCursor) {
                Presets.decode(restored.document.params, state)
                rotation = SourceRotation.fromDegrees(restored.document.manualRotationDeg)
                previewTick++
                snackbarHost.currentSnackbarData?.dismiss()
                snackbarHost.showSnackbar(
                    message = ctx.getString(R.string.editor_snack_recipe_restored),
                    withDismissAction = true,
                )
            }
        }
        is RecipeReadResult.CorruptQuarantined -> {
            hasRecipe = false
            if (!preserveSessionCursor) {
                Recipes.resetToDefaults(state, settings, profiles)
                rotation = SourceRotation.NONE
                previewTick++
            }
            Diag.w("recipe quarantined: ${restored.reason}")
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(
                message = ctx.getString(R.string.editor_snack_recipe_quarantined),
                withDismissAction = true,
            )
        }
        RecipeReadResult.Missing -> {
            hasRecipe = false
            if (!preserveSessionCursor) {
                Recipes.resetToDefaults(state, settings, profiles)
                rotation = SourceRotation.NONE
                previewTick++
            }
            // A photo opened for the first time: offer the previous photo's look once.
            // Launched so the snackbar's wait doesn't hold up the restore; a new source
            // restarts this effect, which cancels a stale offer with it.
            if (sourceChanged && !preserveSessionCursor) launch { offerPreviousEdit() }
        }
        is RecipeReadResult.Unsupported -> {
            hasRecipe = true
            if (!preserveSessionCursor) {
                Recipes.resetToDefaults(state, settings, profiles)
                rotation = SourceRotation.NONE
                previewTick++
            }
            status = ctx.getString(R.string.editor_status_recipe_newer_version, restored.version)
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(
                message = ctx.getString(R.string.editor_snack_recipe_newer_version),
                withDismissAction = true,
            )
        }
        is RecipeReadResult.CorruptQuarantineFailed,
        is RecipeReadResult.IoFailure -> {
            hasRecipe = true
            if (!preserveSessionCursor) {
                Recipes.resetToDefaults(state, settings, profiles)
                rotation = SourceRotation.NONE
                previewTick++
            }
            val reason = when (restored) {
                is RecipeReadResult.CorruptQuarantineFailed -> restored.reason
                is RecipeReadResult.IoFailure -> restored.reason
                else -> error("unreachable")
            }
            Diag.w("recipe unavailable and writes paused: $reason")
            status = ctx.getString(R.string.editor_status_recipe_unavailable)
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(
                message = ctx.getString(R.string.editor_snack_recipe_unavailable),
                withDismissAction = true,
            )
        }
    }
}

/** Debounced recipe auto-save; see the comment at its LaunchedEffect in EditorScreen. */
internal suspend fun CoroutineScope.runRecipeAutosave(
    ctx: Context,
    state: ParamsState,
    recipeEditEpoch: RecipeEditEpoch,
    recipeKey: String?,
    recipeRepo: RecipeRepository,
    defaultsJsonState: MutableState<String?>,
    hasRecipeState: MutableState<Boolean>,
    recipeAccessState: MutableState<EditorRecipeAccess>,
    recipeReadyState: MutableState<Boolean>,
    rotationState: MutableState<SourceRotation>,
    sourceState: State<EditorSourceState>,
    statusState: MutableState<String>,
) {
    var defaultsJson by defaultsJsonState
    var hasRecipe by hasRecipeState
    var recipeAccess by recipeAccessState
    var recipeReady by recipeReadyState
    var rotation by rotationState
    var status by statusState
    if (!recipeReady || recipeKey == null) return
    val writableGeneration = recipeAccess.writableGenerationFor(recipeKey)
        ?: return
    val autosaveEpoch = recipeEditEpoch.snapshot()
    delay(700)
    if (!recipeEditEpoch.isCurrent(autosaveEpoch)) return
    if (recipeAccess.writableGenerationFor(recipeKey) != writableGeneration) {
        return
    }
    val current = runCatching { Presets.toJsonString(state) }.getOrNull()
    // A non-NONE manual rotation is itself an edit worth persisting, even when the
    // params are otherwise default — so only treat as "pristine" if rotation is NONE.
    if (current != null && current == defaultsJson && rotation == SourceRotation.NONE) {
        if (!hasRecipe) return
        val pendingDelete = RecipeWorkRuntime.submit {
            if (recipeRepo.generation(recipeKey) != writableGeneration) return@submit null
            recipeRepo.delete(recipeKey)
            recipeRepo.generation(recipeKey)
        }
        val newGeneration = try {
            pendingDelete.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Diag.w("pristine recipe delete failed: ${failure.message}")
            status = ctx.getString(R.string.editor_status_recipe_clear_failed)
            return
        }
        if (newGeneration != null) {
            recipeAccess = EditorRecipeAccess.Writable(recipeKey, newGeneration)
            hasRecipe = false
        }
        return
    }
    val saved = try {
        // Reuse the main-thread serialization from above (torn-snapshot safety);
        // only the envelope build + file write cross to IO.
        val paramsJson = current ?: Presets.toJsonString(state)
        val savedSourceName = sourceState.value.displayName
        val savedRotationDegrees = rotation.degrees
        val pendingSave = RecipeWorkRuntime.submit {
            recipeRepo.saveJson(
                recipeKey,
                paramsJson,
                savedSourceName,
                savedRotationDegrees,
                writableGeneration,
            )
        }
        pendingSave.await()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Diag.w("recipe auto-save failed: ${failure.message}")
        return
    }
    if (saved) hasRecipe = true
}

/**
 * Queues the editor's current recipe NOW, skipping the 700 ms debounce of [runRecipeAutosave].
 * Leaving the editor disposes that effect, so an edit made just before leaving would reach the
 * recipe file only when the editor came back -- too late for anything that reads the recipe
 * meanwhile, such as the Library's Paste edit ([LookSync]), which runs on the same FIFO and so
 * sees this save. Serializes on the calling (main) thread; never waits. Applies the same gates as
 * the auto-save (recipe ready, writable generation). A pristine state over an existing recipe
 * (say, undone back to defaults) is SAVED as the defaults rather than deleted: deleting moves the
 * generation the editor holds, and leaving the old file would let Paste edit spread a look the
 * user discarded. The auto-save deletes it when the editor comes back.
 */
internal fun flushRecipeBeforeLeaving(
    state: ParamsState,
    recipeKey: String?,
    recipeReady: Boolean,
    recipeAccess: EditorRecipeAccess,
    defaultsJson: String?,
    rotation: SourceRotation,
    sourceName: String,
    hasRecipe: Boolean,
    recipeRepo: RecipeRepository,
) {
    if (!recipeReady || recipeKey == null) return
    val writableGeneration = recipeAccess.writableGenerationFor(recipeKey) ?: return
    val current = runCatching { Presets.toJsonString(state) }.getOrNull() ?: return
    if (current == defaultsJson && rotation == SourceRotation.NONE && !hasRecipe) return
    val degrees = rotation.degrees
    RecipeWorkRuntime.submit {
        runCatching { recipeRepo.saveJson(recipeKey, current, sourceName, degrees, writableGeneration) }
            .onFailure { Diag.w("recipe flush failed: ${it.message}") }
    }
}
