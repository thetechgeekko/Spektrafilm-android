/*
 * Spektrafilm for Android — platform-decoder subsample choice. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * ImageDecoder only subsamples by integer (in practice power-of-two) factors, so the
 * factor alone cannot land on an arbitrary maxEdge. decodeViaPlatform pairs it with an
 * exact downscale afterwards, which means the factor's job is to land at or ABOVE the
 * target, never below it: anything below is resolution that the exact downscale can no
 * longer recover.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformSampleSizeTest {

    @Test
    fun landsAboveTheTargetSoTheExactDownscaleCanFinish() {
        // The real case: a 4080px DNG previewed at 640. Stopping at the first
        // factor that is UNDER the target picked 8 (510px) and the exact downscale then
        // had nothing to do, so the preview shipped 20% softer than it was asked for.
        assertEquals(4, platformSampleSize(longest = 4080, maxEdge = 640))
        assertEquals(1020, 4080 / platformSampleSize(4080, 640))
    }

    @Test
    fun neverOvershootsIntoAPointlesslyLargeIntermediate() {
        // The bitmap this feeds is ARGB_8888 and is downscaled immediately, so the
        // factor must still be the LARGEST one that clears the target.
        assertEquals(2, platformSampleSize(longest = 1600, maxEdge = 640))
        assertEquals(8, platformSampleSize(longest = 8000, maxEdge = 640))
    }

    @Test
    fun anExactPowerOfTwoMatchIsTakenRatherThanHalved() {
        assertEquals(2, platformSampleSize(longest = 1280, maxEdge = 640))
        assertEquals(1280 / 2, 640)
    }

    @Test
    fun aSourceAlreadyAtOrUnderTheTargetIsNotSubsampled() {
        assertEquals(1, platformSampleSize(longest = 640, maxEdge = 640))
        assertEquals(1, platformSampleSize(longest = 500, maxEdge = 640))
        assertEquals(1, platformSampleSize(longest = 1, maxEdge = 640))
    }

    @Test
    fun degenerateInputsDoNotProduceAZeroOrNegativeFactor() {
        assertEquals(1, platformSampleSize(longest = 0, maxEdge = 640))
        assertEquals(1, platformSampleSize(longest = 4080, maxEdge = 0))
    }
}
