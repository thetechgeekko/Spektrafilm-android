/*
 * Spektrafilm for Android — editor hardware-keyboard shortcuts and Back handling. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273). Called at the same point in EditorScreen's
 * composition as before, so the Back handlers register in the same order relative to the
 * rest of the editor (the last one registered wins).
 */
package com.spectrafilm.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException

@Composable
internal fun EditorBackAndShortcuts(
    toolUi: EditorToolUiState,
    toolVm: EditorToolViewModel,
    viewerUi: EditorViewerUiState,
    viewerVm: EditorViewerViewModel,
    exportMaskVisible: Boolean,
    magnifierOpen: Boolean,
    activeCategory: Category?,
    onCloseCategory: () -> Unit,
    /** Leave the Crop tab, keeping its draft (one undo step), as switching tabs does. */
    onCloseCrop: () -> Unit,
    /** The Crop tab's draft holds an edit not yet in the history; Undo takes it back first. */
    cropDraftPending: Boolean,
    onBackPressedDispatcher: OnBackPressedDispatcher,
    setEditorShortcutHandler: (((EditorShortcut) -> Boolean)?) -> Unit,
    editHistory: EditHistory,
    doUndo: () -> Unit,
    doRedo: () -> Unit,
    openPhotoPicker: () -> Unit,
    requestExport: () -> Unit,
    hasSource: Boolean,
    openPhotoInfo: () -> Unit,
    previousEdit: PreviousEdit?,
    applyPreviousEdit: (PreviousEdit) -> Unit,
    onOverlayBackProgress: (Float) -> Unit,
    onPanelBackProgress: (Float) -> Unit,
    closeMagnifier: () -> Unit,
) {
    // --- hardware keyboard shortcuts (EditorShortcuts.kt) ---
    // Every shortcut calls the SAME action as its on-screen control. Esc goes through the
    // back dispatcher, so it closes exactly what Back would (and in the same order), but
    // only when something is open: at the root Back leaves the app, and Esc must not.
    val shortcutHandler by rememberUpdatedState<(EditorShortcut) -> Boolean> { shortcut ->
        // The magnifier is a full-screen overlay in this window (not a dialog), so it
        // has to block shortcuts too; an undo behind it would invalidate its crop.
        val overlayOpen = toolUi.anyOverlayOpen || exportMaskVisible || magnifierOpen
        // The Crop tab is not modal: Undo / Redo stay live there, since Undo is its way back.
        val historyBlocked = toolUi.maskGeometryOpen || toolUi.sampleOpen || exportMaskVisible || magnifierOpen
        when (shortcut) {
            EditorShortcut.CLOSE -> {
                val closable = viewerUi.chromeHidden || overlayOpen || activeCategory != null
                if (closable) onBackPressedDispatcher.onBackPressed()
                closable
            }
            EditorShortcut.UNDO -> !historyBlocked && (editHistory.canUndo || cropDraftPending) &&
                run { doUndo(); true }
            EditorShortcut.REDO -> !historyBlocked && !cropDraftPending && editHistory.canRedo && run { doRedo(); true }
            EditorShortcut.OPEN -> !overlayOpen && run {
                openPhotoPicker()
                true
            }
            EditorShortcut.EXPORT -> !overlayOpen && run { requestExport(); true }
            EditorShortcut.TOGGLE_COMPARE -> !overlayOpen && run {
                viewerVm.onAction(EditorViewerAction.ToggleCompare)
                true
            }
            // Delivered to whichever preview is on screen (Viewer.kt), the same transform
            // as its wheel / + / - controls.
            EditorShortcut.ZOOM_IN -> !overlayOpen && run { viewerVm.requestZoom(ViewerZoom.IN); true }
            EditorShortcut.ZOOM_OUT -> !overlayOpen && run { viewerVm.requestZoom(ViewerZoom.OUT); true }
            EditorShortcut.ZOOM_FIT -> !overlayOpen && run { viewerVm.requestZoom(ViewerZoom.FIT); true }
            EditorShortcut.PHOTO_INFO -> !overlayOpen && hasSource && run { openPhotoInfo(); true }
            EditorShortcut.APPLY_PREVIOUS -> !overlayOpen && previousEdit?.let { applyPreviousEdit(it); true } == true
        }
    }
    DisposableEffect(Unit) {
        setEditorShortcutHandler { shortcutHandler(it) }
        onDispose { setEditorShortcutHandler(null) }
    }

    // --- back handling on the root editor ---
    // 0) full-screen tool open -> close it; 1) panel open -> close panel;
    // 2) else the system takes Back (to home).
    // Above every other rung: with the chrome hidden there is no visible control at
    // all, so Back has to mean "give me the app back" before it means anything else.
    BackHandler(enabled = viewerUi.chromeHidden) { viewerVm.onAction(EditorViewerAction.ShowChrome) }
    // The Crop tab: Back leaves it the way another tab would, keeping the crop (Undo reverts
    // it). Registered below the full-screen tools so a mask editor or eyedropper on top of it
    // would still take Back first.
    BackHandler(enabled = toolUi.cropOpen) { onCloseCrop() }
    // Predictive back on the full-screen tools (mask geometry, eyedropper): while the
    // swipe is held the tool shrinks away so the user can see Back will close it, and can
    // still cancel. Committing cancels the tool exactly as its own Cancel button does.
    PredictiveBackHandler(
        enabled = toolUi.maskGeometryOpen || toolUi.sampleOpen,
    ) { progress ->
        try {
            progress.collect { onOverlayBackProgress(it.progress) }
            val tool = toolVm.current
            when {
                tool.maskGeometryOpen -> toolVm.onAction(EditorToolAction.CloseMaskGeometry)
                tool.sampleOpen -> toolVm.onAction(EditorToolAction.CloseSample)
            }
        } catch (e: CancellationException) {
            // Gesture cancelled: leave the tool open.
        } finally {
            onOverlayBackProgress(0f)
        }
    }
    // Predictive back on the open panel: while the back swipe is in progress the panel
    // shrinks toward the bottom edge (M3 sheets), so the user sees what Back will do and
    // can still cancel. Committing closes it; cancelling springs it back.
    PredictiveBackHandler(
        enabled = !toolUi.anyOverlayOpen && !exportMaskVisible && activeCategory != null,
    ) { progress ->
        try {
            progress.collect { onPanelBackProgress(it.progress) }
            onCloseCategory()
        } catch (e: CancellationException) {
            // Gesture cancelled: leave the panel open.
        } finally {
            onPanelBackProgress(0f)
        }
    }
    // At the editor root Back is left to the system, so Android 13+ shows the predictive
    // back-to-home preview and the task simply goes to the background. Nothing is lost:
    // the session is checkpointed on ON_STOP and restored on the next launch. (This used
    // to be a "press Back again to exit" trap, which blocks that preview and which M3's
    // back model -- close the top surface, pop the stack, then exit -- does not have.)
    // Registered last so it wins: the full-screen grain magnifier sits over everything
    // above, and Back (or Esc) must close IT, not the panel or overlay hidden beneath.
    BackHandler(enabled = magnifierOpen) { closeMagnifier() }
}
