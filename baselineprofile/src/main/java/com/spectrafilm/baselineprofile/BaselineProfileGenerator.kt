/*
 * Spektrafilm for Android — Baseline + Startup Profile generator (#459). GPLv3.
 *
 * ./gradlew :app:generateReleaseBaselineProfile records this on a connected device and
 * writes the profiles into app/src/main/generated/baselineProfiles/.
 */
package com.spectrafilm.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        // Also emit startup-prof.txt for DEX layout (dexLayoutOptimization in :app).
        includeInStartupProfile = true,
    ) {
        startEditor()
        editJourney()
    }
}
