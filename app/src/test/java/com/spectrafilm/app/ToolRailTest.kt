package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The rail chips' hold-to-toggle: which tools have a switch, and that it drives the param. */
class ToolRailTest {
    private val switched = setOf(
        Category.TONE_CURVE, Category.SCENE_LATITUDE, Category.CONTRAST_MASK, Category.COUPLERS,
        Category.GRAIN, Category.HALATION, Category.CAMERA_DIFFUSION, Category.GLARE,
    )

    @Test
    fun onlyToolsWithAnEngineSwitchHaveOne() {
        val s = ParamsState()
        for (tool in Category.entries) {
            if (tool in switched) assertNotNull(tool.name, toolSwitch(s, tool)) else assertNull(tool.name, toolSwitch(s, tool))
        }
    }

    @Test
    fun aSwitchFlipsItsToolAndReportsTheNewState() {
        val s = ParamsState()
        for (tool in switched) {
            val before = toolSwitch(s, tool)!!.on
            toolSwitch(s, tool)!!.set(!before)
            assertEquals(tool.name, !before, toolSwitch(s, tool)!!.on)
        }
        // Spot-check the wiring to the real fields.
        assertEquals(!PARAM_DEFAULTS.halationActive, s.halationActive)
        assertEquals(!PARAM_DEFAULTS.cameraDiffusionState.active, s.cameraDiffusionState.active)
    }
}
