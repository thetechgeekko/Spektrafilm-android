/*
 * Spektrafilm for Android — stage-weighted export progress (#466). GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProgressTest {

    @Test
    fun weightsCoverTheWholeExport() {
        assertEquals(1.0, ExportStage.entries.sumOf { it.weight.toDouble() }, 1e-4)
        assertTrue(ExportStage.entries.all { it.weight > 0f })
    }

    @Test
    fun stagesStartInOrderFromZero() {
        assertEquals(0f, ExportStage.SETUP.startFraction, 0f)
        val starts = ExportStage.entries.map { it.startFraction }
        assertEquals(starts.sorted(), starts)
        assertTrue("ENCODE must start before the bar is full", ExportStage.ENCODE.startFraction < 1f)
    }

    @Test
    fun simulateIsMostOfTheBarBecauseItIsMostOfTheTime() {
        // perf-lab §19.1 release: simulate 5504 of 6251 ms.
        assertTrue(ExportStage.SIMULATE.weight > 0.8f)
    }

    @Test
    fun progressNeverGoesBackwards() {
        var p = 0f
        p = mergeExportProgress(p, ExportStage.SIMULATE)
        val afterSimulate = p
        p = mergeExportProgress(p, ExportStage.DECODE) // a late report of an earlier stage
        assertEquals(afterSimulate, p, 0f)
        p = mergeExportProgress(p, ExportStage.ENCODE)
        assertTrue(p > afterSimulate)
        assertTrue(p <= 1f)
    }
}
