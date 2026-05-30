/*
 * Spektrafilm for Android — in-session edit history (undo / redo). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A bounded two-stack undo/redo store over full-editing-state SNAPSHOTS. A snapshot
 * is the EXACT same JSON the recipe/preset layer already produces — Presets.toJsonString(state)
 * — paired with the editor-local manual rotation (which lives outside ParamsState). No
 * parallel params model: restoring a snapshot decodes that JSON back into the live
 * ParamsState via Presets.decode and re-applies the rotation, then the caller bumps
 * previewTick exactly like the recipe restore-on-open path.
 *
 * Coalescing is the CALLER's job (see MainActivity): this store only records discrete,
 * already-settled snapshots. The "commit" model used there pushes the PREVIOUS settled
 * snapshot when a new settled state differs, so one slider drag (which settles once) =
 * one undo entry.
 *
 * Stacks are capped (CAP) to bound memory; the oldest undo entry is dropped when full.
 * canUndo/canRedo are Compose state so the top-bar buttons recompose enable/disable.
 */
package com.spectrafilm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A single point-in-time editing state: the full params JSON + the manual rotation. */
data class EditSnapshot(val paramsJson: String, val rotationDegrees: Int)

/**
 * Bounded undo/redo stacks of [EditSnapshot]s. Not thread-safe; all mutation happens on
 * the Compose/main thread (the editor never touches it off the main dispatcher).
 */
class EditHistory(private val cap: Int = 50) {

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /**
     * Record [snapshot] as a new undo step. A fresh edit always invalidates the redo
     * branch (standard undo/redo semantics). When the cap is exceeded the OLDEST undo
     * entry is dropped so memory stays bounded.
     */
    fun push(snapshot: EditSnapshot) {
        undoStack.addLast(snapshot)
        while (undoStack.size > cap) undoStack.removeFirst()
        redoStack.clear()
        refreshFlags()
    }

    /**
     * Undo: returns the snapshot to restore, having moved [current] (the live state the
     * caller passes in) onto the redo stack. Returns null when there is nothing to undo.
     */
    fun undo(current: EditSnapshot): EditSnapshot? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        while (redoStack.size > cap) redoStack.removeFirst()
        refreshFlags()
        return prev
    }

    /**
     * Redo: returns the snapshot to restore, having moved [current] onto the undo stack.
     * Returns null when there is nothing to redo.
     */
    fun redo(current: EditSnapshot): EditSnapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        while (undoStack.size > cap) undoStack.removeFirst()
        refreshFlags()
        return next
    }

    /** Drop all history (e.g. on a source change). Leaves both stacks empty. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        refreshFlags()
    }
}
