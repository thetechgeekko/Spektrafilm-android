/*
 * Spektrafilm for Android — zoom (ROI) render timing. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RoiTimingTest {

    @Test
    fun lineReportsEachMilestoneFromTheSettle() {
        var now = 1_000_000_000L
        val t = RoiTiming { now }
        now += 12_000_000L
        t.sourceMs = t.sinceStartMs()
        t.cropW = 1200
        t.cropH = 900
        now += 168_000_000L
        t.draftMs = t.sinceStartMs()
        now += 460_000_000L
        t.finalMs = t.sinceStartMs()
        now += 10_000_000L
        assertEquals(
            "zoom roi done crop=1200x900 source=12ms draft=180ms final=640ms total=650ms",
            t.line("done"),
        )
    }

    @Test
    fun unreachedMilestonesPrintAsDash() {
        var now = 0L
        val t = RoiTiming { now }
        now += 2_000_000_000L
        assertEquals(
            "zoom roi superseded crop=0x0 source=- draft=- final=- total=2000ms",
            t.line("superseded"),
        )
    }
}
