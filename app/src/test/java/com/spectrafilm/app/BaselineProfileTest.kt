/*
 * Spektrafilm for Android — baseline profile syntax gate (#283). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGP only warns on a baseline-prof.txt line it cannot parse and drops it, so a typo would
 * silently ship a release without the app's profile. This keeps every rule in the shapes the
 * file uses: a method rule `[HSP]+L<class>;?->name(args)ret` or a class rule `L<class>;`.
 */
class BaselineProfileTest {
    // #459 (#504) moved the profile to the Baseline Profile plugin's generated location.
    private val file = File("src/main/generated/baselineProfiles/baseline-prof.txt")

    // '-' appears in recorded R8 synthetic names (`$r8$lambda$gcIK6Em3026em-cFe...`).
    private val methodRule = Regex("""^[HSP]{1,3}L[\w/*$-]+;?->[\w*<>$-]+\([\w/;\[*$-]*\)[\w/;\[*$-]+$""")
    private val classRule = Regex("""^L[\w/*$-]+;$""")

    private fun rules() = file.readLines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun everyRuleParses() {
        val bad = rules().filterNot { methodRule.matches(it) || classRule.matches(it) }
        assertTrue("unparseable baseline-profile rules: $bad", bad.isEmpty())
    }

    @Test
    fun coversTheAppAndEngine() {
        val r = rules()
        assertTrue(r.any { it.startsWith("HSPLcom/spectrafilm/app/") })
        assertTrue(r.any { it.startsWith("HSPLcom/spectrafilm/engine/") })
    }
}
