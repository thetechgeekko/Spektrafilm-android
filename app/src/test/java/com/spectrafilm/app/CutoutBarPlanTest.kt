/*
 * Spektrafilm for Android — editor bar planning beside the camera cutout. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor bar used to drop below the front camera in full screen once Compare and the
 * Export + Share pill joined it: the pill took every spare pixel, so nothing fit beside the
 * cutout and the status strip sat empty. Numbers are the test phone's (1080 px wide,
 * density 2.8125, a centred punch hole at x 510..570).
 */
class CutoutBarPlanTest {
    private val d = 2.8125f
    private fun px(dp: Int) = Math.round(dp * d)
    private val rowWidth = 1080 - 2 * px(4)
    private val punchHole = listOf((510 - px(4) - px(8)) to (570 - px(4) + px(8)))

    @Test
    fun noCutoutKeepsTheUsualBar() {
        val plan = planEditorBar(rowWidth, emptyList(), px(48), px(106), wide = false, showPill = true)
        assertTrue(plan.compareInBar)
        assertNull(plan.pillMaxWidth)
    }

    @Test
    fun centredPunchHoleMovesCompareIntoMoreAndSizesThePill() {
        val plan = planEditorBar(rowWidth, punchHole, px(48), px(106), wide = false, showPill = true)
        assertFalse("Compare cannot fit beside a centred hole on a 411dp phone", plan.compareInBar)
        val pill = assertNotNullAnd(plan.pillMaxWidth)
        assertTrue("the icon-only pill fits", pill >= px(106))
        // The planned row really fits beside the hole with that pill.
        val actions = listOf(px(48), px(48), pill, px(48))
        assertTrue(placeBesideCutout(rowWidth, punchHole, listOf(px(48)), actions).fits)
        // ...and one pixel more would not.
        val wider = listOf(px(48), px(48), pill + 1, px(48))
        assertFalse(placeBesideCutout(rowWidth, punchHole, listOf(px(48)), wider).fits)
    }

    @Test
    fun withoutThePillCompareStays() {
        val plan = planEditorBar(rowWidth, punchHole, px(48), px(106), wide = false, showPill = false)
        assertTrue(plan.compareInBar)
    }

    @Test
    fun placementStepsActionsPastTheHole() {
        val actions = listOf(px(48), px(48), px(106), px(48))
        val p = placeBesideCutout(rowWidth, punchHole, listOf(px(48)), actions)
        assertTrue(p.fits)
        val (l, r) = punchHole.single()
        actions.indices.forEach { i ->
            val from = p.endX[i]
            val to = from + actions[i]
            assertTrue("action $i covers the camera", to <= l || from >= r)
        }
        assertEquals(0, p.startX.single())
    }

    private fun assertNotNullAnd(v: Int?): Int {
        assertNotNull(v)
        return v!!
    }
}
