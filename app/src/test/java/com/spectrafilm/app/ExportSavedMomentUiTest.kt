package com.spectrafilm.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
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
 * The export result no longer traps the editor (user, 2026-11-07: "Saved to gallery" blocked
 * input until Done). The hero plays, collapses into a floating toolbar, and the toolbar lets
 * every touch outside it through and goes away on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ExportSavedMomentUiTest {
    @get:Rule(order = 0) val hostActivity = ComposeHostActivityRule()

    @get:Rule(order = 1) val compose = createComposeRule()

    private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private class Calls {
        var collapsed by mutableStateOf(false)
        var dismissals = 0
        var shares = 0
        var opens = 0
        var behindTaps = 0
    }

    private fun show(startCollapsed: Boolean, fontScale: Float = 1f): Calls {
        val calls = Calls().apply { collapsed = startCollapsed }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) { MaterialTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("editor")
                        .clickable { calls.behindTaps++ },
                )
                ExportSavedMoment(
                    collapsed = calls.collapsed,
                    onCollapse = { calls.collapsed = true },
                    onDismiss = { calls.dismissals++ },
                    onShare = { calls.shares++ },
                    onOpen = { calls.opens++ },
                )
            } }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        return calls
    }

    @Test fun heroCollapsesOnItsOwn() {
        val calls = show(startCollapsed = false)
        compose.onNodeWithText(str(R.string.editor_export_saved)).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(EXPORT_SAVED_HERO_MS + 100)
        compose.waitForIdle()
        assertTrue(calls.collapsed)
        assertEquals(0, calls.dismissals)
    }

    @Test fun anyTapOnTheHeroCollapsesItWithoutReachingTheEditor() {
        val calls = show(startCollapsed = false)
        compose.onNodeWithTag("editor").performTouchInput { click(Offset(20f, 20f)) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(calls.collapsed)
        assertEquals(0, calls.behindTaps)
    }

    @Test fun toolbarLetsTheEditorKeepWorking() {
        val calls = show(startCollapsed = true)
        compose.onNodeWithTag("editor").performTouchInput { click(Offset(20f, 20f)) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(1, calls.behindTaps)
        assertEquals(0, calls.dismissals)
    }

    @Test fun toolbarGoesAwayOnItsOwn() {
        val calls = show(startCollapsed = true)
        compose.mainClock.advanceTimeBy(EXPORT_SAVED_TOOLBAR_MS + 100)
        compose.waitForIdle()
        assertEquals(1, calls.dismissals)
    }

    /** Codex on #495: a long label must never squeeze the actions out of a narrow window. */
    @Test @Config(qualifiers = "w320dp-h640dp")
    fun actionsKeepFullSizeOnANarrowWindowAtLargeText() {
        show(startCollapsed = true, fontScale = 2f)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val root = compose.onRoot().getBoundsInRoot()
        val close = compose.onNodeWithTag(EXPORT_SAVED_CLOSE_TAG).getBoundsInRoot()
        assertTrue("close button clipped: $close in $root", close.right <= root.right)
        assertTrue("close button squeezed: ${close.width}x${close.height}", close.width >= 48.dp && close.height >= 48.dp)
        for (label in listOf(R.string.editor_export_share, R.string.editor_export_open)) {
            // Text button or labelled icon button: either way it is a whole 48dp target.
            val b = compose.onNode(
                (hasText(str(label)) or hasContentDescription(str(label))) and hasClickAction(),
            ).getBoundsInRoot()
            assertTrue("${str(label)} clipped: $b in $root", b.right <= root.right && b.left >= root.left)
            assertTrue("${str(label)} squeezed: ${b.width}x${b.height}", b.width >= 48.dp && b.height >= 48.dp)
        }
    }

    @Test fun shareAndOpenActAndDismiss() {
        val calls = show(startCollapsed = true)
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasText(str(R.string.editor_export_share)) and hasClickAction()).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(1, calls.shares)
        assertEquals(1, calls.dismissals)
        compose.onNode(hasText(str(R.string.editor_export_open)) and hasClickAction()).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(1, calls.opens)
        assertEquals(2, calls.dismissals)
    }
}
