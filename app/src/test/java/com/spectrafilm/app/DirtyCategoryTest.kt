package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category "modified" dot.
 *
 * `dirtyCategories` attributes an edit to a chip by comparing the top-level sections of
 * `Presets.encode`. That indirection is what keeps a new parameter covered automatically — but
 * it also means a renamed or removed section would silently leave a chip permanently dark, with
 * nothing on screen to say so. `everySectionNameExistsInTheEncoding` is the guard for exactly
 * that, and is the reason this file exists rather than a hand-written field list.
 */
class DirtyCategoryTest {

    @Test
    fun neutralStateHasNoDots() {
        assertEquals(emptySet<Category>(), dirtyCategories(ParamsState()))
    }

    @Test
    fun anEditLandsOnItsOwnCategoryAndNoOther() {
        fun only(expected: Category, edit: ParamsState.() -> Unit) {
            val s = ParamsState().apply(edit)
            assertEquals("edit should mark exactly $expected", setOf(expected), dirtyCategories(s))
        }
        only(Category.GRAIN) { grainBlur = 0.9f }
        only(Category.HALATION) { halHalationAmount = 1.4f }
        only(Category.GLARE) { glarePercent = 0.2f }
        only(Category.TONE_CURVE) { toneCurveActive = true }
        only(Category.INPUT) { spectralGaussianBlur = 3f }
    }

    @Test
    fun rawAndCreativeWhiteBalanceShareOneChip() {
        // RAW_WB is the one category that owns two encoded sections; both must light it.
        assertEquals(
            setOf(Category.RAW_WB),
            dirtyCategories(ParamsState().apply { rawTemperature = 4200f }),
        )
        assertEquals(
            setOf(Category.RAW_WB),
            dirtyCategories(ParamsState().apply { creativeWbTemp = 12f }),
        )
    }

    @Test
    fun twoEditsLightTwoChips() {
        val s = ParamsState().apply {
            grainBlur = 0.9f
            glarePercent = 0.2f
        }
        assertEquals(setOf(Category.GRAIN, Category.GLARE), dirtyCategories(s))
    }

    @Test
    fun everySectionNameExistsInTheEncoding() {
        // A typo or a renamed section would make a chip that can never light up, and nothing
        // else in the app would notice. Encode a neutral state and require every claimed key.
        val encoded = Presets.encode(ParamsState())
        val claimed = CATEGORY_SECTIONS.values.flatten().toSet()
        val missing = claimed.filterNot { encoded.has(it) }
        assertTrue(
            "CATEGORY_SECTIONS names sections that Presets.encode does not emit: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun noSectionIsClaimedByTwoCategories() {
        // Two chips sharing a section would both light for one edit, which is the "wrong dot"
        // failure the table exists to avoid. SIMULATION/PREFLASH are deliberately absent.
        val all = CATEGORY_SECTIONS.values.flatten()
        assertEquals(
            "a section is claimed by more than one category: " +
                all.groupBy { it }.filterValues { it.size > 1 }.keys,
            all.size,
            all.toSet().size,
        )
    }
}
