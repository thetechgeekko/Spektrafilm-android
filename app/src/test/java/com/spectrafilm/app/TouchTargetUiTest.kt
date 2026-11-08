package com.spectrafilm.app

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Touch targets on the editor's small round controls (#455, audit X1, MAD checklist #9):
 * each must be at least 48 x 48 dp to touch even where it draws smaller (the 40 dp zoom
 * buttons on the photo), so a thumb never misses a control it can see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TouchTargetUiTest {
    @get:Rule(order = 0) val hostActivity = ComposeHostActivityRule()

    @get:Rule(order = 1) val compose = createComposeRule()

    private fun assertAtLeast48dp(node: androidx.compose.ui.test.SemanticsNodeInteraction, what: String) {
        val semantics = node.fetchSemanticsNode()
        val min = with(compose.density) { 48.dp.toPx() } - 0.5f
        val bounds = semantics.touchBoundsInRoot
        assertTrue("$what touch width ${bounds.width}px < 48dp ($min px)", bounds.width >= min)
        assertTrue("$what touch height ${bounds.height}px < 48dp ($min px)", bounds.height >= min)
    }

    @Test
    fun topBarPressIconButtonIs48dpToTouch() {
        compose.setContent {
            MaterialTheme {
                PressIconButton(onClick = {}) {
                    Icon(SpectraIcons.Help, contentDescription = "help")
                }
            }
        }
        assertAtLeast48dp(compose.onNodeWithContentDescription("help"), "PressIconButton")
    }

    @Test
    fun parameterSliderIs48dpTall() {
        // On device the slider measured 44.2dp tall (#455 follow-up).
        compose.setContent {
            MaterialTheme {
                EnhancedSlider(label = "grain amount", value = 0.5f, range = 0f..1f, onValueChange = {})
            }
        }
        val node = compose.onAllNodesWithContentDescription("grain amount").onFirst().fetchSemanticsNode()
        val min = with(compose.density) { 48.dp.toPx() } - 0.5f
        val h = node.touchBoundsInRoot.height
        assertTrue("slider touch height ${h}px < 48dp ($min px)", h >= min)
    }
}
