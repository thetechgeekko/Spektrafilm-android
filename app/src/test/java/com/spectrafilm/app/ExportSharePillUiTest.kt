package com.spectrafilm.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The editor's Export + Share pill (user, 2026-11-08): two halves that sit 2dp apart, each a
 * whole touch target, and both inert while an export runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ExportSharePillUiTest {
    @get:Rule(order = 0) val hostActivity = ComposeHostActivityRule()

    @get:Rule(order = 1) val compose = createComposeRule()

    private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private class Calls {
        var exporting by mutableStateOf(false)
        var exports = 0
        var shares = 0
    }

    private fun show(exporting: Boolean = false, maxWidth: Dp = Dp.Infinity): Calls {
        val calls = Calls().apply { this.exporting = exporting }
        compose.setContent {
            MaterialTheme {
                ExportSharePill(
                    exporting = calls.exporting,
                    saved = false,
                    onExport = { calls.exports++ },
                    onShare = { calls.shares++ },
                    maxWidth = maxWidth,
                )
            }
        }
        compose.waitForIdle()
        return calls
    }

    @Test fun eachHalfRunsItsOwnAction() {
        val calls = show()
        compose.onNodeWithTag(EXPORT_PILL_EXPORT_TAG).performClick()
        compose.onNodeWithTag(EXPORT_PILL_SHARE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, calls.exports)
        assertEquals(1, calls.shares)
    }

    @Test fun bothHalvesAreInertWhileExporting() {
        val calls = show(exporting = true)
        compose.onNodeWithTag(EXPORT_PILL_EXPORT_TAG).performClick()
        compose.onNodeWithTag(EXPORT_PILL_SHARE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(0, calls.exports)
        assertEquals(0, calls.shares)
        compose.onNode(hasContentDescription(str(R.string.editor_exporting))).assertIsDisplayed()
    }

    @Test fun labelledAndReachable() {
        show()
        compose.onNode(hasText(str(R.string.editor_action_export))).assertIsDisplayed()
        compose.onNode(hasContentDescription(str(R.string.editor_export_share))).assertIsDisplayed()
        for (tag in listOf(EXPORT_PILL_EXPORT_TAG, EXPORT_PILL_SHARE_TAG)) {
            val b = compose.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag too small: ${b.width}x${b.height}", b.width >= 48.dp && b.height >= 40.dp)
        }
    }

    /** The two halves read as one pill: no more than a few dp between them. */
    @Test fun halvesSitTightTogether() {
        show()
        val export = compose.onNodeWithTag(EXPORT_PILL_EXPORT_TAG).getBoundsInRoot()
        val share = compose.onNodeWithTag(EXPORT_PILL_SHARE_TAG).getBoundsInRoot()
        val gap = share.left - export.right
        assertTrue("gap between halves is $gap", gap >= 0.dp && gap <= 4.dp)
    }

    /** Codex on #535: on a 360dp bar the pill must not push the other actions over each other. */
    @Test fun dropsTheLabelWhenTheBarIsTight() {
        val calls = show(maxWidth = 112.dp)
        compose.onNode(hasText(str(R.string.editor_action_export))).assertDoesNotExist()
        val export = compose.onNodeWithTag(EXPORT_PILL_EXPORT_TAG)
        export.assertContentDescriptionEquals(str(R.string.editor_action_export))
        val b = export.getBoundsInRoot()
        assertTrue("icon-only Export is ${b.width}", b.width >= 48.dp && b.width <= 52.dp)
        export.performClick()
        compose.waitForIdle()
        assertEquals(1, calls.exports)
    }
}
