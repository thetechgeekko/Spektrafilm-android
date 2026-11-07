/*
 * Spektrafilm for Android — Macrobenchmarks (#459). GPLv3.
 *
 * Each benchmark runs with no AOT compilation and with the recorded Baseline Profile, so the
 * profile's effect is the difference between the two rows. Time to full display (TTFD) is
 * reported because the editor calls ReportDrawnWhen once its first preview is drawn.
 *
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 */
package com.spectrafilm.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() =
        startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun editNoCompilation() = edit(CompilationMode.None())

    @Test
    fun editBaselineProfile() = edit(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            // Dismiss first-run screens once so they are not part of what is measured.
            startEditor()
            killProcess()
        },
    ) {
        pressHome()
        startActivityAndWait()
        // Keep the trace open until the editor reports fully drawn, or TTFD is not reported.
        awaitFirstPreview()
    }

    private fun edit(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        // No startup mode: the editor opened in setupBlock must still be in front when the
        // measured block runs (a startup mode would have the library restart it first).
        startupMode = null,
        iterations = 5,
        // Cold-start the editor for every iteration. A warm relaunch after two journeys
        // never showed the film preview again on the test phone, and a fresh process also
        // gives every iteration the same JIT state, which is what the compilation-mode
        // comparison is about.
        setupBlock = {
            killProcess()
            startEditor()
        },
    ) {
        editJourney()
    }
}
