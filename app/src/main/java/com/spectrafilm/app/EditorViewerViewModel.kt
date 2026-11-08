/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Editor viewer modes (#272, fifth ViewModel slice): before/after compare, the histogram
 * overlay and Lightroom-style hidden chrome. These were three `remember` vars in EditorScreen;
 * they now live in one immutable [EditorViewerUiState], changed only through
 * [EditorViewerAction] by a pure [EditorViewerViewModel.reduce].
 *
 * None of the three is journalled in the session document, and each started fresh with every
 * editor composition. [EditorViewerViewModel.reset] keeps that: EditorScreen calls it once per
 * composition, so moving the state into an Activity-scoped ViewModel changes no behaviour.
 */
package com.spectrafilm.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class EditorViewerUiState(
    /** Before/after split on the preview. */
    val compare: Boolean = false,
    val histogram: Boolean = false,
    /** Single tap on the photo hid the top bar, rail and panels. */
    val chromeHidden: Boolean = false,
    /** Clipping warnings latched on from the canvas button. */
    val clipWarnings: Boolean = false,
    /** A second finger is resting on a slider being adjusted: show clipping while it stays. */
    val clipPeek: Boolean = false,
    /** The top bar's Compare is being held: the original shows until it is released. */
    val originalPeek: Boolean = false,
) {
    /** Whether the preview paints its clipped pixels right now. */
    val showClipping: Boolean get() = clipWarnings || clipPeek
}

internal sealed interface EditorViewerAction {
    data object ToggleCompare : EditorViewerAction
    data object ToggleHistogram : EditorViewerAction
    data object ToggleChrome : EditorViewerAction
    data object ToggleClipWarnings : EditorViewerAction

    /** The slider multi-touch peek started (true) or ended (false). */
    data class SetClipPeek(val active: Boolean) : EditorViewerAction

    /** Compare held down (true) or released (false): peek at the original. */
    data class SetOriginalPeek(val active: Boolean) : EditorViewerAction

    /** Back with the chrome hidden: bring the controls back. */
    data object ShowChrome : EditorViewerAction
}

internal class EditorViewerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(EditorViewerUiState())
    val uiState: StateFlow<EditorViewerUiState> = _uiState.asStateFlow()

    /** Latest state, read synchronously by effects and callbacks (see [ExportViewModel.current]). */
    val current: EditorViewerUiState get() = _uiState.value

    /**
     * Keyboard zoom requests (EditorShortcuts.kt) for the preview on screen. One-shot events,
     * not state: the zoom level itself stays local to the viewer, as it always has. Dropped,
     * not queued, when nothing is collecting (no preview yet), so a stale press never fires later.
     */
    private val _zoomRequests = MutableSharedFlow<ViewerZoom>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val zoomRequests: SharedFlow<ViewerZoom> = _zoomRequests.asSharedFlow()

    fun requestZoom(zoom: ViewerZoom) {
        _zoomRequests.tryEmit(zoom)
    }

    /** A new editor composition starts with every viewer mode off, as it always has. */
    fun reset() {
        _uiState.value = EditorViewerUiState()
    }

    fun onAction(action: EditorViewerAction) {
        _uiState.update { reduce(it, action) }
    }

    companion object {
        fun reduce(state: EditorViewerUiState, action: EditorViewerAction): EditorViewerUiState = when (action) {
            EditorViewerAction.ToggleCompare -> state.copy(compare = !state.compare)
            EditorViewerAction.ToggleHistogram -> state.copy(histogram = !state.histogram)
            EditorViewerAction.ToggleChrome -> state.copy(chromeHidden = !state.chromeHidden)
            EditorViewerAction.ShowChrome -> state.copy(chromeHidden = false)
            EditorViewerAction.ToggleClipWarnings -> state.copy(clipWarnings = !state.clipWarnings)
            is EditorViewerAction.SetClipPeek -> state.copy(clipPeek = action.active)
            is EditorViewerAction.SetOriginalPeek -> state.copy(originalPeek = action.active)
        }
    }
}
