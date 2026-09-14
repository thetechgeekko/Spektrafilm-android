/*
 * Spektrafilm for Android — unit tests for preset name resolution. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A saved preset's on-disk name is not the typed name: safeName trims it, caps it at 96
 * characters and rewrites anything outside `A-Za-z0-9_- ` to an underscore. That was
 * invisible — the save status line reported the name the user typed while the file was
 * stored under a different one, so "Portra #2" silently became "Portra _2" and a second
 * save of "Portra @2" would overwrite it.
 *
 * Presets.resolveName exposes the rule so the panel can show it before the save and the
 * status line can report what actually happened. These pin the rule itself.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetNameTest {

    @Test
    fun punctuationBecomesUnderscores() {
        assertEquals("Portra _2", Presets.resolveName("Portra #2"))
        assertEquals("golden_hour", Presets.resolveName("golden.hour"))
        assertEquals("a_b", Presets.resolveName("a/b"))
    }

    @Test
    fun spacesHyphensAndUnderscoresSurvive() {
        // The characters that do NOT get rewritten — the panel only warns when the name
        // actually changes, so this set is what keeps the warning quiet for normal names.
        assertEquals("Soft Light Portrait", Presets.resolveName("Soft Light Portrait"))
        assertEquals("warm-tone_2", Presets.resolveName("warm-tone_2"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("Ektar", Presets.resolveName("   Ektar   "))
    }

    @Test
    fun blankFallsBackRatherThanProducingAnEmptyFilename() {
        assertEquals("preset", Presets.resolveName(""))
        assertEquals("preset", Presets.resolveName("    "))
    }

    @Test
    fun longNamesAreCappedAt96() {
        val long = "x".repeat(200)
        assertEquals(96, Presets.resolveName(long).length)
    }

    @Test
    fun distinctTypedNamesCanCollideOnDisk() {
        // The reason the overwrite confirmation checks the RESOLVED name: these are two
        // different names to the user and the same file on disk.
        assertEquals(Presets.resolveName("Portra #2"), Presets.resolveName("Portra @2"))
        assertTrue(Presets.resolveName("Portra #2") != "Portra #2")
    }
}
