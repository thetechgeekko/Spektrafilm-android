/*
 * Spektrafilm for Android — zoom/magnifier frame-exposure regressions. GPLv3.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.LinearImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Zooming used to change the photo's brightness: the zoom patch and the magnifier rendered their
 * crop with auto-exposure on, so the engine metered only the zoomed region. They now fold the
 * whole frame's gain into the crop and render it with auto-exposure off.
 */
class ZoomFrameExposureTest {
    private fun image(w: Int, h: Int, values: FloatArray): LinearImage {
        val buf = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(values)
        return LinearImage(buf, w, h)
    }

    private fun LinearImage.floats(): FloatArray = acquireDataLease().use { lease ->
        val fb = lease.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        FloatArray(width * height * 3) { fb.get(it) }
    }

    @Test
    fun cropRendersNeverMeterTheCropThemselves() {
        val state = ParamsState().apply {
            autoExposure = true
            autoExposureMethod = "matrix"
        }
        val snapshot = state.captureInteractiveRenderSnapshot(renderKey = 3)
        assertFalse(snapshot.paramsForCrop(cropFraction = 0.25f).camera.autoExposure)
        assertFalse(
            snapshot.paramsForCrop(cropFraction = 0.25f, previewMaxSize = 640).camera.autoExposure,
        )
        // The whole-frame params keep it, so the frame meter still knows the method.
        assertEquals(true, snapshot.params.camera.autoExposure)
        assertEquals("matrix", snapshot.params.camera.autoExposureMethod)
    }

    @Test
    fun cropFoldsTheFrameGainIntoEveryCopiedValue() {
        // 3x2 RGB ramp; cut the right 2x2 box at gain 2.
        val src = image(3, 2, FloatArray(18) { it.toFloat() })
        cropLinearImageBox(src, CropBox(1, 0, 2, 2), gain = 2f).use { crop ->
            assertEquals(2, crop.width)
            assertEquals(2, crop.height)
            val expected = floatArrayOf(
                3f, 4f, 5f, 6f, 7f, 8f,
                12f, 13f, 14f, 15f, 16f, 17f,
            ).map { it * 2f }.toFloatArray()
            val got = crop.floats()
            for (i in expected.indices) assertEquals(expected[i], got[i], 0f)
        }
        // Unity gain is still a plain copy.
        cropLinearImageBox(src, CropBox(0, 0, 1, 1)).use { crop ->
            val got = crop.floats()
            assertEquals(0f, got[0], 0f); assertEquals(1f, got[1], 0f); assertEquals(2f, got[2], 0f)
        }
    }

    @Test
    fun cropRejectsANonPositiveGain() {
        val src = image(1, 1, floatArrayOf(1f, 1f, 1f))
        assertThrows(IllegalArgumentException::class.java) {
            cropLinearImageBox(src, CropBox(0, 0, 1, 1), gain = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cropLinearImageBox(src, CropBox(0, 0, 1, 1), gain = Float.NaN)
        }
    }

    @Test
    fun frameMeterIsReusedOnlyForTheSameSourceAndSettings() {
        val a = image(1, 1, floatArrayOf(1f, 1f, 1f))
        val b = image(1, 1, floatArrayOf(1f, 1f, 1f))
        val key = FrameExposureKey(method = "center_weighted", inputCctfDecoding = false)
        FrameExposureCache.store(a, key, 1.7f)
        assertEquals(1.7f, FrameExposureCache.lookup(a, key)!!, 0f)
        assertNull(FrameExposureCache.lookup(b, key))
        assertNull(FrameExposureCache.lookup(a, key.copy(method = "matrix")))
        assertNull(FrameExposureCache.lookup(a, key.copy(inputCctfDecoding = true)))
    }
}
