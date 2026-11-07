/*
 * Spektrafilm for Android — the editor journey shared by the profile generator and the
 * benchmarks (#459). GPLv3.
 */
package com.spectrafilm.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val TARGET_PACKAGE = "com.spectrafilm.app"

private const val WAIT_MS = 10_000L

// English UI labels (values/strings.xml). Run the profile/benchmark device in English.
private const val WELCOME_SKIP = "Skip"
private const val WELCOME_GET_STARTED = "Get started"
private const val COACH_GOT_IT = "Got it"
private const val STAGE_FILM = "Film"
private const val TOOL_GRAIN = "Grain"
private const val EXPORT = "Export"
// Content description of the rendered film preview (editor_preview_desc_film). It replaces
// the empty-canvas description once the first preview is on screen, the same moment the
// editor's ReportDrawnWhen reports fully drawn.
private const val FILM_PREVIEW_DESC_PREFIX = "Film preview."
private const val PREVIEW_WAIT_MS = 30_000L

private fun label(text: String): BySelector = By.text(text)
private fun described(text: String): BySelector = By.desc(text)

private fun MacrobenchmarkScope.find(text: String, timeoutMs: Long = WAIT_MS): UiObject2? =
    device.wait(Until.findObject(label(text)), timeoutMs)
        ?: device.findObject(described(text))

/**
 * First-run screens (onboarding pager and the editor coach marks) are shown once per
 * install; dismiss them if present so every journey starts on the editor.
 */
fun MacrobenchmarkScope.dismissFirstRun() {
    device.findObject(label(WELCOME_SKIP))?.click()
        ?: device.findObject(label(WELCOME_GET_STARTED))?.click()
    device.wait(Until.findObject(label(COACH_GOT_IT)), 2_000L)?.click()
    device.waitForIdle()
}

/**
 * Wait for the first rendered preview, so the editor has reported fully drawn (TTFD) before
 * a measured block ends. Fails if it never appears.
 */
fun MacrobenchmarkScope.awaitFirstPreview() {
    checkNotNull(
        device.wait(Until.findObject(By.descStartsWith(FILM_PREVIEW_DESC_PREFIX)), PREVIEW_WAIT_MS),
    ) { "First preview not drawn within ${PREVIEW_WAIT_MS} ms" }
}

/** Launch to the editor with its first preview drawn and any first-run screens dismissed. */
fun MacrobenchmarkScope.startEditor() {
    pressHome()
    startActivityAndWait()
    dismissFirstRun()
    awaitFirstPreview()
}

private val SLIDER: BySelector = By.clazz("android.widget.SeekBar")

private fun MacrobenchmarkScope.slider(timeoutMs: Long): UiObject2? =
    device.wait(Until.findObject(SLIDER), timeoutMs)

/**
 * Bring a slider on screen whatever state the editor starts in. Tapping the open stage folds
 * it away (stageTapTarget), and the selected tool survives process restarts, so never tap
 * the Film stage blindly: use the panel as found if it already shows Grain or a slider.
 */
private fun MacrobenchmarkScope.openGrainSlider(): UiObject2 {
    slider(1_000L)?.let { return it }
    device.findObject(label(TOOL_GRAIN))?.let { grain ->
        grain.click()
        slider(WAIT_MS)?.let { return it }
    }
    checkNotNull(find(STAGE_FILM)) { "Film stage not found" }.click()
    device.waitForIdle()
    slider(1_000L)?.let { return it }
    checkNotNull(find(TOOL_GRAIN)) { "Grain tool not found under Film" }.click()
    return checkNotNull(slider(WAIT_MS)) { "No slider under Film > Grain" }
}

/** Open Film > Grain, drag a slider both ways, then open and close the export sheet. */
fun MacrobenchmarkScope.editJourney() {
    val slider = openGrainSlider()
    slider.setGestureMargin(device.displayWidth / 10)
    slider.swipe(Direction.RIGHT, 0.6f)
    device.waitForIdle()
    slider.swipe(Direction.LEFT, 0.6f)
    device.waitForIdle()

    checkNotNull(find(EXPORT)) { "Export button not found" }.click()
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}
