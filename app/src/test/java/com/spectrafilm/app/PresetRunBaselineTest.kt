/*
 * Spektrafilm for Android — unit tests for the built-in preset browsing baseline. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Built-in presets are sparse overlays, so tapping two in a row compounds them. The editor
 * rewinds to the look a browsing run started from before overlaying the next preset;
 * presetRunBaseline is the decision of what that look is. The rewind itself is a no-op
 * exactly when this returns the current state, so this function alone decides whether a
 * preset lands on a clean baseline or on the previous preset's leftovers.
 *
 * See BuiltInPresetsApplyTest for the other half: that the rewind actually clears a field
 * the incoming preset does not author.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetRunBaselineTest {

    private val lookA = """{"v":1,"film":"a"}"""
    private val lookB = """{"v":1,"film":"b"}"""
    private val edited = """{"v":1,"film":"b","grain":3}"""

    @Test
    fun firstApplyOfARunBaselinesOnWhateverIsOnScreen() {
        // Nothing applied yet: the current look is the baseline, and the caller's rewind
        // is a no-op because the baseline IS the current state.
        assertEquals(lookA, presetRunBaseline(now = lookA, lastOutput = null, runBaseline = null))
    }

    @Test
    fun consecutiveApplesKeepTheRunsOriginalBaseline() {
        // The user tapped a preset (producing lookB from lookA) and taps another without
        // touching anything: the second must overlay lookA, not lookB.
        assertEquals(
            lookA,
            presetRunBaseline(now = lookB, lastOutput = lookB, runBaseline = lookA),
        )
    }

    @Test
    fun aHandEditEndsTheRunAndBecomesTheNewBaseline() {
        // State no longer matches what the last apply produced, so the user changed
        // something. Their work is the new starting point and must not be rewound away.
        assertEquals(
            edited,
            presetRunBaseline(now = edited, lastOutput = lookB, runBaseline = lookA),
        )
    }

    @Test
    fun aRunThatSomehowLostItsBaselineFallsBackToTheCurrentLook() {
        assertEquals(lookB, presetRunBaseline(now = lookB, lastOutput = lookB, runBaseline = null))
    }

    @Test
    fun anUnreadableCurrentStateYieldsNoBaselineRatherThanAStaleOne() {
        // toJsonString failed. Returning the old run baseline here would rewind the editor
        // to an unrelated look; returning null makes the caller skip the rewind entirely.
        assertEquals(null, presetRunBaseline(now = null, lastOutput = lookB, runBaseline = lookA))
    }

    @Test
    fun resetsAndSourceChangesNeedNoClearSiteBecauseTheStateStopsMatching() {
        // The property that lets this work without a hand-maintained list of places to
        // null the anchor: any state that is not the last apply's output ends the run.
        val afterReset = """{"v":1,"film":"defaults"}"""
        assertEquals(
            afterReset,
            presetRunBaseline(now = afterReset, lastOutput = lookB, runBaseline = lookA),
        )
    }
}
