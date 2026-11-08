/*
 * Spektrafilm for Android — unit tests for free-angle straighten. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.LinearImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StraightenTest {

    private fun image(w: Int, h: Int, fill: (x: Int, y: Int, c: Int) -> Float): LinearImage {
        val b = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
        val f = b.asFloatBuffer()
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            f.put((y * w + x) * 3 + c, fill(x, y, c))
        }
        return LinearImage(b, w, h)
    }

    private fun noise(w: Int, h: Int): LinearImage {
        var s = 777
        return image(w, h) { _, _, _ ->
            s = s * 1103515245 + 12345
            ((s ushr 8) and 0xFFFF) / 65535f
        }
    }

    private fun floatsOf(img: LinearImage): FloatArray = img.acquireDataLease().use { lease ->
        FloatArray(img.width * img.height * 3).also { lease.data.asFloatBuffer().get(it) }
    }

    @Test
    fun zero_returnsSameInstance() {
        val img = noise(8, 6)
        assertSame(img, img.straightened(0f))
    }

    @Test
    fun size_keepsAspectAndShrinks() {
        assertEquals(4000 to 3000, straightenedSize(4000, 3000, 0f))
        for (deg in listOf(0.5f, -3f, 10f, -45f)) {
            val (w, h) = straightenedSize(4000, 3000, deg)
            assertTrue("smaller at $deg", w < 4000 && h < 3000)
            assertEquals("aspect at $deg", 4000.0 / 3000, w.toDouble() / h, 0.002)
        }
        // Symmetric in sign.
        assertEquals(straightenedSize(4000, 3000, 7f), straightenedSize(4000, 3000, -7f))
    }

    @Test
    fun output_fitsInsideTheRotatedFrame() {
        // Every corner of the output, turned back into source space, must land inside it.
        for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 1000 to 1000, 6000 to 1000)) {
            for (deg in listOf(1f, -5f, 12.5f, 30f, -45f)) {
                val (ow, oh) = straightenedSize(w, h, deg)
                val r = Math.toRadians(deg.toDouble())
                for ((u, v) in listOf(
                    -ow / 2.0 to -oh / 2.0, ow / 2.0 to -oh / 2.0,
                    -ow / 2.0 to oh / 2.0, ow / 2.0 to oh / 2.0,
                )) {
                    val sx = u * cos(r) + v * sin(r)
                    val sy = -u * sin(r) + v * cos(r)
                    assertTrue("x in frame ${w}x$h @$deg", abs(sx) <= w / 2.0 + 1e-6)
                    assertTrue("y in frame ${w}x$h @$deg", abs(sy) <= h / 2.0 + 1e-6)
                }
            }
        }
    }

    @Test
    fun flatImage_staysFlat() {
        val out = image(64, 48) { _, _, c -> 0.25f * (c + 1) }.straightened(8f)
        val f = floatsOf(out)
        for (i in f.indices) assertEquals(0.25f * (i % 3 + 1), f[i], 1e-6f)
    }

    @Test
    fun linearRamp_isRotatedClockwise() {
        // r = x: after a clockwise turn, moving DOWN the output's centre column walks the
        // source to the right, so r increases downwards and is constant-ish across the row.
        val w = 201; val h = 201
        val out = image(w, h) { x, _, _ -> x.toFloat() }.straightened(10f)
        val f = floatsOf(out)
        val ow = out.width; val oh = out.height
        val cx = ow / 2
        val topR = f[(2 * ow + cx) * 3]
        val botR = f[((oh - 3) * ow + cx) * 3]
        assertTrue("clockwise: r grows downward ($topR -> $botR)", botR > topR + 10f)
        // Centre pixel samples the source centre.
        assertEquals(w / 2f, f[((oh / 2) * ow + ow / 2) * 3], 1f)
    }

    @Test
    fun workerCount_doesNotMoveAByte() {
        for (deg in listOf(3.3f, -17f, 45f)) {
            val one = floatsOf(noise(301, 203).straightenedWithWorkers(deg, 1))
            val many = floatsOf(noise(301, 203).straightenedWithWorkers(deg, 8))
            assertArrayEquals("deg=$deg", one, many, 0f)
        }
    }

    @Test
    fun ruler_movesWithTheFinger_snaps_andSticksToLevel() {
        val px = 10f
        // Dragging left (negative dx) brings larger angles under the needle.
        assertEquals(2f, straightenRulerValue(0f, -20f, px), 1e-6f)
        assertEquals(-1.5f, straightenRulerValue(0f, 15f, px), 1e-6f)
        // Snaps to tenths.
        assertEquals(1.2f, straightenRulerValue(0f, -12.3f, px), 1e-6f)
        // Within the detent of level it lands on exactly 0.
        assertEquals(0f, straightenRulerValue(0f, -2f, px), 0f)
        assertEquals(0f, straightenRulerValue(1f, 8f, px), 0f)
        // Clamped to the range.
        assertEquals(STRAIGHTEN_MAX_DEGREES, straightenRulerValue(40f, -1000f, px), 0f)
    }

    @Test
    fun format_usesSignAndOneDecimal() {
        assertEquals("+2.5\u00B0", formatStraighten(2.5f))
        assertEquals("\u22121.0\u00B0", formatStraighten(-1f))
        assertEquals("0.0\u00B0", formatStraighten(0f))
    }

    @Test
    fun clamp_rejectsNonFinite() {
        assertEquals(0f, clampStraighten(Float.NaN), 0f)
        assertEquals(0f, clampStraighten(Float.POSITIVE_INFINITY), 0f)
        assertEquals(45f, clampStraighten(90f), 0f)
        assertEquals(-45f, clampStraighten(-90f), 0f)
        assertEquals(2.5f, clampStraighten(2.5f), 0f)
    }

    @Test
    fun cancellation_closesConsumedInput() {
        val closes = AtomicInteger(0)
        val plain = noise(16, 16)
        val owned = plain.acquireDataLease().use { lease ->
            LinearImage.fromDataLease(
                lease.data, plain.width, plain.height,
                lease = AutoCloseable { closes.incrementAndGet() },
            )
        }
        try {
            owned.straightenedWithWorkers(5f, 1) { true }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, closes.get())
    }
}
