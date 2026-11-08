/*
 * Spektrafilm for Android -- the key screens at 2x font scale, for previews and screenshot
 * tests (#456). GPLv3. Film modeling powered by spektrafilm.
 *
 * Robolectric cannot tell real clipping from its own text metrics (it flagged 35 labels where
 * the phone showed 3), so large text is gated the way it is seen: layoutlib renders these with
 * real fonts at 2x, the reference images are reviewed by eye, and any later change to them
 * fails the screenshot suite.
 */
package com.spectrafilm.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import com.spectrafilm.engine.ColorSpace

private val largeTextOptions = ExportOptions(
    ExportFormat.JPEG, 90, ExportSize.FULL, 0, "{name}_{film}",
    sharpen = OutputSharpen.STANDARD,
)

/** The Export sheet's body as the user sees it, without the modal window. */
@Composable
internal fun ExportSheetSample(dark: Boolean) = PreviewSurface(dark) {
    ExportSheet(
        options = largeTextOptions,
        onOptionsChange = {},
        colorSpace = ColorSpace.SRGB,
        onColorSpaceChange = {},
        cctf = true,
        onCctfChange = {},
        keepGps = false,
        onKeepGpsChange = {},
        onDismiss = {},
        onExport = {},
        presetNames = listOf("Instagram", "Print"),
        selectedPreset = "Instagram",
        onBatchExport = {},
        onHalfFramePair = {},
        inSheet = false,
    )
}

private val largeTextSettings = SettingsUiState(
    theme = ThemeMode.SYSTEM,
    dynamicColor = false,
    fullScreenEditing = true,
    outputColorSpace = ColorSpace.SRGB,
    previewMaxSize = 640,
    previewAuto = true,
    draftMaxPx = 256,
    draftAuto = true,
    autoEdges = AutoPreviewEdges(PreviewTier.BASELINE, previewEdge = 640, draftEdge = 256),
    defaultFilm = "",
    defaultPrint = "",
    exportFormat = ExportFormat.JPEG,
    exportQuality = 90,
    keepGps = false,
    exportBorder = FilmBorder.NONE,
)

/**
 * The Settings list with its default values. Screenshot renders stop at about 3000dp, which
 * at 2x text is less than the whole list, so [scrolledToEnd] renders its bottom instead: the
 * initial scroll is clamped to the list's end on the first measure.
 */
@Composable
internal fun SettingsSample(dark: Boolean, scrolledToEnd: Boolean = false) = PreviewSurface(dark) {
    SettingsScreen(
        state = largeTextSettings,
        onAction = {},
        filmGroups = emptyList(),
        printGroups = emptyList(),
        onShowOnboarding = {},
        scrollState = rememberScrollState(if (scrolledToEnd) Int.MAX_VALUE else 0),
    )
}
