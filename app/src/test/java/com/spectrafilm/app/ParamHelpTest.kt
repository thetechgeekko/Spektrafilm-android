/*
 * Spektrafilm for Android — unit tests for the plain-language control help (onboarding §6h). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The help content is data, so its coverage and the "concise one-line summary + richer body"
 * contract are checkable on the plain JVM. Guards against an opaque control shipping with no help,
 * an empty field, or a summary that is really a paragraph.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamHelpTest {

    /** The opaque controls §6h names (couplers/grain/halation/print-gamma) — plus preflash/glare. */
    private val opaqueKeys = listOf(
        ParamHelpText.GRAIN,
        ParamHelpText.HALATION,
        ParamHelpText.COUPLERS,
        ParamHelpText.PRINT_GAMMA,
        ParamHelpText.PREFLASH,
        ParamHelpText.GLARE,
    )

    @Test
    fun everyOpaqueControlHasHelp() {
        for (key in opaqueKeys) {
            val help = ParamHelpText.forKey(key)
            assertNotNull("no help registered for '$key'", help)
            help!!
            assertTrue("blank title for '$key'", help.title.isNotBlank())
            assertTrue("blank summary for '$key'", help.summary.isNotBlank())
            assertTrue("blank body for '$key'", help.body.isNotBlank())
        }
    }

    @Test
    fun registryHoldsExactlyTheDeclaredKeys() {
        assertEquals(opaqueKeys.toSet(), ParamHelpText.sections.keys)
    }

    @Test
    fun summaryIsAConciseSingleLineAndBodyIsRicher() {
        for ((key, help) in ParamHelpText.sections) {
            assertFalse("summary should be a single line: '$key'", help.summary.contains('\n'))
            assertTrue(
                "summary should stay a one-liner (<=100 chars): '$key' is ${help.summary.length}",
                help.summary.length <= 100,
            )
            assertTrue("body should be a real explanation (>=80 chars): '$key'", help.body.length >= 80)
            assertTrue("body should add more than the summary: '$key'", help.body.length > help.summary.length)
        }
    }

    @Test
    fun unknownKeyReturnsNull() {
        assertNull(ParamHelpText.forKey("not-a-control"))
    }
}
