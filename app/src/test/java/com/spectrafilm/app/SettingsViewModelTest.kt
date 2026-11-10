package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {
    /** In-memory store that clamps like AppSettings does. */
    private class FakeStore : SettingsStore {
        override var theme = ThemeMode.SYSTEM
        override var dynamicColor = true
        override var fullScreenEditing = true
        override var defaultOutputColorSpace = ColorSpace.SRGB
        override var previewMaxSize = 640
        override var previewMaxSizeAuto = true
        override var draftRenderMaxPx = 384
            set(v) { field = v.coerceIn(128, 512) }
        override var draftRenderAuto = true
        override var defaultFilmProfile = ""
        override var defaultPrintProfile = ""
        override var exportFormat = ExportFormat.PNG
        override var exportQuality = 95
            set(v) { field = v.coerceIn(1, 100) }
        override var exportKeepGps = false
        override var exportBorder = FilmBorder.NONE
        override var exportFrame = FrameLook()
    }

    private val edges = AutoPreviewEdges(PreviewTier.MAX, 1024, 512)

    @Test fun initialStateMirrorsTheStore() {
        val store = FakeStore().apply { theme = ThemeMode.DARK; exportKeepGps = true; defaultFilmProfile = "kodak" }
        val s = SettingsViewModel(store, edges).uiState.value
        assertEquals(ThemeMode.DARK, s.theme)
        assertTrue(s.keepGps)
        assertEquals("kodak", s.defaultFilm)
        assertEquals(edges, s.autoEdges)
    }

    @Test fun everyActionWritesThroughAndUpdatesState() {
        val store = FakeStore()
        val vm = SettingsViewModel(store, edges)
        val actions = listOf(
            SettingsAction.SetTheme(ThemeMode.LIGHT),
            SettingsAction.SetDynamicColor(false),
            SettingsAction.SetFullScreenEditing(false),
            SettingsAction.SetOutputColorSpace(ColorSpace.entries.last()),
            SettingsAction.SetPreviewAuto(false),
            SettingsAction.SetPreviewMaxSize(896),
            SettingsAction.SetDraftAuto(false),
            SettingsAction.SetDraftMaxPx(256),
            SettingsAction.SetDefaultFilm("film-a"),
            SettingsAction.SetDefaultPrint("print-b"),
            SettingsAction.SetExportFormat(ExportFormat.JPEG),
            SettingsAction.SetExportQuality(80),
            SettingsAction.SetKeepGps(true),
        )
        actions.forEach(vm::onAction)
        val s = vm.uiState.value
        assertEquals(ThemeMode.LIGHT, s.theme); assertEquals(ThemeMode.LIGHT, store.theme)
        assertFalse(s.dynamicColor); assertFalse(store.dynamicColor)
        assertFalse(s.fullScreenEditing); assertFalse(store.fullScreenEditing)
        assertEquals(ColorSpace.entries.last(), s.outputColorSpace)
        assertFalse(s.previewAuto); assertEquals(896, s.previewMaxSize); assertEquals(896, store.previewMaxSize)
        assertFalse(s.draftAuto); assertEquals(256, s.draftMaxPx)
        assertEquals("film-a", store.defaultFilmProfile); assertEquals("print-b", s.defaultPrint)
        assertEquals(ExportFormat.JPEG, s.exportFormat); assertEquals(80, s.exportQuality)
        assertTrue(s.keepGps); assertTrue(store.exportKeepGps)
    }

    @Test fun stateShowsTheClampedValueTheStoreSaved() {
        val vm = SettingsViewModel(FakeStore(), edges)
        vm.onAction(SettingsAction.SetExportQuality(250))
        assertEquals(100, vm.uiState.value.exportQuality)
        vm.onAction(SettingsAction.SetDraftMaxPx(64))
        assertEquals(128, vm.uiState.value.draftMaxPx)
    }

    @Test fun qualityIsOnlyOfferedForJpegFormats() {
        val vm = SettingsViewModel(FakeStore(), edges)
        assertFalse(vm.uiState.value.showsQuality)
        vm.onAction(SettingsAction.SetExportFormat(ExportFormat.JPEG))
        assertTrue(vm.uiState.value.showsQuality)
        vm.onAction(SettingsAction.SetExportFormat(ExportFormat.ULTRA_HDR))
        assertTrue(vm.uiState.value.showsQuality)
    }

    @Test fun storeChangesMadeElsewhereAppearOnTheNextAction() {
        val store = FakeStore()
        val vm = SettingsViewModel(store, edges)
        store.defaultPrintProfile = "elsewhere"
        vm.onAction(SettingsAction.SetKeepGps(true))
        assertEquals("elsewhere", vm.uiState.value.defaultPrint)
    }
}
