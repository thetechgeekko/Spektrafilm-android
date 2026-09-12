package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-category reset.
 *
 * The whole safety argument for building it out of [categoryDefaults] rather than a
 * hand-written list of fields is that a partial recipe provably cannot reach outside its own
 * sections — `Presets.applyJson` reads each section behind its own `optJSONObject(...)?.let`.
 * That argument is only worth anything if something checks it, because the failure mode is a
 * reset that quietly clears a neighbouring category's work.
 */
class CategoryResetTest {

    @Test
    fun everyMappedCategoryProducesDefaultsForExactlyItsOwnSections() {
        for ((category, sections) in CATEGORY_SECTIONS) {
            val defaults = categoryDefaults(category)
            assertNotNull("no defaults for $category", defaults)
            val keys = requireNotNull(defaults).keys().asSequence().toSet()
            assertEquals("$category carried the wrong sections", sections.toSet(), keys)
        }
    }

    @Test
    fun resettingOneCategoryLeavesAnotherAlone() {
        val state = ParamsState()
        // Move two categories away from neutral.
        state.grainBlur = 0.99f
        state.grainParticleAreaUm2 = 0.77f
        state.halScatterAmount = 0.42f

        val grainDefaults = requireNotNull(categoryDefaults(Category.GRAIN))
        Presets.decode(grainDefaults, state)

        val fresh = ParamsState()
        assertEquals("grain should be back to neutral", fresh.grainBlur, state.grainBlur, 1e-6f)
        assertEquals(fresh.grainParticleAreaUm2, state.grainParticleAreaUm2, 1e-6f)
        // The point of the test: halation is a different section and must not have moved.
        assertEquals("halation was collateral damage", 0.42f, state.halScatterAmount, 1e-6f)
    }

    @Test
    fun theResetActuallyClearsTheDirtyMarkForThatCategoryOnly() {
        val state = ParamsState()
        state.grainBlur = 0.99f
        state.halScatterAmount = 0.42f
        assertTrue(Category.GRAIN in dirtyCategories(state))
        assertTrue(Category.HALATION in dirtyCategories(state))

        Presets.decode(requireNotNull(categoryDefaults(Category.GRAIN)), state)

        val after = dirtyCategories(state)
        assertTrue("grain dot should be gone", Category.GRAIN !in after)
        assertTrue("halation dot should remain", Category.HALATION in after)
    }

    @Test
    fun anUnmappedCategoryOffersNoReset() {
        // SIMULATION and PREFLASH both live in the "enlarger" section and are deliberately
        // absent from CATEGORY_SECTIONS. They must return null rather than an empty object,
        // which would decode as a no-op reset button that appears to do nothing.
        assertTrue(Category.SIMULATION !in CATEGORY_SECTIONS)
        assertEquals(null, categoryDefaults(Category.SIMULATION))
    }

    @Test
    fun defaultsAreCopiedNotAliased() {
        // categoryDefaults hands its result to a decoder. If it returned the live section from
        // the shared DEFAULT_ENCODED singleton, a mutation there would corrupt every dirty
        // comparison in the app at once.
        val a = requireNotNull(categoryDefaults(Category.GRAIN)).getJSONObject("grain")
        a.put("blur", 12345.0)
        val b = requireNotNull(categoryDefaults(Category.GRAIN)).getJSONObject("grain")
        assertTrue("the shared defaults were mutated", b.optDouble("blur", 0.0) != 12345.0)
    }
}
