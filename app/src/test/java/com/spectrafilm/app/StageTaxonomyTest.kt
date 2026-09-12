package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five-stage parameter taxonomy that replaced fourteen flat chips.
 *
 * The rail is built by asking each [Stage] which categories belong to it, so a category that
 * fell out of the mapping would simply stop being reachable — no crash, no warning, just a
 * panel a user can no longer open. `Category.stage` is an exhaustive `when` so the compiler
 * catches a *new* category, but nothing except these tests catches a category quietly moved
 * into no stage, or two stages both claiming to own the whole set.
 */
class StageTaxonomyTest {

    @Test
    fun everyCategoryIsReachableFromExactlyOneStage() {
        val placed = Stage.entries.flatMap { categoriesOf(it) }
        assertEquals(
            "a category is claimed by more than one stage: " +
                placed.groupBy { it }.filterValues { it.size > 1 }.keys,
            placed.size,
            placed.toSet().size,
        )
        assertEquals(
            "every Category must be reachable from the rail",
            Category.entries.toSet(),
            placed.toSet(),
        )
    }

    @Test
    fun noStageIsEmpty() {
        // An empty stage renders a chip that opens nothing.
        val empty = Stage.entries.filter { categoriesOf(it).isEmpty() }
        assertTrue("these stages hold no categories: $empty", empty.isEmpty())
    }

    @Test
    fun theSecondRowStaysShortEnoughToFitAPhone() {
        // The whole point of the change: the old row was all 14 at once. Six 72dp chips is
        // ~432dp, which still scrolls a little at 360dp but is a bounded, obvious amount
        // rather than a bar running past 1000dp.
        val worst = Stage.entries.maxOf { categoriesOf(it).size }
        assertTrue("a stage holds $worst groups, which is too many for one row", worst <= 6)
    }

    @Test
    fun theStageRowItselfNeverScrolls() {
        // Five fixed chips is the invariant the bottom row depends on: it is a Row with
        // SpaceEvenly, not a LazyRow, so it cannot scroll if it overflows.
        assertEquals(5, Stage.entries.size)
    }

    @Test
    fun everyStageHasDistinctLabelAndHintResources() {
        val labels = Stage.entries.map { it.labelRes }
        val hints = Stage.entries.map { it.hintRes }
        assertEquals("two stages share a label string", labels.size, labels.toSet().size)
        assertEquals("two stages share a hint string", hints.size, hints.toSet().size)
        assertTrue(
            "a stage reuses its label as its hint",
            labels.toSet().intersect(hints.toSet()).isEmpty(),
        )
    }

    @Test
    fun theOpeningGroupOfEachStageIsStable() {
        // Selecting a stage opens categoriesOf(stage).first(). Pin those so a reordering of
        // the Category enum cannot silently change which panel each stage lands on.
        assertEquals(Category.SOURCE, categoriesOf(Stage.LOOK).first())
        assertEquals(Category.SIMULATION, categoriesOf(Stage.FILM).first())
        assertEquals(Category.PREFLASH, categoriesOf(Stage.PRINT).first())
        assertEquals(Category.GLARE, categoriesOf(Stage.SCAN).first())
        assertEquals(Category.EXPERIMENTAL, categoriesOf(Stage.FINISH).first())
    }
}
