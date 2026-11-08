/*
 * Spektrafilm for Android — the editor session document and its lifecycle checkpoints. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273): what a checkpoint captures, the live-editor
 * readiness publication, and the ON_STOP / teardown checkpoint. Values read after launch are
 * passed as lambdas, so those reads stay live.
 */
package com.spectrafilm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.lifecycle.Lifecycle

internal fun buildEditorSessionDocument(
    recipeReady: Boolean,
    toolVm: EditorToolViewModel,
    state: ParamsState,
    sourceState: State<EditorSourceState>,
    session: EditorSession,
    committedSnapshot: EditSnapshot?,
    editHistory: EditHistory,
    activeCategory: Category?,
    presetVm: PresetViewModel,
    exportVm: ExportViewModel,
): EditorSessionDocument? {
    if (!recipeReady) return null
    val overlay = toolVm.current.overlay
    val selectedMask = clampSelectedMaskIndex(
        toolVm.current.maskIndex,
        state.localAdjustments.size,
    )
    // Crop/mask-geometry gesture drafts live only inside their overlay Composable. Persist
    // the selected tool; a recreated overlay seeds a fresh, no-op draft from the committed
    // crop/mask geometry passed below, never from the disposed in-progress gesture.
    val committedOverlay = restorableEditorOverlay(overlay)
    val safeOverlay = if (
        committedOverlay == EditorOverlayTool.MASK_SAMPLE_COLOR ||
        committedOverlay == EditorOverlayTool.MASK_SAMPLE_LUMINANCE
    ) {
        committedOverlay.takeIf { selectedMask in state.localAdjustments.indices }
            ?: EditorOverlayTool.NONE
    } else {
        committedOverlay
    }
    return EditorSessionDocument(
        source = sourceState.value.let {
            it.copy(displayName = AtomicJsonStore.truncateUtf16Safely(it.displayName, 512))
        },
        current = session.snapshot(),
        committed = committedSnapshot,
        history = editHistory.snapshotState(),
        tool = EditorToolState(
            category = activeCategory,
            overlay = safeOverlay,
            maskIndex = selectedMask,
        ),
        preset = presetVm.current.toSessionState(),
        export = exportVm.current.toSessionState(),
    )
}

@Composable
@NonRestartableComposable
internal fun EditorSessionLifecycleEffects(
    recipeReady: Boolean,
    lifecycle: Lifecycle,
    editorInstance: Long,
    captureEditorSessionDocument: () -> EditorSessionDocument?,
    liveCursorMutated: () -> Boolean,
    onSessionCheckpoint: (EditorSessionDocument, liveMutation: Boolean) -> Boolean,
    currentSessionCheckpoint: () -> Boolean,
) {
    LaunchedEffect(recipeReady) {
        if (!recipeReady) return@LaunchedEffect
        runCatching { captureEditorSessionDocument() }
            .onSuccess { document ->
                if (document != null) {
                    editorProbe.publishLiveEditorSnapshot(editorInstance, document)
                    onSessionCheckpoint(document, liveCursorMutated())
                    editorProbe.publishLiveEditorReady(editorInstance, document)
                }
            }
            .onFailure {
                Diag.w("live editor readiness rejected (${it.javaClass.simpleName})")
            }
    }

    // Lifecycle recreation is the only time a non-navigation teardown can happen after the
    // last debounced settle. Capture synchronously on the main thread; the latest-only runtime
    // owns the bounded IO after this Activity is gone.
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) currentSessionCheckpoint()
        }
        lifecycle.addObserver(observer)
        onDispose {
            currentSessionCheckpoint()
            lifecycle.removeObserver(observer)
        }
    }
}
