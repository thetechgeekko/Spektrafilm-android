// Spektrafilm for Android — startup benchmarks (#459). GPLv3.
package com.spectrafilm.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start into the editor with a photo, as from a gallery "Open with". timeToInitialDisplay
 * is the first frame; timeToFullDisplay is the first rendered film preview (ReportDrawnWhen in
 * EditorScreen). Run both compilation modes: the gap between them is what the Baseline
 * Profile buys, and it should not shrink after a change.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldOpenPhotoNoCompilation() = coldOpenPhoto(CompilationMode.None())

    @Test
    fun coldOpenPhotoBaselineProfile() =
        coldOpenPhoto(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun coldOpenPhoto(compilation: CompilationMode) {
        val image = SampleImage.uri()
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilation,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
        ) {
            openInEditor(image)
        }
    }
}
