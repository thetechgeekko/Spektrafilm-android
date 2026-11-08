package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * History steps are named by diffing neighbouring snapshots (interaction rule: release
 * creates a named entry), so these pin the diff, the path-to-tool mapping and the timeline
 * order that the history list walks with undo and redo.
 */
class EditHistoryLabelsTest {

    private fun snap(rotation: Int = 0, edit: ParamsState.() -> Unit = {}): EditSnapshot =
        EditSnapshot(Presets.encode(ParamsState().apply(edit)).toString(), rotation)

    @Test
    fun noChangeHasNoName() {
        assertNull(describeStep(snap(), snap()))
    }

    @Test
    fun oneSliderNamesItsToolAndValue() {
        val label = describeStep(snap(), snap { exposureCompensationEv = 0.7f })!!
        assertEquals(listOf(HistoryTag.Tool(Category.EXPOSURE)), label.tags)
        assertEquals(0.7, label.number!!, 1e-6)
        assertEquals("+0.70", formatHistoryNumber(label.number!!))
    }

    @Test
    fun aSwitchReportsOnOrOff() {
        val before = ParamsState().autoExposure
        val label = describeStep(snap(), snap { autoExposure = !before })!!
        assertEquals(listOf(HistoryTag.Tool(Category.EXPOSURE)), label.tags)
        assertEquals(!before, label.toggle)
    }

    @Test
    fun stocksAndRotationGetTheirOwnNames() {
        assertEquals(
            listOf(HistoryTag.Tool(Category.SIMULATION)),
            describeStep(snap { filmProfile = "a" }, snap { filmProfile = "b" })!!.tags,
        )
        assertEquals(
            listOf(HistoryTag.Tool(Category.PAPER)),
            describeStep(snap { printProfile = "a" }, snap { printProfile = "b" })!!.tags,
        )
        assertEquals(listOf(HistoryTag.Rotate), describeStep(snap(), snap(rotation = 90))!!.tags)
    }

    @Test
    fun sectionKeysMapToTheToolThatOwnsThem() {
        // "camera" is shared by Exposure, Film format, Lens blur, Diffusion and Date stamp.
        assertEquals(HistoryTag.Tool(Category.FILM_FORMAT), tagForPath("camera.filmFormatMm"))
        assertEquals(HistoryTag.Tool(Category.LENS_BLUR), tagForPath("camera.lensBlurUm"))
        assertEquals(HistoryTag.Tool(Category.CAMERA_DIFFUSION), tagForPath("camera.diffusion"))
        // Whole-section tools own every key inside them.
        assertEquals(HistoryTag.Tool(Category.GRAIN), tagForPath("grain.blur"))
        assertEquals(HistoryTag.Crop, tagForPath("input.crop"))
        assertEquals(HistoryTag.Straighten, tagForPath("input.straighten"))
        assertEquals(HistoryTag.Other, tagForPath("nonsense"))
    }

    @Test
    fun aMultiToolStepListsToolsInEditorOrderWithNoValue() {
        val label = describeStep(snap(), snap { halHalationAmount = 1.4f; grainBlur = 0.9f })!!
        assertEquals(listOf(HistoryTag.Tool(Category.GRAIN), HistoryTag.Tool(Category.HALATION)), label.tags)
        assertNull(label.number)
        assertNull(label.toggle)
    }

    @Test
    fun timelineRunsUndoThenLiveThenRedoInRedoOrder() {
        val (a, b, live, c, d) = listOf(snap(0), snap(90), snap(180), snap(270), snap(1))
        // EditHistory.redo pops the LAST redo entry, so [d, c] reaches c first.
        val (timeline, current) = historyTimeline(EditHistoryState(undo = listOf(a, b), redo = listOf(d, c)), live)
        assertEquals(listOf(a, b, live, c, d), timeline)
        assertEquals(2, current)
    }

    @Test
    fun wholeNumbersDropTheirDecimals() {
        assertEquals("+2", formatHistoryNumber(2.0))
        assertEquals("-15", formatHistoryNumber(-15.0))
        assertEquals("-0.25", formatHistoryNumber(-0.25))
    }
}
