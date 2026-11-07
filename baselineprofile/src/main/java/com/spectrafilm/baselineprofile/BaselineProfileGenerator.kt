/*
 * Spektrafilm for Android — Baseline + Startup Profile generator (#459). GPLv3.
 *
 * ./gradlew :app:generateBaselineProfile records this on a connected device and
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

    /** Cold start to the first preview: also the Startup Profile used for DEX layout. */
    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        startEditor()
    }

    /** The edit journey: Baseline Profile only, so the startup DEX stays small. */
    @Test
    fun edit() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = false,
    ) {
        startEditor()
        editJourney()
    }
}
