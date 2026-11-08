package com.spectrafilm.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the export sheet on the JVM (#280). #257 was "nothing on the export sheet
 * is clickable": these click its real controls and check that each reaches its callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ExportSheetUiTest {
    @get:Rule(order = 0) val hostActivity = ComposeHostActivityRule()

    @get:Rule(order = 1) val compose = createComposeRule()

    private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    // The sheet title and the button share the "Export" text; the button is the clickable one.
    private fun exportButton() = hasText(str(R.string.tool_export_action)) and hasClickAction()

    private val jpeg = ExportOptions(
        format = ExportFormat.JPEG,
        jpegQuality = 90,
        size = ExportSize.entries.first(),
        customLongEdge = 0,
        customName = "",
    )

    private class Calls {
        var exports = 0
        var dismissals = 0
        val gps = mutableListOf<Boolean>()
    }

    private fun show(options: ExportOptions = jpeg, keepGps: Boolean = false): Calls {
        val calls = Calls()
        compose.setContent {
            MaterialTheme {
                ExportSheet(
                    options = options,
                    onOptionsChange = {},
                    colorSpace = ColorSpace.SRGB,
                    onColorSpaceChange = {},
                    cctf = false,
                    onCctfChange = {},
                    keepGps = keepGps,
                    onKeepGpsChange = { calls.gps += it },
                    onDismiss = { calls.dismissals++ },
                    onExport = { calls.exports++ },
                )
            }
        }
        compose.waitForIdle()
        return calls
    }

    @Test fun exportButtonExports() {
        val calls = show()
        compose.onNode(exportButton()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, calls.exports)
        assertEquals(0, calls.dismissals)
    }

    // No Cancel button any more: the sheet closes as an M3 modal sheet does. Its drag
    // handle carries the accessibility Dismiss action, which is also what TalkBack offers.
    @Test fun sheetDismissActionDismissesWithoutExporting() {
        val calls = show()
        compose.onNodeWithText(str(R.string.tool_cancel)).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .onFirst()
            .performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        assertEquals(1, calls.dismissals)
        assertEquals(0, calls.exports)
    }

    @Test fun gpsRowToggles() {
        val calls = show(keepGps = false)
        val row = compose.onNodeWithText(str(R.string.tool_export_include_gps), useUnmergedTree = false)
        row.performScrollTo().assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), calls.gps)
    }

    @Test fun sceneLinearHidesGpsAndColour() {
        show(options = jpeg.copy(format = ExportFormat.SCENE_LINEAR_TIFF))
        compose.onNodeWithText(str(R.string.tool_export_include_gps)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.tool_export_color_space)).assertDoesNotExist()
        compose.onNode(exportButton()).performScrollTo()
    }
}
