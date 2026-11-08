/*
 * Spektrafilm for Android — export developer-tray timing. GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDevelopTrayTest {

    @Test
    fun startsAsBlankPaper() {
        assertEquals(0f, developLevel(0f, 0f), 0f)
    }

    @Test
    fun neverFinishesThePrintWhileExporting() {
        // Simulate reports nothing inside it, so time alone must never claim a finished print.
        assertTrue(developLevel(600f, 0f) <= DEVELOP_CAP)
        assertTrue(developLevel(600f, 1f) <= DEVELOP_CAP)
        assertEquals(DEVELOP_CAP, developLevel(0f, 1f), 1e-6f)
    }

    @Test
    fun monotonicInTimeAndStage() {
        var last = -1f
        for (i in 0..200) {
            val level = developLevel(i * 0.1f, 0f)
            assertTrue("time step $i went backwards", level >= last)
            last = level
        }
        last = -1f
        for (stage in ExportStage.entries) {
            val level = developLevel(1f, stage.startFraction)
            assertTrue("$stage went backwards", level >= last)
            last = level
        }
    }

    @Test
    fun aStageStartOnlyPushesForward() {
        val timed = developLevel(4f, 0f)
        assertEquals(timed, developLevel(4f, ExportStage.DECODE.startFraction), 0f)
        assertTrue(developLevel(0.2f, ExportStage.ENCODE.startFraction) > developLevel(0.2f, 0f))
    }

    @Test
    fun aReleaseExportIsMostlyDevelopedBeforeItEnds() {
        // ~6.3 s measured release export (ExportProgress.kt): visibly developed, not finished.
        val level = developLevel(6.3f, ExportStage.ENCODE.startFraction)
        assertTrue(level > 0.75f * DEVELOP_CAP)
    }

    @Test
    fun shutterBlinksOnceThenHoldsOpen() {
        assertEquals(0f, shutterPhase(0f), 0f)
        assertEquals(0.5f, shutterPhase(SHUTTER_S / 2f), 1e-6f)
        assertEquals(1f, shutterPhase(SHUTTER_S), 0f)
        assertEquals(1f, shutterPhase(30f), 0f)
        assertEquals(0f, shutterPhase(-1f), 0f)
    }
}
