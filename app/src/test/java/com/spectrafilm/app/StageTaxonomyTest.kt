package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's tool taxonomy: three modes in the bottom bar (Looks, Edit, Masks; Crop is an
 * action beside them), and Edit split into sections by what each tool changes.
 *
 * The panels are built by asking each [Stage] which tools belong to it, so a tool that fell
 * out of the mapping would simply stop being reachable — no crash, no warning, just a panel a
 * user can no longer open. `Category.stage` is an exhaustive `when` so the compiler catches a
 * *new* tool, but nothing except these tests catches one quietly moved into no section.
 */
class StageTaxonomyTest {

    @Test
    fun everyToolIsReachableFromExactlyOneSection() {
        val placed = Stage.entries.flatMap { categoriesOf(it) }
        assertEquals(
            "a tool is claimed by more than one section: " +
                placed.groupBy { it }.filterValues { it.size > 1 }.keys,
            placed.size,
            placed.toSet().size,
        )
        assertEquals("every tool must be reachable", Category.entries.toSet(), placed.toSet())
    }

    @Test
    fun everySectionBelongsToItsModeAndNoneIsEmpty() {
        val empty = Stage.entries.filter { categoriesOf(it).isEmpty() }
        assertTrue("these sections hold no tools: $empty", empty.isEmpty())
        assertEquals(Stage.entries.toSet(), EditorMode.entries.flatMap { stagesOf(it) }.toSet())
        // Looks and Masks are one group each; Edit is the one with section tabs.
        assertEquals(listOf(Stage.LOOKS), stagesOf(EditorMode.LOOKS))
        assertEquals(listOf(Stage.MASKS), stagesOf(EditorMode.MASKS))
    }

    @Test
    fun editSectionsAreInTheApprovedOrder() {
        assertEquals(
            listOf(
                Stage.LIGHT, Stage.COLOR, Stage.FILM, Stage.PRINT,
                Stage.EFFECTS, Stage.DETAIL, Stage.FRAME, Stage.OUTPUT, Stage.LAB,
            ),
            stagesOf(EditorMode.EDIT),
        )
    }

    @Test
    fun aSectionStaysShortEnoughToStack() {
        // A section's tools are stacked in one scrolling panel; past five it stops being a
        // section and becomes the old fourteen-chip pile again.
        val worst = Stage.entries.maxOf { categoriesOf(it).size }
        assertTrue("a section holds $worst tools, which is too many", worst <= 5)
    }

    @Test
    fun theBottomBarHoldsThreeModes() {
        // Looks, Edit and Masks, with Crop beside them: a Row with SpaceBetween, not a
        // LazyRow, so it cannot scroll if it overflows.
        assertEquals(3, EditorMode.entries.size)
    }

    @Test
    fun everyStageHasDistinctLabelAndHintResources() {
        val labels = Stage.entries.map { it.labelRes }
        val hints = Stage.entries.map { it.hintRes }
        assertEquals("two sections share a label string", labels.size, labels.toSet().size)
        assertEquals("two sections share a hint string", hints.size, hints.toSet().size)
        assertTrue(
            "a section reuses its label as its hint",
            labels.toSet().intersect(hints.toSet()).isEmpty(),
        )
    }

    @Test
    fun theOpeningToolOfEachSectionIsStable() {
        // Selecting a section scrolls to categoriesOf(section).first(). Pin those so a
        // reordering of the Category enum cannot silently change where each one lands.
        assertEquals(Category.SIMULATION, categoriesOf(Stage.LOOKS).first())
        assertEquals(Category.EXPOSURE, categoriesOf(Stage.LIGHT).first())
        assertEquals(Category.RAW_WB, categoriesOf(Stage.COLOR).first())
        assertEquals(Category.FILM_FORMAT, categoriesOf(Stage.FILM).first())
        assertEquals(Category.PRINT_EXPOSURE, categoriesOf(Stage.PRINT).first())
        assertEquals(Category.GLARE, categoriesOf(Stage.EFFECTS).first())
        assertEquals(Category.SCANNER, categoriesOf(Stage.DETAIL).first())
        assertEquals(Category.FRAME, categoriesOf(Stage.FRAME).first())
        assertEquals(Category.OUTPUT, categoriesOf(Stage.OUTPUT).first())
        assertEquals(Category.INPUT, categoriesOf(Stage.LAB).first())
        assertEquals(Category.MASKS, categoriesOf(Stage.MASKS).first())
    }

    @Test
    fun persistedToolNamesSurvive() {
        // Category names are stored in the editor session (EditorSessionCodec): renaming or
        // removing one would drop a saved session's open panel.
        val legacy = listOf(
            "PRESETS", "SOURCE", "SIMULATION", "INPUT", "RAW_WB", "GRAIN", "HALATION", "SCANNER",
            "GLARE", "COUPLERS", "PAPER", "PREFLASH", "CONTRAST_MASK", "TONE_CURVE", "OUTPUT",
            "MASKS", "DISPLAY", "EXPERIMENTAL",
        )
        val names = Category.entries.map { it.name }.toSet()
        assertTrue("missing: ${legacy - names}", names.containsAll(legacy))
    }
}
