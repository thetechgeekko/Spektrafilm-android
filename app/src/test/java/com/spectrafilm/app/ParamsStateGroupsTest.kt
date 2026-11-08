/*
 * Spektrafilm for Android — unit tests for the immutable ParamsState groups (#452). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ParamsStateGroupsTest {

    @Test
    fun aFreshStateSnapshotsToTheGroupDefaults() {
        val snap = ParamsState().snapshot()
        assertEquals(GrainUiParams(), snap.grain)
        assertEquals(HalationUiParams(), snap.halation)
        assertEquals(DiffusionUiParams(), snap.cameraDiffusion)
        assertEquals(ProfileUiParams(), snap.profiles)
    }

    @Test
    fun anEditReplacesOnlyItsOwnGroup() {
        val s = ParamsState()
        val before = s.snapshot()
        s.grainBlur = 2.5f
        val after = s.snapshot()
        assertEquals(2.5f, after.grain.grainBlur)
        assertEquals(before.grain.copy(grainBlur = 2.5f), after.grain)
        // Other groups keep the very same instance.
        assertSame(before.halation, after.halation)
        assertSame(before.profiles, after.profiles)
        assertNotSame(before.grain, after.grain)
    }

    @Test
    fun aSnapshotIsNotChangedByLaterEdits() {
        val s = ParamsState()
        val snap = s.snapshot()
        s.printDiffusionState.strength = 0.9f
        s.filmProfile = "kodak_gold_200"
        assertEquals(0.5f, snap.printDiffusion.strength)
        assertEquals("kodak_portra_400", snap.profiles.filmProfile)
        assertEquals(0.9f, s.snapshot().printDiffusion.strength)
    }

    @Test
    fun theSnapshotRoundTripsThroughPresets() {
        val s = ParamsState()
        s.halHalationStrengthPct = Triple(7f, 2f, 1f)
        s.toneCurveActive = true
        s.dateStampText = "'98 11 08"
        val copy = ParamsState()
        Presets.decode(Presets.encode(s), copy)
        assertEquals(s.snapshot(), copy.snapshot())
    }
}
