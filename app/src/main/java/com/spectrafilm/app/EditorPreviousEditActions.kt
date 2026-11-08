/*
 * Spektrafilm for Android — "Apply previous edit" in the editor. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * #582: moved out of EditorScreen. The newest OTHER photo's saved look, looked up on the
 * recipe worker so it is ordered after any autosave of the photo just left. Applying it is
 * exactly Paste settings: the whole look is replaced (rotation stays this photo's), and the
 * settle effect makes it undoable.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.json.JSONObject

internal class EditorPreviousEditActions(
    private val previousEditState: MutableState<PreviousEdit?>,
    private val ctx: Context,
    private val screen: EditorScreenState,
    private val session: EditorSession,
    private val presetVm: PresetViewModel,
    private val statusState: MutableState<String>,
    private val recipeKey: String?,
    private val recipeRepo: RecipeRepository,
) {
    /** The look offered, or null when no other photo has one. */
    val previousEdit: PreviousEdit? get() = previousEditState.value

    suspend fun lookUp(): PreviousEdit? {
        val key = recipeKey
        return RecipeWorkRuntime.submit { runCatching { recipeRepo.previousEdit(key) }.getOrNull() }
            .await()
            .also { previousEditState.value = it }
    }

    fun apply(edit: PreviousEdit) {
        // Accepted right after opening, the settle effect may not have taken this photo's
        // baseline yet (it is debounced and was cleared by the source change); capture it now
        // so settleDecision pushes it and the applied look stays one undo away.
        if (session.committedSnapshot.value == null) session.committedSnapshot.value = session.snapshot()
        runCatching { Presets.decode(JSONObject(edit.paramsJson), session.params) }
            .onSuccess {
                presetVm.onAction(PresetAction.ClearAmount)
                statusState.value =
                    ctx.getString(R.string.editor_status_previous_applied, previousEditDisplayName(ctx, edit))
                screen.previewTickState.intValue++
            }
            .onFailure { statusState.value = ctx.getString(R.string.editor_status_paste_failed, it.message) }
    }

    /** Offers the previous edit in a snackbar after a photo with no edit of its own opens. */
    suspend fun offer() {
        val edit = lookUp() ?: return
        val result = screen.snackbarHost.showSnackbar(
            message = ctx.getString(R.string.editor_snack_apply_previous, previousEditDisplayName(ctx, edit)),
            actionLabel = ctx.getString(R.string.editor_snack_apply_previous_action),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) apply(edit)
    }
}

/** The previous-edit actions for this photo; the offer is looked up once its recipe is ready. */
@Composable
internal fun rememberEditorPreviousEditActions(
    ctx: Context,
    screen: EditorScreenState,
    session: EditorSession,
    presetVm: PresetViewModel,
    statusState: MutableState<String>,
    recipeKey: String?,
    recipeReady: Boolean,
    recipeRepo: RecipeRepository,
): EditorPreviousEditActions {
    val previousEditState = remember(recipeKey) { mutableStateOf<PreviousEdit?>(null) }
    // Built per composition, like the local functions it replaces.
    val actions = EditorPreviousEditActions(
        previousEditState, ctx, screen, session, presetVm, statusState, recipeKey, recipeRepo,
    )
    LaunchedEffect(recipeKey, recipeReady) {
        if (recipeReady) actions.lookUp()
    }
    return actions
}
