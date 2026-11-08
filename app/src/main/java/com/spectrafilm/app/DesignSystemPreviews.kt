/*
 * Spektrafilm for Android — previews of the shared editor building blocks. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * MAD audit fix 5: the app had no @Preview at all. These cover the pieces the redesign
 * shares across screens (stage chrome, sliders, section cards), in light and dark, at large
 * font scale and on phone/tablet sizes. The screenshotTest suite renders the same previews
 * and compares them with committed reference images, so a visual regression fails a build.
 *
 * Each preview uses the static palette (dynamicColor = false) so it renders the same on
 * every machine; wallpaper colour would make reference images device-dependent.
 */
package com.spectrafilm.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp

/** Wraps a preview in the app theme with the static palette, on a surface. */
@Composable
internal fun PreviewSurface(dark: Boolean, content: @Composable () -> Unit) {
    SpektraTheme(dark = dark, dynamicColor = false) {
        Surface { content() }
    }
}

private val previewDirty = setOf(Category.GRAIN, Category.HALATION)
private val previewLooksTools = listOf(Category.SIMULATION, Category.PAPER, Category.PRESETS, Category.SOURCE)

@Composable
internal fun StageRowSample(dark: Boolean) = PreviewSurface(dark) {
    Column {
        SectionTabs(sections = stagesOf(EditorMode.EDIT), active = Stage.FILM, dirty = previewDirty, onSelect = {})
        StageRow(active = Category.GRAIN, dirty = previewDirty, onOpen = {}, onCrop = {})
    }
}

@Composable
internal fun ToolRowSample(dark: Boolean) = PreviewSurface(dark) {
    ToolRow(tools = previewLooksTools, active = Category.SIMULATION, dirty = previewDirty, onSelect = {})
}

@Composable
internal fun StageRailSample(dark: Boolean) = PreviewSurface(dark) {
    Row {
        StageRail(
            active = Category.GRAIN,
            dirty = previewDirty,
            onOpen = {},
            exportVisible = true,
            exporting = false,
            onExport = {},
            onCrop = {},
            onShare = {},
        )
        ToolList(
            tools = previewLooksTools,
            active = Category.SIMULATION,
            dirty = previewDirty,
            onSelect = {},
            modifier = Modifier.width(240.dp),
        )
    }
}

@Composable
internal fun SlidersSample(dark: Boolean) = PreviewSurface(dark) {
    Column(Modifier.padding(16.dp)) {
        EnhancedSlider(
            label = "Exposure",
            value = 0.7f,
            range = -3f..3f,
            onValueChange = {},
            step = 0.1f,
            decimals = 1,
            default = 0f,
        )
        EnhancedSlider(
            label = "Grain size",
            value = 1f,
            range = 0f..4f,
            onValueChange = {},
            default = 1f,
            tooltip = "Larger grains read as faster film.",
        )
        IntSlider(label = "JPEG quality", value = 95, range = 1..100, onValueChange = {})
    }
}

@Composable
internal fun SectionCardSample(dark: Boolean) = PreviewSurface(dark) {
    Column(Modifier.padding(16.dp)) {
        SectionCard(
            title = "Halation",
            expanded = true,
            onExpandedChange = {},
            enabledSwitch = true,
            onEnabledChange = {},
        ) {
            IntSlider(label = "Strength", value = 40, range = 0..100, onValueChange = {}, default = 0)
        }
        AdvancedToggle(advanced = false, onToggle = {})
    }
}

@Composable
internal fun ExportButtonSample(dark: Boolean) = PreviewSurface(dark) {
    Row(Modifier.padding(16.dp)) {
        ExportSharePill(exporting = false, saved = false, onExport = {}, onShare = {})
        ExportSharePill(exporting = true, saved = false, onExport = {}, onShare = {})
    }
}

// --- Studio previews: light and dark, large font scale, screen sizes ---

@PreviewLightDark
@Composable
private fun StageRowPreview() = StageRowSample(dark = isSystemInDarkTheme())

@PreviewFontScale
@Composable
private fun StageRowFontScalePreview() = StageRowSample(dark = false)

@PreviewLightDark
@Composable
private fun ToolRowPreview() = ToolRowSample(dark = isSystemInDarkTheme())

@PreviewScreenSizes
@Composable
private fun StageRailPreview() = StageRailSample(dark = false)

@PreviewLightDark
@Composable
private fun SlidersPreview() = SlidersSample(dark = isSystemInDarkTheme())

@PreviewFontScale
@Composable
private fun SlidersFontScalePreview() = SlidersSample(dark = false)

@PreviewLightDark
@Composable
private fun SectionCardPreview() = SectionCardSample(dark = isSystemInDarkTheme())

@Preview
@Composable
private fun ExportButtonPreview() = ExportButtonSample(dark = false)
