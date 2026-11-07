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
import java.io.File

class ParamHelpTest {

    /** English text of every app string, read from res/values (resources aren't loaded on the JVM). */
    private val english: Map<String, String> by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var values: File? = null
        while (dir != null && values == null) {
            values = listOf(File(dir, "app/src/main/res/values"), File(dir, "src/main/res/values"))
                .firstOrNull { it.isDirectory }
            dir = dir.parentFile
        }
        val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        values!!.listFiles { f -> f.extension == "xml" }.orEmpty()
            .flatMap { f -> entry.findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }
            .associate { (name, raw) ->
                name to raw.replace("\\\"", "\"").replace("\\'", "'").replace("&amp;", "&").replace("&lt;", "<")
            }
    }

    /** Resource id -> resource name, through the generated R class. */
    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private fun text(id: Int): String =
        english[names[id] ?: error("unknown string id $id")] ?: error("no English text for ${names[id]}")

    private val ParamHelp.titleText get() = text(title)
    private val ParamHelp.summaryText get() = text(summary)
    private val ParamHelp.bodyText get() = text(body)

    /** The opaque controls §6h names (couplers/grain/halation/print-gamma) — plus preflash/glare/contrast mask. */
    private val opaqueKeys = listOf(
        ParamHelpText.GRAIN,
        ParamHelpText.HALATION,
        ParamHelpText.COUPLERS,
        ParamHelpText.PRINT_GAMMA,
        ParamHelpText.PREFLASH,
        ParamHelpText.GLARE,
        ParamHelpText.CONTRAST_MASK,
    )

    @Test
    fun everyOpaqueControlHasHelp() {
        for (key in opaqueKeys) {
            val help = ParamHelpText.forKey(key)
            assertNotNull("no help registered for '$key'", help)
            help!!
            assertTrue("blank title for '$key'", help.titleText.isNotBlank())
            assertTrue("blank summary for '$key'", help.summaryText.isNotBlank())
            assertTrue("blank body for '$key'", help.bodyText.isNotBlank())
        }
    }

    /** Each help points at its own help_<key>_* strings (also proves R ids resolve uniquely here). */
    @Test
    fun helpUsesItsOwnStringResources() {
        for ((key, help) in ParamHelpText.sections) {
            assertEquals("help_${key}_title", names[help.title])
            assertEquals("help_${key}_summary", names[help.summary])
            assertEquals("help_${key}_body", names[help.body])
        }
    }

    @Test
    fun registryHoldsExactlyTheDeclaredKeys() {
        assertEquals(opaqueKeys.toSet(), ParamHelpText.sections.keys)
    }

    @Test
    fun summaryIsAConciseSingleLineAndBodyIsRicher() {
        for ((key, help) in ParamHelpText.sections) {
            assertFalse("summary should be a single line: '$key'", help.summaryText.contains('\n'))
            assertTrue(
                "summary should stay a one-liner (<=100 chars): '$key' is ${help.summaryText.length}",
                help.summaryText.length <= 100,
            )
            assertTrue("body should be a real explanation (>=80 chars): '$key'", help.bodyText.length >= 80)
            assertTrue("body should add more than the summary: '$key'", help.bodyText.length > help.summaryText.length)
        }
    }

    @Test
    fun unknownKeyReturnsNull() {
        assertNull(ParamHelpText.forKey("not-a-control"))
    }
}
