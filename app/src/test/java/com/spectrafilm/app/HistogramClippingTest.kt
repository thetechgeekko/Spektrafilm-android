package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The histogram's clipping wedges.
 *
 * The threshold is the whole design: a photograph of a street lamp at night has a handful of
 * genuinely blown pixels and is not a clipped photograph, so a marker with no threshold would
 * light on nearly every frame and mean nothing. These pin that it takes a real amount of the
 * frame, that a single stray pixel is not enough, and that an empty histogram cannot divide
 * by zero.
 */
class HistogramClippingTest {

    /** [inBin] pixels at [bin] on the red channel, the rest spread over the midtones. */
    private fun histogram(total: Int, bin: Int, inBin: Int): Histogram {
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        val luma = IntArray(256)
        r[bin] = inBin
        val rest = total - inBin
        if (rest > 0) {
            r[128] = rest
            g[128] = total
            b[128] = total
        }
        // luma carries the full frame; its bin total is the sampled pixel count.
        luma[128] = total
        return Histogram(r, g, b, luma, maxOf(total, inBin))
    }

    @Test
    fun aCleanFrameClipsNeither() {
        val h = histogram(total = 100_000, bin = 255, inBin = 0)
        assertFalse(h.highlightsClipped())
        assertFalse(h.shadowsClipped())
    }

    @Test
    fun aFewStrayBlownPixelsAreNotClipping() {
        // 50 of 100k is 0.05%, under the one-in-a-thousand bar — a specular highlight, not a
        // blown photograph.
        val h = histogram(total = 100_000, bin = 255, inBin = 50)
        assertFalse(h.highlightsClipped())
    }

    @Test
    fun arealBlownHighlightIsClipping() {
        // 500 of 100k is 0.5%.
        val h = histogram(total = 100_000, bin = 255, inBin = 500)
        assertTrue(h.highlightsClipped())
        assertFalse("only the top end was pinned", h.shadowsClipped())
    }

    @Test
    fun crushedShadowsAreDetectedIndependently() {
        val h = histogram(total = 100_000, bin = 0, inBin = 500)
        assertTrue(h.shadowsClipped())
        assertFalse("only the bottom end was pinned", h.highlightsClipped())
    }

    @Test
    fun anEmptyHistogramClipsNothingRatherThanDividingByZero() {
        val empty = Histogram(IntArray(256), IntArray(256), IntArray(256), IntArray(256), 0)
        assertFalse(empty.highlightsClipped())
        assertFalse(empty.shadowsClipped())
    }

    @Test
    fun clippingOnAnySingleChannelCounts() {
        // A blown red sky with green and blue still in range is still clipped: the marker is
        // about losing data, and losing one channel loses colour.
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        val luma = IntArray(256)
        r[255] = 5_000
        g[200] = 100_000
        b[200] = 100_000
        luma[200] = 100_000
        assertTrue(Histogram(r, g, b, luma, 100_000).highlightsClipped())
    }
}
