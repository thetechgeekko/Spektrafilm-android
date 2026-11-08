/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Editor export state holder (#272, second ViewModel slice). The export sheet's choices and
 * the editor's export phase machine used to be six independent `remember` vars inside the
 * 6,000-line EditorScreen, written from a dozen effects and callbacks. They now live in one
 * immutable [ExportUiState], changed only through [ExportAction]s by a pure [reduce], so the
 * transitions are unit-testable on the JVM.
 *
 * Restoration authority is unchanged (docs/EDITOR_SESSION.md): the session document stays
 * the single source a new editor composition starts from. Every EditorScreen composition
 * calls [restore] with its reconciled checkpoint, so this ViewModel never resurrects state
 * the session document does not hold. It is a state holder, not a second store.
 */
package com.spectrafilm.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ExportUiState(
    val sheetOpen: Boolean,
    val options: ExportOptions,
    val keepGps: Boolean,
    val phase: EditorExportPhase,
    val runtimeRunId: Long?,
    /**
     * #162: content URI + OutputDescriptor MIME of the export this editor claimed; drives the
     * result mask's Share action. Session-local by design: [restore] clears it, so a recreated
     * Activity restoring SUCCESS still shows the result, just without one-tap share.
     */
    val lastShare: Pair<String, String>?,
    /**
     * The pill's Share half started this run: open the share sheet when it lands instead of
     * the "Saved" snackbar. Session-local like [lastShare], never journaled.
     */
    val shareWhenDone: Boolean = false,
) {
    // #161: busy/done are DERIVED from the one export state machine, never parallel flags.
    val inFlight: Boolean get() = phase == EditorExportPhase.RUNNING
    val done: Boolean get() = phase == EditorExportPhase.SUCCESS
    val maskVisible: Boolean get() = inFlight || done

    /** What the session checkpoint journals. [lastShare] is deliberately not part of it. */
    fun toSessionState() = EditorExportState(
        sheetOpen = sheetOpen,
        options = options,
        keepGps = keepGps,
        phase = phase,
        runtimeRunId = runtimeRunId,
    )

    companion object {
        fun from(restored: EditorExportState) = ExportUiState(
            sheetOpen = restored.sheetOpen,
            options = restored.options,
            keepGps = restored.keepGps,
            phase = restored.phase,
            runtimeRunId = restored.runtimeRunId,
            lastShare = null,
        )
    }
}

internal sealed interface ExportAction {
    data object OpenSheet : ExportAction
    data object CloseSheet : ExportAction
    data class SetOptions(val value: ExportOptions) : ExportAction
    data class SetKeepGps(val value: Boolean) : ExportAction

    /** This editor launched, or adopted, the process-owned run [runId]. */
    data class Running(val runId: Long) : ExportAction

    /** The runtime went idle; a RECONCILING editor no longer tracks any run id. */
    data object RuntimeIdle : ExportAction

    /**
     * Run [runId] is no longer this editor's to publish (source identity changed or its result
     * was discarded). Only acts if it is the run this editor tracks.
     */
    data class RunDisowned(val runId: Long) : ExportAction

    /** The tracked run reached a terminal outcome: stop tracking it and show [phase]. */
    data class Terminal(val phase: EditorExportPhase) : ExportAction

    /** Refine the shown phase without touching run tracking (e.g. SUCCESS -> RECONCILING). */
    data class SetPhase(val phase: EditorExportPhase) : ExportAction

    data class SetShare(val value: Pair<String, String>?) : ExportAction

    /** The user dismissed the result mask. */
    data object DismissResult : ExportAction

    /** The pill's Share half asked for the next result to go to the share sheet. */
    data object ShareWhenDone : ExportAction

    /** The pending share was handed to the share sheet (or can no longer happen). */
    data object ShareHandled : ExportAction
}

internal class ExportViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(
        ExportUiState(
            sheetOpen = false,
            options = ExportOptions(ExportFormat.JPEG, 95, ExportSize.FULL, 2048, ""),
            keepGps = false,
            phase = EditorExportPhase.IDLE,
            runtimeRunId = null,
            lastShare = null,
        ),
    )
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    /**
     * The latest state, read synchronously. Effects and callbacks read this rather than the
     * Compose-collected copy, which trails a write by one dispatch: a checkpoint taken right
     * after an action must journal the new phase, not the previous one.
     */
    val current: ExportUiState get() = _uiState.value

    /** Start a new editor composition from its reconciled session checkpoint. */
    fun restore(restored: EditorExportState) {
        _uiState.value = ExportUiState.from(restored)
    }

    fun onAction(action: ExportAction) {
        _uiState.update { reduce(it, action) }
    }

    companion object {
        fun reduce(state: ExportUiState, action: ExportAction): ExportUiState = when (action) {
            // Export through the sheet is a plain save: it never inherits a pending share.
            ExportAction.OpenSheet -> state.copy(sheetOpen = true, shareWhenDone = false)
            ExportAction.CloseSheet -> state.copy(sheetOpen = false)
            is ExportAction.SetOptions -> state.copy(options = action.value)
            is ExportAction.SetKeepGps -> state.copy(keepGps = action.value)
            // A new run replaces the previous result: Share must wait for THIS run's file,
            // never pick up the one before it.
            is ExportAction.Running -> state.copy(
                phase = EditorExportPhase.RUNNING,
                runtimeRunId = action.runId,
                lastShare = null,
            )
            ExportAction.RuntimeIdle ->
                if (state.phase == EditorExportPhase.RECONCILING) state.copy(runtimeRunId = null) else state
            is ExportAction.RunDisowned ->
                if (state.runtimeRunId == action.runId) {
                    state.copy(phase = EditorExportPhase.RECONCILING, runtimeRunId = null)
                } else {
                    state
                }
            // A failed or cancelled run has nothing to share; only SUCCESS keeps the request
            // alive until the editor hands the file to the share sheet.
            is ExportAction.Terminal -> state.copy(
                phase = action.phase,
                runtimeRunId = null,
                shareWhenDone = state.shareWhenDone && action.phase == EditorExportPhase.SUCCESS,
            )
            is ExportAction.SetPhase -> state.copy(
                phase = action.phase,
                shareWhenDone = state.shareWhenDone &&
                    (action.phase == EditorExportPhase.RUNNING || action.phase == EditorExportPhase.SUCCESS),
            )
            is ExportAction.SetShare -> state.copy(lastShare = action.value)
            ExportAction.DismissResult -> state.copy(
                lastShare = null,
                phase = EditorExportPhase.IDLE,
                runtimeRunId = null,
                shareWhenDone = false,
            )
            ExportAction.ShareWhenDone -> state.copy(shareWhenDone = true)
            ExportAction.ShareHandled -> state.copy(shareWhenDone = false)
        }
    }
}
