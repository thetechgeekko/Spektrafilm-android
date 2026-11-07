// Spektrafilm for Android — macrobenchmark user journeys (#459). GPLv3.
package com.spectrafilm.macrobenchmark

import android.content.Intent
import android.graphics.Point
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.spectrafilm.app"

// UI anchors are the app's English strings (values/strings_editor.xml): run on a device set to
// English. The preview description is set only once a rendered preview is on screen
// (EditorPreviewRegion), the same moment ReportDrawnWhen reports the editor fully drawn.
private val PREVIEW_SHOWN: BySelector = By.descStartsWith("Film preview.")
// Compact layouts show Export as a labelled button; expanded layouts (side pane) as an
// icon-only FAB whose label is its content description. Either means "in the editor".
private const val EXPORT_LABEL = "Export"
// First-run overlays (AppSettings.seenOnboarding / seenEditorCoach): WelcomeFlow, then the
// editor coach marks. Both persist once dismissed, so only a fresh install shows them.
private const val WELCOME_SKIP_LABEL = "Skip"
private const val COACH_DISMISS_LABEL = "Got it"
private const val GRAIN_LABEL = "Grain"
private const val MORE_LABEL = "More options"

private const val RENDER_TIMEOUT_MS = 60_000L
private const val UI_TIMEOUT_MS = 5_000L

/** Gallery "Open with" into the editor: the cold-start path a user takes most. */
internal fun viewIntent(image: Uri): Intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(image, "image/jpeg")
    .setClassName(TARGET_PACKAGE, "$TARGET_PACKAGE.MainActivity")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/** Launch with [image] and wait for the first rendered film preview. */
internal fun MacrobenchmarkScope.openInEditor(image: Uri) {
    startActivityAndWait(viewIntent(image))
    awaitPreview()
}

/**
 * Unmeasured: open [image] once and dismiss the welcome flow and editor coach marks, so later
 * measured launches start straight in an interactive editor. Safe to call when neither shows.
 */
internal fun MacrobenchmarkScope.dismissFirstRun(image: Uri) {
    startActivityAndWait(viewIntent(image))
    device.wait(Until.findObject(By.text(WELCOME_SKIP_LABEL)), UI_TIMEOUT_MS)?.click()
    device.wait(Until.hasObject(PREVIEW_SHOWN), RENDER_TIMEOUT_MS)
    device.wait(Until.findObject(By.text(COACH_DISMISS_LABEL)), UI_TIMEOUT_MS)?.click()
    device.waitForIdle()
    check(!device.hasObject(By.text(COACH_DISMISS_LABEL))) { "editor coach marks still showing" }
    check(inEditor()) { "editor not reached after first-run setup" }
    awaitPreview()
}

private fun MacrobenchmarkScope.inEditor(): Boolean =
    device.hasObject(By.text(EXPORT_LABEL)) || device.hasObject(By.desc(EXPORT_LABEL))

internal fun MacrobenchmarkScope.awaitPreview() {
    check(device.wait(Until.hasObject(PREVIEW_SHOWN), RENDER_TIMEOUT_MS)) {
        "no film preview within ${RENDER_TIMEOUT_MS} ms"
    }
}

/**
 * The interactive loop the profile and the frame benchmark both cover: open a tool panel,
 * drag one of its sliders both ways (each step re-renders the preview), open the overflow
 * menu and close it again. Never presses Export: on this app that writes to the gallery.
 */
internal fun MacrobenchmarkScope.editJourney() {
    check(inEditor()) { "not in the editor" }
    findOnRail(GRAIN_LABEL).click()
    val slider = device.wait(
        Until.findObject(By.clazz("android.widget.SeekBar")),
        UI_TIMEOUT_MS,
    ) ?: error("no slider in the $GRAIN_LABEL panel")
    dragAcross(slider)
    device.waitForIdle()
    awaitPreview()

    device.wait(Until.findObject(By.desc(MORE_LABEL)), UI_TIMEOUT_MS)?.let { more ->
        more.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}

/** The category rail scrolls; bring [label] into view before tapping it. */
private fun MacrobenchmarkScope.findOnRail(label: String): UiObject2 {
    device.wait(Until.findObject(By.text(label)), UI_TIMEOUT_MS)?.let { return it }
    val rail = device.findObjects(By.scrollable(true)).firstOrNull {
        it.visibleBounds.width() > it.visibleBounds.height()
    } ?: error("no scrollable category rail")
    repeat(6) {
        rail.scroll(Direction.RIGHT, 0.8f)
        device.findObject(By.text(label))?.let { return it }
    }
    error("category '$label' not found on the rail")
}

private fun MacrobenchmarkScope.dragAcross(slider: UiObject2) {
    val bounds = slider.visibleBounds
    val y = bounds.centerY()
    val left = Point(bounds.left + bounds.width() / 5, y)
    val right = Point(bounds.right - bounds.width() / 5, y)
    // ~1 s each way at the default step rate: long enough to span several preview renders.
    device.drag(bounds.centerX(), y, right.x, right.y, 50)
    device.drag(right.x, right.y, left.x, left.y, 100)
}
