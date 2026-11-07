/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Editor preset state holder (#272, third ViewModel slice). The preset panel's selection and
 * name, the Lightroom-style amount blend anchors and the copy/paste settings clipboard were
 * six `remember` vars in EditorScreen. They now live in one immutable [PresetUiState],
 * changed only through [PresetAction] by a pure [PresetViewModel.reduce].
 *
 * Same restoration contract as [ExportViewModel]: each EditorScreen composition seeds this
 * ViewModel once from its session checkpoint, so the session document stays the only
 * restoration authority (docs/EDITOR_SESSION.md).
 */
package com.spectrafilm.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class PresetUiState(
    /** The name typed in the save field. */
    val name: String = "",
    /** The saved preset selected in the list ("" = none). */
    val selected: String = "",
    /**
     * Preset "amount": the look just before a preset was applied ([baseJson]) and the full
     * applied preset ([fullJson]), as flat-schema JSON, so the slider can cross-fade between
     * them. Both null = no preset applied since load (slider disabled).
     */
    val baseJson: String? = null,
    val fullJson: String? = null,
    /** The live mix, 0..1. */
    val amount: Float = 1f,
    /** Copy/paste settings clipboard: a full params snapshot, or null. */
    val clipboardJson: String? = null,
) {
    val amountEnabled: Boolean get() = fullJson != null
    val canPaste: Boolean get() = clipboardJson != null

    /** What the session checkpoint journals; free text is bounded the way the schema requires. */
    fun toSessionState() = EditorPresetState(
        baseJson = baseJson,
        fullJson = fullJson,
        amount = amount,
        clipboardJson = clipboardJson,
        selectedPreset = AtomicJsonStore.truncateUtf16Safely(selected, 96),
        presetName = AtomicJsonStore.truncateUtf16Safely(name, 96),
    )

    companion object {
        fun from(restored: EditorPresetState?) = if (restored == null) {
            PresetUiState()
        } else {
            PresetUiState(
                name = restored.presetName,
                selected = restored.selectedPreset,
                baseJson = restored.baseJson,
                fullJson = restored.fullJson,
                amount = restored.amount,
                clipboardJson = restored.clipboardJson,
            )
        }
    }
}

internal sealed interface PresetAction {
    data class SetName(val value: String) : PresetAction
    data class Select(val value: String) : PresetAction

    /** A preset was applied: anchor the amount slider between [base] and [full], at 100%. */
    data class Applied(val base: String, val full: String) : PresetAction

    data class SetAmount(val value: Float) : PresetAction

    /** The look was replaced wholesale (paste, reset, new source): the blend anchors are stale. */
    data object ClearAmount : PresetAction

    data class SetClipboard(val value: String?) : PresetAction
}

internal class PresetViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PresetUiState())
    val uiState: StateFlow<PresetUiState> = _uiState.asStateFlow()

    /** Latest state, read synchronously by effects and callbacks (see [ExportViewModel.current]). */
    val current: PresetUiState get() = _uiState.value

    /** Start a new editor composition from its session checkpoint (null = fresh session). */
    fun restore(restored: EditorPresetState?) {
        _uiState.value = PresetUiState.from(restored)
    }

    fun onAction(action: PresetAction) {
        _uiState.update { reduce(it, action) }
    }

    companion object {
        fun reduce(state: PresetUiState, action: PresetAction): PresetUiState = when (action) {
            is PresetAction.SetName -> state.copy(name = action.value)
            is PresetAction.Select -> state.copy(selected = action.value)
            is PresetAction.Applied ->
                state.copy(baseJson = action.base, fullJson = action.full, amount = 1f)
            is PresetAction.SetAmount -> state.copy(amount = action.value)
            PresetAction.ClearAmount -> state.copy(baseJson = null, fullJson = null, amount = 1f)
            is PresetAction.SetClipboard -> state.copy(clipboardJson = action.value)
        }
    }
}
