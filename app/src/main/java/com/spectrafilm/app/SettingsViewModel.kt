/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Settings screen state holder (#272, first ViewModel slice). The screen used to keep
 * thirteen `remember` mirrors of AppSettings and write each one back by hand; those
 * mirrors were lost on rotation-free recreation paths and could drift from the store
 * (a clamped write showed the unclamped value). Now one [SettingsUiState] is read
 * from the store, every edit is a [SettingsAction] written through and re-read, and
 * the UI collects a StateFlow. Theme and dynamic colour are read from the same state
 * by the activity, so the screen no longer needs callbacks to re-theme the app.
 */
package com.spectrafilm.app

import androidx.lifecycle.ViewModel
import com.spectrafilm.engine.ColorSpace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The settings the Settings screen edits. [AppSettings] is the real store. */
interface SettingsStore {
    var theme: ThemeMode
    var dynamicColor: Boolean
    /** Hide the status and navigation bars while editing (swipe from an edge shows them). */
    var fullScreenEditing: Boolean
    var defaultOutputColorSpace: ColorSpace
    var previewMaxSize: Int
    var previewMaxSizeAuto: Boolean
    var draftRenderMaxPx: Int
    var draftRenderAuto: Boolean
    var defaultFilmProfile: String
    var defaultPrintProfile: String
    var exportFormat: ExportFormat
    var exportQuality: Int
    var exportKeepGps: Boolean
    /** Film border the export sheet starts with (the last one used). */
    var exportBorder: FilmBorder
    /** Width, colour and aspect of that border (the Frame tab's last settings). */
    var exportFrame: FrameLook
}

internal data class SettingsUiState(
    val theme: ThemeMode,
    val dynamicColor: Boolean,
    val fullScreenEditing: Boolean,
    val outputColorSpace: ColorSpace,
    val previewMaxSize: Int,
    val previewAuto: Boolean,
    val draftMaxPx: Int,
    val draftAuto: Boolean,
    /** What Auto would pick on this device; shown in the toggle notes. */
    val autoEdges: AutoPreviewEdges,
    val defaultFilm: String,
    val defaultPrint: String,
    val exportFormat: ExportFormat,
    val exportQuality: Int,
    val keepGps: Boolean,
    val exportBorder: FilmBorder,
) {
    /** JPEG quality only means something for the JPEG-based formats. */
    val showsQuality: Boolean
        get() = exportFormat == ExportFormat.JPEG || exportFormat == ExportFormat.ULTRA_HDR
}

internal sealed interface SettingsAction {
    data class SetTheme(val value: ThemeMode) : SettingsAction
    data class SetDynamicColor(val value: Boolean) : SettingsAction
    data class SetFullScreenEditing(val value: Boolean) : SettingsAction
    data class SetOutputColorSpace(val value: ColorSpace) : SettingsAction
    data class SetPreviewAuto(val value: Boolean) : SettingsAction
    data class SetPreviewMaxSize(val value: Int) : SettingsAction
    data class SetDraftAuto(val value: Boolean) : SettingsAction
    data class SetDraftMaxPx(val value: Int) : SettingsAction
    data class SetDefaultFilm(val value: String) : SettingsAction
    data class SetDefaultPrint(val value: String) : SettingsAction
    data class SetExportFormat(val value: ExportFormat) : SettingsAction
    data class SetExportQuality(val value: Int) : SettingsAction
    data class SetKeepGps(val value: Boolean) : SettingsAction
    data class SetExportBorder(val value: FilmBorder) : SettingsAction
    data class SetExportFrame(val value: FrameLook) : SettingsAction
}

internal class SettingsViewModel(
    private val store: SettingsStore,
    private val autoEdges: AutoPreviewEdges,
) : ViewModel() {
    private val _uiState = MutableStateFlow(read())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetTheme -> store.theme = action.value
            is SettingsAction.SetDynamicColor -> store.dynamicColor = action.value
            is SettingsAction.SetFullScreenEditing -> store.fullScreenEditing = action.value
            is SettingsAction.SetOutputColorSpace -> store.defaultOutputColorSpace = action.value
            is SettingsAction.SetPreviewAuto -> store.previewMaxSizeAuto = action.value
            is SettingsAction.SetPreviewMaxSize -> store.previewMaxSize = action.value
            is SettingsAction.SetDraftAuto -> store.draftRenderAuto = action.value
            is SettingsAction.SetDraftMaxPx -> store.draftRenderMaxPx = action.value
            is SettingsAction.SetDefaultFilm -> store.defaultFilmProfile = action.value
            is SettingsAction.SetDefaultPrint -> store.defaultPrintProfile = action.value
            is SettingsAction.SetExportFormat -> store.exportFormat = action.value
            is SettingsAction.SetExportQuality -> store.exportQuality = action.value
            is SettingsAction.SetKeepGps -> store.exportKeepGps = action.value
            is SettingsAction.SetExportBorder -> store.exportBorder = action.value
            is SettingsAction.SetExportFrame -> store.exportFrame = action.value
        }
        // Re-read rather than echo the action: the store clamps (quality 1..100, draft
        // 128..512), and the UI must show what was actually saved.
        _uiState.value = read()
    }

    private fun read() = SettingsUiState(
        theme = store.theme,
        dynamicColor = store.dynamicColor,
        fullScreenEditing = store.fullScreenEditing,
        outputColorSpace = store.defaultOutputColorSpace,
        previewMaxSize = store.previewMaxSize,
        previewAuto = store.previewMaxSizeAuto,
        draftMaxPx = store.draftRenderMaxPx,
        draftAuto = store.draftRenderAuto,
        autoEdges = autoEdges,
        defaultFilm = store.defaultFilmProfile,
        defaultPrint = store.defaultPrintProfile,
        exportFormat = store.exportFormat,
        exportQuality = store.exportQuality,
        keepGps = store.exportKeepGps,
        exportBorder = store.exportBorder,
    )
}
