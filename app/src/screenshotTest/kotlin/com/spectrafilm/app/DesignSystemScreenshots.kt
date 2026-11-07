/*
 * Spektrafilm for Android — screenshot tests of the shared UI. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * MAD audit fix 5. Each @PreviewTest renders a sample from DesignSystemPreviews.kt on the host
 * (layoutlib) and compares it with the reference image under
 * src/screenshotTestDefaultDebug/reference. After an intended visual change, regenerate with
 * ./gradlew :app:updateScreenshotTestDefaultDebugTestSuite and review the new images.
 */
package com.spectrafilm.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@PreviewLightDark
@Composable
fun StageRowScreenshot() = StageRowSample(dark = androidx.compose.foundation.isSystemInDarkTheme())

@PreviewTest
@PreviewFontScale
@Composable
fun StageRowFontScaleScreenshot() = StageRowSample(dark = false)

@PreviewTest
@PreviewLightDark
@Composable
fun ToolRowScreenshot() = ToolRowSample(dark = androidx.compose.foundation.isSystemInDarkTheme())

@PreviewTest
@PreviewScreenSizes
@Composable
fun StageRailScreenshot() = StageRailSample(dark = false)

@PreviewTest
@PreviewLightDark
@Composable
fun SlidersScreenshot() = SlidersSample(dark = androidx.compose.foundation.isSystemInDarkTheme())

@PreviewTest
@PreviewFontScale
@Composable
fun SlidersFontScaleScreenshot() = SlidersSample(dark = false)

@PreviewTest
@PreviewLightDark
@Composable
fun SectionCardScreenshot() = SectionCardSample(dark = androidx.compose.foundation.isSystemInDarkTheme())

@PreviewTest
@Preview
@Composable
fun ExportButtonScreenshot() = ExportButtonSample(dark = false)
