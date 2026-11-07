package com.spectrafilm.app

import com.spectrafilm.engine.LinearImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockPreviewQueueTest {
    @Test fun newestRequestIsServedFirst() {
        val q = StockPreviewQueue<String>()
        q.bind("look")
        q.request("a"); q.request("b"); q.request("c")
        assertEquals("c", q.next()?.stockId)
        q.request("a") // scrolled back up: a jumps ahead of b
        assertEquals("a", q.next()?.stockId)
        assertEquals("b", q.next()?.stockId)
        assertNull(q.next())
    }

    @Test fun finishedAndInFlightTilesAreNotRequeued() {
        val q = StockPreviewQueue<String>()
        q.bind("look")
        q.request("a")
        val work = q.next()!!
        q.request("a")
        assertNull("in flight", q.next())
        assertTrue(q.complete(work, "tile-a"))
        q.request("a")
        assertNull("already rendered", q.next())
        assertEquals(mapOf("a" to "tile-a"), q.done)
        assertTrue(q.waiting().isEmpty())
    }

    @Test fun sameKeyKeepsTilesAndNewKeyRerendersThem() {
        val q = StockPreviewQueue<String>()
        q.bind(listOf("src", 1))
        q.request("a")
        q.complete(q.next()!!, "old-a")
        assertTrue(q.bind(listOf("src", 1)).isEmpty())
        assertEquals("old-a", q.done["a"])

        val stale = q.bind(listOf("src", 2))
        assertEquals(listOf("old-a"), stale)
        assertTrue(q.done.isEmpty())
        assertEquals(setOf("a"), q.waiting())
        assertEquals("a", q.next()?.stockId)
    }

    @Test fun aRenderFromAnOlderLookIsRejected() {
        val q = StockPreviewQueue<String>()
        q.bind("look-1")
        q.request("a")
        val old = q.next()!!
        q.bind("look-2")
        assertFalse(q.complete(old, "stale"))
        assertTrue(q.done.isEmpty())
        val fresh = q.next()!!
        assertEquals("a", fresh.stockId)
        assertTrue(q.complete(fresh, "fresh"))
        assertEquals("fresh", q.done["a"])
    }

    @Test fun requestsBeforeTheFirstSessionAreKept() {
        val q = StockPreviewQueue<String>()
        q.request("a") // a card composed before the sheet's session was built
        q.bind("look")
        assertEquals("a", q.next()?.stockId)
    }

    @Test fun closingMidRenderPutsTheTileBack() {
        val q = StockPreviewQueue<String>()
        q.bind("look")
        q.request("a")
        q.next()
        q.requeueInFlight()
        assertEquals(setOf("a"), q.waiting())
        assertEquals("a", q.next()?.stockId)
    }

    @Test fun aFailedTileCanBeAskedForAgain() {
        val q = StockPreviewQueue<String>()
        q.bind("look")
        q.request("a")
        q.fail(q.next()!!)
        assertTrue(q.waiting().isEmpty())
        q.request("a")
        assertEquals("a", q.next()?.stockId)
    }
}

class DownsampleLinearBoxTest {
    private fun image(w: Int, h: Int, value: (x: Int, y: Int, c: Int) -> Float): LinearImage {
        val b = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
        val f = b.asFloatBuffer()
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) f.put(value(x, y, c))
        return LinearImage(b, w, h)
    }

    private fun pixels(img: LinearImage): FloatArray = img.acquireDataLease().use { lease ->
        val f = lease.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        FloatArray(img.width * img.height * 3).also { f.get(it) }
    }

    @Test fun averagesWholeBlocksInLinearLight() {
        // 4x2, a bright and a dark column pair: a 2x box must average, not pick a pixel.
        val src = image(4, 2) { x, _, c -> if (x % 2 == 0) 1f + c else 0f }
        val out = downsampleLinearBox(src, 2)
        assertEquals(2, out.width)
        assertEquals(1, out.height)
        val p = pixels(out)
        for (px in 0 until 2) for (c in 0 until 3) assertEquals((1f + c) / 2f, p[px * 3 + c], 1e-6f)
    }

    @Test fun fitsWithinTheEdgeAndKeepsAspect() {
        val src = image(1000, 600) { _, _, _ -> 0.18f }
        val out = downsampleLinearBox(src, 320)
        assertTrue(out.width <= 320 && out.height <= 320)
        assertEquals(250, out.width) // step 4
        assertEquals(150, out.height)
        assertTrue(pixels(out).all { kotlin.math.abs(it - 0.18f) < 1e-6f })
    }

    @Test fun smallImagesAreCopiedUnchanged() {
        val src = image(3, 2) { x, y, c -> x + 10f * y + 100f * c }
        val out = downsampleLinearBox(src, 320)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        assertEquals(pixels(src).toList(), pixels(out).toList())
    }

    @Test fun aStripThinnerThanOneBlockDoesNotReadPastTheRow() {
        val src = image(1, 1000) { _, y, _ -> if (y < 500) 1f else 0f }
        val out = downsampleLinearBox(src, 100)
        assertEquals(1, out.width)
        assertEquals(100, out.height)
        assertEquals(1f, pixels(out)[0], 1e-6f)
        assertEquals(0f, pixels(out)[(99) * 3], 1e-6f)
    }
}
