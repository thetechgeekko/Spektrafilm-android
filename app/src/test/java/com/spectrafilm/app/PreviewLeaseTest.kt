package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview-retirement contract that `rememberLeasedImage` depends on.
 *
 * `retirePreviewBitmap` is called from `DisposableEffect(preview)` the instant a new frame
 * replaces the old one, while the Compose draw pass may still be about to read the outgoing
 * bitmap. Before the lease was wired into composition, only `computeHistogram` ever acquired
 * one, so a render settling between composition and draw recycled pixel storage out from
 * under the draw pass. These assert the deferral that makes that safe.
 *
 * Uses a fake retirable value rather than a real Bitmap: the registry is generic over its
 * retire hooks, and `android.graphics.Bitmap` is a throwing stub in plain JVM unit tests.
 */
class PreviewLeaseTest {

    private class Frame {
        var retired = false
    }

    private fun registry(): RetirableReadLeaseRegistry<Frame> =
        RetirableReadLeaseRegistry(
            isPhysicallyRetired = { it.retired },
            physicallyRetire = { it.retired = true },
        )

    @Test
    fun retireWhileLeasedDefersUntilTheLastReaderCloses() {
        val reg = registry()
        val frame = Frame()

        val lease = requireNotNull(reg.acquire(frame)) { "a live frame must be leasable" }

        reg.retire(frame)
        assertFalse("retire must not recycle while a reader holds the frame", frame.retired)

        lease.close()
        assertTrue("the last close must complete the deferred retire", frame.retired)
    }

    @Test
    fun twoReadersBothHaveToCloseBeforeTheFrameGoes() {
        val reg = registry()
        val frame = Frame()

        val a = requireNotNull(reg.acquire(frame))
        val b = requireNotNull(reg.acquire(frame))
        reg.retire(frame)

        a.close()
        assertFalse("one reader closing is not enough", frame.retired)
        b.close()
        assertTrue(frame.retired)
    }

    @Test
    fun aRetiredFrameIsNotLeasableAgain() {
        val reg = registry()
        val frame = Frame()

        reg.retire(frame)
        assertTrue(frame.retired)
        assertNull("composition must get null and draw nothing", reg.acquire(frame))
    }

    @Test
    fun closingTwiceReleasesOnce() {
        val reg = registry()
        val frame = Frame()

        val a = requireNotNull(reg.acquire(frame))
        val b = requireNotNull(reg.acquire(frame))
        a.close()
        // A double close that decremented twice would retire the frame while `b` still reads it.
        a.close()
        reg.retire(frame)
        assertFalse("a repeated close must not drop another reader's lease", frame.retired)

        b.close()
        assertTrue(frame.retired)
    }
}
