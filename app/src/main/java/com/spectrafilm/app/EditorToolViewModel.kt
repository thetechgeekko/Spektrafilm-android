/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Editor on-photo tool state (#272, fourth ViewModel slice): which overlay owns the preview
 * (crop, mask geometry, eyedropper and its mode) and the selected local-adjustment mask. These
 * were six `remember` vars in EditorScreen; they now live in one immutable [EditorToolUiState],
 * changed only through [EditorToolAction] by a pure [EditorToolViewModel.reduce].
 *
 * The flags are kept as they were rather than folded into one enum: [EditorToolUiState.overlay]
 * reproduces the old capture-time priority (crop > mask > WB sample > luminance > colour), so
 * the journalled [EditorOverlayTool] is unchanged for every combination.
 *
 * Same restoration contract as [ExportViewModel]: each EditorScreen composition seeds this
 * ViewModel once from its session checkpoint (docs/EDITOR_SESSION.md).
 */
package com.spectrafilm.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class EditorToolUiState(
    val cropOpen: Boolean = false,
    /** Draw-on-the-preview mask geometry editor for [maskIndex]. */
    val maskGeometryOpen: Boolean = false,
    /** Eyedropper: WB gray point ([sampleWb]) or a colour/luminance mask target. */
    val sampleOpen: Boolean = false,
    val sampleLuminance: Boolean = false,
    val sampleWb: Boolean = false,
    val maskIndex: Int = 0,
) {
    /** True while an overlay owns the preview's gestures. */
    val anyOverlayOpen: Boolean get() = cropOpen || maskGeometryOpen || sampleOpen

    /** The overlay to journal, in the priority the editor has always used. */
    val overlay: EditorOverlayTool
        get() = when {
            cropOpen -> EditorOverlayTool.CROP
            maskGeometryOpen -> EditorOverlayTool.MASK_GEOMETRY
            sampleOpen && sampleWb -> EditorOverlayTool.WHITE_BALANCE_SAMPLE
            sampleOpen && sampleLuminance -> EditorOverlayTool.MASK_SAMPLE_LUMINANCE
            sampleOpen -> EditorOverlayTool.MASK_SAMPLE_COLOR
            else -> EditorOverlayTool.NONE
        }

    companion object {
        /** [overlay] must already be [restorableEditorOverlay]-filtered by the caller. */
        fun from(overlay: EditorOverlayTool, maskIndex: Int) = EditorToolUiState(
            cropOpen = overlay == EditorOverlayTool.CROP,
            maskGeometryOpen = overlay == EditorOverlayTool.MASK_GEOMETRY,
            sampleOpen = overlay == EditorOverlayTool.MASK_SAMPLE_COLOR ||
                overlay == EditorOverlayTool.MASK_SAMPLE_LUMINANCE ||
                overlay == EditorOverlayTool.WHITE_BALANCE_SAMPLE,
            sampleLuminance = overlay == EditorOverlayTool.MASK_SAMPLE_LUMINANCE,
            sampleWb = overlay == EditorOverlayTool.WHITE_BALANCE_SAMPLE,
            maskIndex = maskIndex,
        )
    }
}

internal sealed interface EditorToolAction {
    data object OpenCrop : EditorToolAction
    data object CloseCrop : EditorToolAction
    data class EditMaskOnPhoto(val index: Int) : EditorToolAction
    data object CloseMaskGeometry : EditorToolAction

    /** Gray-point white-balance eyedropper. */
    data object PickNeutral : EditorToolAction

    /** Eyedropper for mask [index]'s luminance ([luminance]) or colour target. */
    data class SampleMask(val index: Int, val luminance: Boolean) : EditorToolAction

    /** Eyedropper dismissed, or a WB sample taken. */
    data object CloseSample : EditorToolAction

    /** A mask sample was applied: close the eyedropper, keep its mode. */
    data object MaskSampleApplied : EditorToolAction

    data class SelectMask(val index: Int) : EditorToolAction

    /** Keep the selection valid for a mask list of [size]. */
    data class ClampMask(val size: Int) : EditorToolAction

    /** A different source was loaded: every overlay closes and mask selection restarts. */
    data object ResetForSourceChange : EditorToolAction
}

internal class EditorToolViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(EditorToolUiState())
    val uiState: StateFlow<EditorToolUiState> = _uiState.asStateFlow()

    /** Latest state, read synchronously by effects and callbacks (see [ExportViewModel.current]). */
    val current: EditorToolUiState get() = _uiState.value

    fun restore(overlay: EditorOverlayTool, maskIndex: Int) {
        _uiState.value = EditorToolUiState.from(overlay, maskIndex)
    }

    fun onAction(action: EditorToolAction) {
        _uiState.update { reduce(it, action) }
    }

    companion object {
        fun reduce(state: EditorToolUiState, action: EditorToolAction): EditorToolUiState = when (action) {
            EditorToolAction.OpenCrop -> state.copy(cropOpen = true)
            EditorToolAction.CloseCrop -> state.copy(cropOpen = false)
            is EditorToolAction.EditMaskOnPhoto ->
                state.copy(maskIndex = action.index, maskGeometryOpen = true)
            EditorToolAction.CloseMaskGeometry -> state.copy(maskGeometryOpen = false)
            EditorToolAction.PickNeutral -> state.copy(sampleWb = true, sampleOpen = true)
            is EditorToolAction.SampleMask -> state.copy(
                maskIndex = action.index,
                sampleLuminance = action.luminance,
                sampleOpen = true,
            )
            EditorToolAction.CloseSample -> state.copy(sampleOpen = false, sampleWb = false)
            EditorToolAction.MaskSampleApplied -> state.copy(sampleOpen = false)
            is EditorToolAction.SelectMask -> state.copy(maskIndex = action.index)
            is EditorToolAction.ClampMask ->
                state.copy(maskIndex = clampSelectedMaskIndex(state.maskIndex, action.size))
            EditorToolAction.ResetForSourceChange -> state.copy(
                cropOpen = false,
                maskGeometryOpen = false,
                sampleOpen = false,
                sampleWb = false,
                maskIndex = 0,
            )
        }
    }
}
