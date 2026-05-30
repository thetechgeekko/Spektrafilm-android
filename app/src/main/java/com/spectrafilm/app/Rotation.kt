/*
 * SpectraFilm for Android — source-image rotation.
 *
 * Rotation is applied to the decoded [LinearImage] BEFORE it is handed to the engine,
 * so both the live preview render and the full-resolution export reflect the same
 * orientation. The engine's [LinearImage.data] is a direct, native-order ByteBuffer of
 * interleaved RGB float32 in row-major order: floatIndex = (y * width + x) * 3 + c.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.LinearImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Clockwise rotation applied to the source before simulation. */
enum class SourceRotation(val degrees: Int) {
    NONE(0), CW90(90), CW180(180), CW270(270);

    /** Next 90-degree clockwise step. */
    fun next(): SourceRotation = when (this) {
        NONE -> CW90
        CW90 -> CW180
        CW180 -> CW270
        CW270 -> NONE
    }
}

/**
 * Return a new [LinearImage] rotated clockwise by [rotation]. [NONE] returns the input
 * unchanged (no copy). 90/270 swap width and height. Operates on the float view of the
 * native ByteBuffer.
 */
fun LinearImage.rotated(rotation: SourceRotation): LinearImage {
    if (rotation == SourceRotation.NONE) return this
    val ch = 3
    val w = width
    val h = height
    val src = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun newBuffer(n: Int): ByteBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())

    return when (rotation) {
        SourceRotation.CW90 -> {
            val nw = h
            val nh = w
            val outBuf = newBuffer(nw * nh * ch)
            val dst = outBuf.asFloatBuffer()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val nx = h - 1 - y
                    val ny = x
                    val s = (y * w + x) * ch
                    val d = (ny * nw + nx) * ch
                    dst.put(d, src.get(s))
                    dst.put(d + 1, src.get(s + 1))
                    dst.put(d + 2, src.get(s + 2))
                }
            }
            LinearImage(outBuf, nw, nh, colorSpace)
        }
        SourceRotation.CW180 -> {
            val outBuf = newBuffer(w * h * ch)
            val dst = outBuf.asFloatBuffer()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val nx = w - 1 - x
                    val ny = h - 1 - y
                    val s = (y * w + x) * ch
                    val d = (ny * w + nx) * ch
                    dst.put(d, src.get(s))
                    dst.put(d + 1, src.get(s + 1))
                    dst.put(d + 2, src.get(s + 2))
                }
            }
            LinearImage(outBuf, w, h, colorSpace)
        }
        SourceRotation.CW270 -> {
            val nw = h
            val nh = w
            val outBuf = newBuffer(nw * nh * ch)
            val dst = outBuf.asFloatBuffer()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val nx = y
                    val ny = w - 1 - x
                    val s = (y * w + x) * ch
                    val d = (ny * nw + nx) * ch
                    dst.put(d, src.get(s))
                    dst.put(d + 1, src.get(s + 1))
                    dst.put(d + 2, src.get(s + 2))
                }
            }
            LinearImage(outBuf, nw, nh, colorSpace)
        }
        SourceRotation.NONE -> this
    }
}
