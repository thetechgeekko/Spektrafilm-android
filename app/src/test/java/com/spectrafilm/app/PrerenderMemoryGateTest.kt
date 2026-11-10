/*
 * Spektrafilm for Android — the speculative pre-render's memory gate. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrerenderMemoryGateTest {
    private val mib = 1_048_576L
    private val threshold = 216L * mib  // the SM-S948W's reported low-memory threshold

    @Test
    fun aLargeRawWithTheMemoryMeasuredAtOpenSkipsThePrerender() {
        // Opening the 41 MP panorama with ~3 GB available: the pre-render on top of the
        // editor's own preview decode had lmkd kill 18 background apps.
        assertFalse(RenderPayloads.prerenderMemoryAllows(3_000L * mib, threshold, 41_220_000L))
    }

    @Test
    fun theReadingThatLetTheMeasuredKillsThroughNowRefuses() {
        // With #628's 100 B/px the gate passed at 5732 MiB available, and the render still
        // had lmkd reap 22 cached apps as MemAvailable fell to ~1.9 GB.
        assertFalse(RenderPayloads.prerenderMemoryAllows(5_732L * mib, threshold, 41_220_000L))
    }

    @Test
    fun theSamePhotoPrerendersWhenThereIsRoomForIt() {
        // 41.2 MP x 135 B + threshold + 512 MiB margin is ~6.0 GiB.
        assertTrue(RenderPayloads.prerenderMemoryAllows(6_600L * mib, threshold, 41_220_000L))
    }

    @Test
    fun anOrdinaryPhotoPrerendersAtTheMemoryAPhoneUsuallyHas() {
        // A 12 MP phone photo needs ~1.2 GB of the ~3 GB.
        assertTrue(RenderPayloads.prerenderMemoryAllows(3_000L * mib, threshold, 12_000_000L))
    }

    @Test
    fun theMarginAboveTheThresholdIsKept() {
        val pixels = 10_000_000L
        val need = pixels * RenderPayloads.PRERENDER_PEAK_BYTES_PER_PIXEL
        val exact = need + threshold + RenderPayloads.PRERENDER_MEMORY_MARGIN_BYTES
        assertTrue(RenderPayloads.prerenderMemoryAllows(exact, threshold, pixels))
        assertFalse(RenderPayloads.prerenderMemoryAllows(exact - 1, threshold, pixels))
    }

    @Test
    fun anUnknownSizeIsTreatedAsALargePhoneRaw() {
        assertFalse(
            RenderPayloads.prerenderMemoryAllows(3_000L * mib, threshold, RenderPayloads.PRERENDER_UNKNOWN_PIXELS),
        )
    }
}
