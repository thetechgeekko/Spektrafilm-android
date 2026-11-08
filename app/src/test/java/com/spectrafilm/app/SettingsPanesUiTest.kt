/*
 * Spektrafilm for Android — Settings list-detail layout on the JVM. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Settings destination picks single-pane or list-detail from the window width (#293).
 * The detail bodies are stubbed: this gates the layout and routing, not About's content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsPanesUiTest {
    @get:Rule(order = 0) val hostActivity = ComposeHostActivityRule()

    @get:Rule(order = 1) val compose = createComposeRule()

    private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private val pushed = mutableListOf<SettingsDetail>()

    private fun show() {
        compose.setContent {
            MaterialTheme {
                SettingsDestination(
                    onBack = {},
                    onOpenDetail = { pushed += it },
                    detailContent = { detail, _ -> Text("body:${detail.name}") },
                ) { selected, open ->
                    Column {
                        Text("selected:${selected?.name}")
                        TextButton(onClick = { open(SettingsDetail.HOW_TO) }) { Text("open-howto") }
                        TextButton(onClick = { open(SettingsDetail.DIAGNOSTICS) }) { Text("open-diag") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun phone_isOnePaneAndPushesEachPage() {
        show()
        compose.onNodeWithText(str(R.string.editor_action_settings)).assertIsDisplayed()
        compose.onNodeWithText("selected:null").assertIsDisplayed()
        compose.onNodeWithText("body:ABOUT").assertDoesNotExist()

        compose.onNodeWithText("open-howto").performClick()
        compose.waitForIdle()
        assertEquals(listOf(SettingsDetail.HOW_TO), pushed)
        compose.onNodeWithText("body:HOW_TO").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun mediumWindow_staysOnePane() {
        show()
        compose.onNodeWithText("body:ABOUT").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun tablet_showsListAndDetailSideBySide() {
        show()
        // About is the opening page, highlighted in the list and shown beside it.
        compose.onNodeWithText(str(R.string.editor_action_settings)).assertIsDisplayed()
        compose.onNodeWithText("selected:ABOUT").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.editor_action_about)).assertIsDisplayed()
        compose.onNodeWithText("body:ABOUT").assertIsDisplayed()

        // Picking a row swaps the detail pane in place; nothing is pushed.
        compose.onNodeWithText("open-howto").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("selected:HOW_TO").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.screen_howto_title)).assertIsDisplayed()
        compose.onNodeWithText("body:HOW_TO").assertIsDisplayed()
        compose.onNodeWithText("body:ABOUT").assertDoesNotExist()

        compose.onNodeWithText("open-diag").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("body:DIAGNOSTICS").assertIsDisplayed()
        assertEquals(emptyList<SettingsDetail>(), pushed)
    }
}
