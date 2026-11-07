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
private const val EXPORT = "Export"

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

/** Cold start ends when the editor reports fully drawn (first rendered preview). */
fun MacrobenchmarkScope.startEditor() {
    pressHome()
    startActivityAndWait()
    dismissFirstRun()
}

/** Open the Film stage, open its first tool, drag a slider, then open and close export. */
fun MacrobenchmarkScope.editJourney() {
    find(STAGE_FILM)?.click()
    device.waitForIdle()

    device.wait(Until.findObject(By.clazz("android.widget.SeekBar")), WAIT_MS)?.let { slider ->
        slider.setGestureMargin(device.displayWidth / 10)
        slider.swipe(Direction.RIGHT, 0.6f)
        device.waitForIdle()
        slider.swipe(Direction.LEFT, 0.6f)
        device.waitForIdle()
    }

    find(EXPORT)?.click()
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}
