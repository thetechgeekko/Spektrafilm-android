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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class EditorViewerUiState(
    /** Before/after split on the preview. */
    val compare: Boolean = false,
    val histogram: Boolean = false,
    /** Single tap on the photo hid the top bar, rail and panels. */
    val chromeHidden: Boolean = false,
)

internal sealed interface EditorViewerAction {
    data object ToggleCompare : EditorViewerAction
    data object ToggleHistogram : EditorViewerAction
    data object ToggleChrome : EditorViewerAction

    /** Back with the chrome hidden: bring the controls back. */
    data object ShowChrome : EditorViewerAction
}

internal class EditorViewerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(EditorViewerUiState())
    val uiState: StateFlow<EditorViewerUiState> = _uiState.asStateFlow()

    /** Latest state, read synchronously by effects and callbacks (see [ExportViewModel.current]). */
    val current: EditorViewerUiState get() = _uiState.value

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
        }
    }
}
