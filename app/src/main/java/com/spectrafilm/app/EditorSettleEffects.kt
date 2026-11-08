/*
 * Spektrafilm for Android — the editor's settle effects: recipe auto-save, undo/redo capture and
 * the session-cursor checkpoints. GPLv3. Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273), same order. Non-restartable, so it composes inside
 * EditorScreen's own scope exactly as the inline code did.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

@Composable
@NonRestartableComposable
internal fun EditorSettleEffects(
    screen: EditorScreenState,
    ctx: Context,
    session: EditorSession,
    exportVm: ExportViewModel,
    snapshot: SpektraParams,
    recipeKey: String?,
    recipeReady: Boolean,
    defaultsJson: String?,
    rotation: SourceRotation,
    recipeAccess: EditorRecipeAccess,
    sourceUri: Uri?,
    sourceKind: SourceKind,
    sourceName: String?,
    sourceAuthorizationRequired: Boolean,
    activeCategory: Category?,
    toolUi: EditorToolUiState,
    presetUi: PresetUiState,
    recipeEditEpoch: RecipeEditEpoch,
    recipeRepo: RecipeRepository,
    recipeAccessState: MutableState<EditorRecipeAccess>,
    statusState: MutableState<String>,
    onLiveCursorMutated: () -> Unit,
    checkpointEditorSession: () -> Boolean,
    currentSessionCheckpoint: () -> Boolean,
) {
    val defaultsJsonState = screen.defaultsJsonState
    val hasRecipeState = screen.hasRecipeState
    val recipeReadyState = screen.recipeReadyState
    val state = session.params
    val rotationState = session.rotation
    val sourceState = session.source
    // --- Non-destructive recipe: debounced auto-save ---
    // Grade fields included for the same reason as the render trigger above:
    // Presets.toJsonString already SERIALIZES them, so the recipe content was
    // right — but a grade-only edit never fired this effect, so it was not
    // persisted until some other change happened to save it along the way.
    LaunchedEffect(snapshot, recipeKey, recipeReady, defaultsJson, rotation,
        recipeAccess,
        state.rawWhiteBalance, state.rawTemperature, state.rawTint,
        state.rawHotPixels, state.rawChromaNoise, state.straightenDegrees,
        state.creativeWbTemp, state.creativeWbTint, state.balanceToFilmStock,
        state.localAdjustments,
        state.saturation, state.vibrance, state.gamutCompress) {
        runRecipeAutosave(
            ctx = ctx,
            state = state,
            recipeEditEpoch = recipeEditEpoch,
            recipeKey = recipeKey,
            recipeRepo = recipeRepo,
            defaultsJsonState = defaultsJsonState,
            hasRecipeState = hasRecipeState,
            recipeAccessState = recipeAccessState,
            recipeReadyState = recipeReadyState,
            rotationState = rotationState,
            sourceState = sourceState,
            statusState = statusState,
        )
    }

    // --- Undo/redo capture (debounced coalescing) ---
    // Keyed on the same settle inputs as the auto-save effect, so it re-arms on every
    // edit and only the LAST change in a burst survives the delay (a slider drag fires
    // many state changes but they all collapse into ONE surviving settle). After the
    // state settles we compare the new snapshot to the last COMMITTED one:
    //   • restoring == true  -> this settle is the programmatic undo/redo restore;
    //     don't record it, just clear the flag (session.restore already moved the
    //     committed pointer). This is what breaks the feedback loop.
    //   • committedSnapshot == null -> first settle for this source/baseline; adopt it
    //     as the baseline WITHOUT pushing (canUndo stays false until a real edit).
    //   • differs from committed -> the user made an edit: push the PREVIOUS committed
    //     snapshot onto the undo stack and advance the committed pointer. One settled
    //     drag => exactly one undo step that returns to the pre-drag state.
    // Uses a slightly shorter delay than auto-save so a quick undo right after an edit
    // still finds the entry recorded; both are debounced independently and key on the
    // same inputs, so this adds no extra previewTick churn or re-decodes.
    // Grade fields added here too. snapshotNow() already captures them (it goes
    // through Presets.toJsonString), so the CONTENT was complete and only the
    // TRIGGER was missing — which meant a grade-only edit pushed no undo step,
    // and a later unrelated edit then collapsed both into a single step that
    // undid more than the user did.
    LaunchedEffect(snapshot, recipeKey, recipeReady, rotation,
        state.rawWhiteBalance, state.rawTemperature, state.rawTint,
        state.rawHotPixels, state.rawChromaNoise, state.straightenDegrees,
        state.creativeWbTemp, state.creativeWbTint, state.balanceToFilmStock,
        state.localAdjustments,
        state.saturation, state.vibrance, state.gamutCompress) {
        if (!recipeReady) return@LaunchedEffect
        delay(500)
        // settleDecision (unit-tested in EditHistoryTest) handles the subtle case where a
        // real edit lands within the restore settle window: it pushes the restored baseline
        // so the edit stays undoable instead of being silently adopted (one-undo-step loss).
        if (session.settle()) onLiveCursorMutated()
        checkpointEditorSession()
    }

    // UI-only editor cursor state does not participate in the render snapshot. Persist it on a
    // short debounce so category/tool/mask, copy/paste and export-sheet choices survive a
    // navigation round-trip without enqueueing one document per keystroke.
    LaunchedEffect(
        recipeReady,
        sourceUri,
        sourceKind,
        sourceName,
        sourceAuthorizationRequired,
        activeCategory,
        toolUi,
        presetUi,
    ) {
        if (!recipeReady) return@LaunchedEffect
        delay(250)
        checkpointEditorSession()
    }
    // Export-sheet choices take the same 250 ms debounce, but are collected here rather than
    // used as a key above: a key is a composition read, and that would recompose the whole
    // editor on every export-state change (#270). collectLatest restarts the wait on each
    // change, exactly as a key change restarts the effect above. drop(1) skips the value
    // the StateFlow replays on collection, which the effect above already checkpoints.
    LaunchedEffect(recipeReady) {
        if (!recipeReady) return@LaunchedEffect
        exportVm.uiState.drop(1).collectLatest {
            delay(250)
            // The updated-state copy: this collector outlives recompositions, and the local
            // function captures the composition it was declared in.
            currentSessionCheckpoint()
        }
    }
}
