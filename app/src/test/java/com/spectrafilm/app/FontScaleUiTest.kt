package com.spectrafilm.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Largest font scale (#456, audit X2, MAD checklist #12): key screens rendered at 2x text
 * size on a compact phone must not CLIP any text, i.e. no text may be cut off by the box it
 * is laid out in. Ellipsized single-line labels are allowed (the full text stays in the
 * semantics TalkBack reads), clipped glyphs are not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class FontScaleUiTest {
    @get:Rule(order = 0) val hostActivity = ComposeHostActivityRule()

    @get:Rule(order = 1) val compose = createComposeRule()

    private fun showAt2x(content: @Composable () -> Unit) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    /** Every text node's layout; fails listing each one whose glyphs overflow their bounds. */
    private fun assertNoClippedText(screen: String) {
        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("$screen rendered no text at all; the check would be vacuous", nodes.isNotEmpty())
        val clipped = nodes.mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val overflowing = layouts.filter { it.hasVisualOverflow && !it.isEllipsized() }
            if (overflowing.isEmpty()) {
                null
            } else {
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                "\"$text\" (${overflowing.first().size})"
            }
        }
        assertTrue("$screen clips text at 2x font scale:\n" + clipped.joinToString("\n"), clipped.isEmpty())
    }

    private fun TextLayoutResult.isEllipsized(): Boolean =
        lineCount > 0 && isLineEllipsized(lineCount - 1)

    @Test
    fun exportSheetAt2x() {
        showAt2x {
            ExportSheet(
                options = ExportOptions(
                    format = ExportFormat.JPEG,
                    jpegQuality = 90,
                    size = ExportSize.entries.first(),
                    customLongEdge = 0,
                    customName = "",
                ),
                onOptionsChange = {},
                colorSpace = ColorSpace.SRGB,
                onColorSpaceChange = {},
                cctf = false,
                onCctfChange = {},
                keepGps = false,
                onKeepGpsChange = {},
                onDismiss = {},
                onExport = {},
            )
        }
        assertNoClippedText("Export sheet")
    }

    @Test
    fun settingsAt2x() {
        showAt2x {
            SettingsScreen(
                state = SettingsUiState(
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
                ),
                onAction = {},
                filmGroups = emptyList(),
                printGroups = emptyList(),
                onShowOnboarding = {},
                onOpenDiagnostics = {},
                onOpenAbout = {},
                onOpenHowTo = {},
                updateUi = UpdateUiState(),
                onUpdateAction = {},
            )
        }
        assertNoClippedText("Settings")
    }
}
