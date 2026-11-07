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
    }

    private var settingsBuilt = 0
    private val settingsVm = SettingsViewModel(Store, AutoPreviewEdges(PreviewTier.MAX, 1024, 512))
    private val factory = AppViewModelFactory(
        settingsViewModel = { settingsBuilt++; settingsVm },
        updateViewModel = { error("not under test") },
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

    @Test(expected = IllegalArgumentException::class)
    fun unknownViewModelIsRejected() {
        create(Unknown::class.java)
    }

    private class Unknown : ViewModel()
}
