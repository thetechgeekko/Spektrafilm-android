package com.spectrafilm.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the Settings screen on the JVM (#280): each control reaches the action it
 * claims, and rows that only make sense in some states are hidden in the others.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SettingsScreenUiTest {
    @get:Rule(order = 0) val hostActivity = ComposeHostActivityRule()

    @get:Rule(order = 1) val compose = createComposeRule()

    private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private val base = SettingsUiState(
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

    private class Calls {
        val settings = mutableListOf<SettingsAction>()
        val updates = mutableListOf<UpdateAction>()
        var howTo = 0
        var onboarding = 0
        var about = 0
        var diagnostics = 0
    }

    private fun show(state: SettingsUiState = base, update: UpdateUiState = UpdateUiState()): Calls {
        val calls = Calls()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = state,
                    onAction = { calls.settings += it },
                    filmGroups = emptyList(),
                    printGroups = emptyList(),
                    onShowOnboarding = { calls.onboarding++ },
                    onOpenDiagnostics = { calls.diagnostics++ },
                    onOpenAbout = { calls.about++ },
                    onOpenHowTo = { calls.howTo++ },
                    updateUi = update,
                    onUpdateAction = { calls.updates += it },
                )
            }
        }
        compose.waitForIdle()
        return calls
    }

    private fun click(id: Int) {
        compose.onNodeWithText(str(id)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun keepGpsToggleDispatchesAction() {
        val calls = show()
        compose.onNodeWithText(str(R.string.screen_settings_keep_gps)).performScrollTo().assertIsOff()
        click(R.string.screen_settings_keep_gps)
        assertEquals(listOf<SettingsAction>(SettingsAction.SetKeepGps(true)), calls.settings)
    }

    @Test fun jpegQualityOnlyForJpegFormats() {
        show(base.copy(exportFormat = ExportFormat.JPEG))
        compose.onNodeWithText(str(R.string.screen_settings_jpeg_quality)).performScrollTo()
    }

    @Test fun jpegQualityHiddenForPng() {
        show(base.copy(exportFormat = ExportFormat.PNG))
        compose.onNodeWithText(str(R.string.screen_settings_jpeg_quality)).assertDoesNotExist()
    }

    @Test fun helpRowsOpenTheirScreens() {
        val calls = show()
        click(R.string.screen_how_to_use_app)
        click(R.string.screen_settings_show_onboarding)
        click(R.string.screen_about_title)
        click(R.string.screen_settings_diagnostics_logs)
        assertEquals(1, calls.howTo)
        assertEquals(1, calls.onboarding)
        assertEquals(1, calls.about)
        assertEquals(1, calls.diagnostics)
    }

    @Test fun checkForUpdatesDispatchesCheckNow() {
        val calls = show()
        click(R.string.screen_settings_check_updates)
        assertEquals(listOf<UpdateAction>(UpdateAction.CheckNow), calls.updates)
    }

    @Test fun checkForUpdatesDisabledWhileChecking() {
        val calls = show(update = UpdateUiState(status = UpdateStatus.CHECKING))
        compose.onNodeWithText(str(R.string.screen_settings_check_updates))
            .performScrollTo()
            .assertIsNotEnabled()
        click(R.string.screen_settings_check_updates)
        assertEquals(emptyList<UpdateAction>(), calls.updates)
    }

    @Test fun whatsNewRowOnlyWhenAnUpdateIsAvailable() {
        show(update = UpdateUiState(status = UpdateStatus.UP_TO_DATE))
        compose.onNodeWithText(str(R.string.screen_settings_update_view)).assertDoesNotExist()
    }

    @Test fun whatsNewRowReopensTheOffer() {
        val calls = show(update = UpdateUiState(status = UpdateStatus.AVAILABLE, latestTag = "v9.9.9"))
        click(R.string.screen_settings_update_view)
        assertEquals(listOf<UpdateAction>(UpdateAction.ShowOffer), calls.updates)
    }
}
