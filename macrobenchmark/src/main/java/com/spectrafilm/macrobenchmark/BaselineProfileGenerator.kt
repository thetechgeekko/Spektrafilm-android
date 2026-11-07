// Spektrafilm for Android — Baseline Profile generator (#459). GPLv3.
package com.spectrafilm.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods the app runs on its critical journeys: cold start into the
 * editor with a photo, then [editJourney]. Needs API 33+ (or a rooted device). The output,
 * `BaselineProfileGenerator_generate-baseline-prof.txt` under
 * macrobenchmark/build/outputs/connected_android_test_additional_output/, replaces the
 * rule-based app/src/main/baseline-prof.txt once reviewed (keep the file's header).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val image = SampleImage.uri()
        rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
            dismissFirstRun(image)
            killProcess()
            pressHome()
            openInEditor(image)
            editJourney()
        }
    }
}
