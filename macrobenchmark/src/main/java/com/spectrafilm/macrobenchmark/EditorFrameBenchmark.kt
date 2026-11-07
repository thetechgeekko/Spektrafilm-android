// Spektrafilm for Android — editor frame-timing benchmark (#459). GPLv3.
package com.spectrafilm.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jank while editing: frame durations and overruns across [editJourney] (panel open, slider
 * drag with live re-render, overflow menu). The app is already showing a preview when
 * measuring starts, so decode and first render stay out of these numbers. No startup mode:
 * COLD would kill the process setupBlock just opened.
 */
@RunWith(AndroidJUnit4::class)
class EditorFrameBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun editNoCompilation() = edit(CompilationMode.None())

    @Test
    fun editBaselineProfile() = edit(CompilationMode.Partial(BaselineProfileMode.Require))

    private var firstRun = true

    private fun edit(compilation: CompilationMode) {
        val image = SampleImage.uri()
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilation,
            startupMode = null,
            iterations = 5,
            setupBlock = {
                killProcess()
                if (firstRun) {
                    dismissFirstRun(image)
                    firstRun = false
                    killProcess()
                }
                openInEditor(image)
            },
        ) {
            editJourney()
        }
    }
}
