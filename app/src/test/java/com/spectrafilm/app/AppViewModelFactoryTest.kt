package com.spectrafilm.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelFactoryTest {
    private object Store : SettingsStore {
        override var theme = ThemeMode.SYSTEM
        override var dynamicColor = true
        override var fullScreenEditing = true
        override var defaultOutputColorSpace = ColorSpace.SRGB
        override var previewMaxSize = 640
        override var previewMaxSizeAuto = true
        override var draftRenderMaxPx = 384
        override var draftRenderAuto = true
        override var defaultFilmProfile = ""
        override var defaultPrintProfile = ""
        override var exportFormat = ExportFormat.PNG
        override var exportQuality = 95
        override var exportKeepGps = false
        override var exportBorder = FilmBorder.NONE
        override var exportFrame = FrameLook()
    }

    private var settingsBuilt = 0
    private val settingsVm = SettingsViewModel(Store, AutoPreviewEdges(PreviewTier.MAX, 1024, 512))
    private val factory = AppViewModelFactory(
        settingsViewModel = { settingsBuilt++; settingsVm },
        updateViewModel = { error("not under test") },
        versionsViewModel = { versionsVm },
        exportPresetViewModel = { exportPresetVm },
    )
    private val versionsVm = VersionsViewModel(
        FileVersionRepository(java.io.File(System.getProperty("java.io.tmpdir"), "versions-factory-test")),
        kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private val exportPresetVm = ExportPresetViewModel(
        FileExportPresetRepository(java.io.File(System.getProperty("java.io.tmpdir"), "export-presets-factory-test.json")),
        kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private fun <T : ViewModel> create(c: Class<T>): T = factory.create(c, MutableCreationExtras())

    @Test fun buildsTheEditorStateHolders() {
        assertTrue(create(ExportViewModel::class.java) is ExportViewModel)
        assertTrue(create(PresetViewModel::class.java) is PresetViewModel)
        assertTrue(create(EditorToolViewModel::class.java) is EditorToolViewModel)
        assertTrue(create(EditorViewerViewModel::class.java) is EditorViewerViewModel)
    }

    @Test fun settingsComeFromTheInjectedBuilder() {
        assertSame(settingsVm, create(SettingsViewModel::class.java))
        assertEquals(1, settingsBuilt)
    }

    @Test fun versionsComeFromTheInjectedBuilder() {
        assertSame(versionsVm, create(VersionsViewModel::class.java))
    }

    @Test fun exportPresetsComeFromTheInjectedBuilder() {
        assertSame(exportPresetVm, create(ExportPresetViewModel::class.java))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownViewModelIsRejected() {
        create(Unknown::class.java)
    }

    private class Unknown : ViewModel()
}
