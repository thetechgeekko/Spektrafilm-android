package com.spectrafilm.app

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the export sheet on the JVM (#280). #257 was "nothing on the export sheet
 * is clickable": these click its real controls and check that each reaches its callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ExportSheetUiTest {
    // Registers the host ComponentActivity with Robolectric's package manager; the
    // ui-test-manifest entry is not merged into the unit-test manifest under testImplementation.
    @get:Rule(order = 0) val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication()
            val host = ComponentName(app, ComponentActivity::class.java)
            val pm = shadowOf(app.packageManager)
            pm.addActivityIfNotPresent(host)
            pm.addIntentFilterForActivity(
                host,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
    }

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

    @Test fun cancelButtonDismisses() {
        val calls = show()
        compose.onNodeWithText(str(R.string.tool_cancel)).performScrollTo().performClick()
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
