/*
 * Spektrafilm for Android — source-image rotation.
 *
 * Rotation is applied to the decoded [LinearImage] BEFORE it is handed to the engine,
 * so both the live preview render and the full-resolution export reflect the same
 * orientation. The engine's [LinearImage.data] is a direct, native-order ByteBuffer of
 * interleaved RGB float32 in row-major order: floatIndex = (y * width + x) * 3 + c.
 */
package com.spectrafilm.app

import androidx.exifinterface.media.ExifInterface
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

    /** Compose two clockwise rotations (this THEN [other]); returns the net step. */
    fun then(other: SourceRotation): SourceRotation =
        fromDegrees(degrees + other.degrees)

    companion object {
        fun fromDegrees(deg: Int): SourceRotation = when (((deg % 360) + 360) % 360) {
            90 -> CW90
            180 -> CW180
            270 -> CW270
            else -> NONE
        }
    }
}

/**
 * The decoded-image geometry op derived from an EXIF Orientation tag: a clockwise
 * [rotation] plus an optional horizontal [flipH] mirror. Covers all 8 TIFF/EXIF
 * orientation values. EXIF is applied to the decoded [LinearImage] as a baseline,
 * BEFORE the user's manual [SourceRotation] steps, so imports appear upright.
 */
data class ExifOrientation(val rotation: SourceRotation, val flipH: Boolean) {
    val isIdentity: Boolean get() = rotation == SourceRotation.NONE && !flipH

    companion object {
        val NONE = ExifOrientation(SourceRotation.NONE, false)

        /** Map an [ExifInterface.TAG_ORIENTATION] value to a rotation (+ optional H flip). */
        fun fromExif(orientation: Int): ExifOrientation = when (orientation) {
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_UNDEFINED -> NONE
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                ExifOrientation(SourceRotation.NONE, true)
            ExifInterface.ORIENTATION_ROTATE_180 ->
                ExifOrientation(SourceRotation.CW180, false)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                ExifOrientation(SourceRotation.CW180, true)
            // Transpose: mirror across the main diagonal = rotate 90 CW then flip H.
            ExifInterface.ORIENTATION_TRANSPOSE ->
                ExifOrientation(SourceRotation.CW90, true)
            ExifInterface.ORIENTATION_ROTATE_90 ->
                ExifOrientation(SourceRotation.CW90, false)
            // Transverse: mirror across the anti-diagonal = rotate 270 CW then flip H.
            ExifInterface.ORIENTATION_TRANSVERSE ->
                ExifOrientation(SourceRotation.CW270, true)
            ExifInterface.ORIENTATION_ROTATE_270 ->
                ExifOrientation(SourceRotation.CW270, false)
            else -> NONE
        }
    }
}

/**
 * Apply an [ExifOrientation] baseline to this image. The horizontal flip is applied
 * FIRST (in the source pixel grid), then the clockwise rotation, matching the EXIF
 * decode convention where the stored orientation describes how to upright the pixels.
 */
fun LinearImage.applyExif(orientation: ExifOrientation): LinearImage {
    if (orientation.isIdentity) return this
    val flipped = if (orientation.flipH) this.flippedHorizontal() else this
    return flipped.rotated(orientation.rotation)
}

/** Return a new [LinearImage] mirrored left-to-right (horizontal flip). */
fun LinearImage.flippedHorizontal(): LinearImage {
    val ch = 3
    val w = width
    val h = height
    val src = data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
    val outBuf = ByteBuffer.allocateDirect(w * h * ch * 4).order(ByteOrder.nativeOrder())
    val dst = outBuf.asFloatBuffer()
    for (y in 0 until h) {
        for (x in 0 until w) {
            val nx = w - 1 - x
            val s = (y * w + x) * ch
            val d = (y * w + nx) * ch
            dst.put(d, src.get(s))
            dst.put(d + 1, src.get(s + 1))
            dst.put(d + 2, src.get(s + 2))
        }
    }
    // This op allocated a fresh managed buffer, so the input is no longer needed. If it
    // owns an off-heap native buffer (a full-res export decode) free it now; close() is a
    // no-op for the common managed (allocateDirect) inputs.
    val flippedResult = LinearImage(outBuf, w, h, colorSpace)
    close()
    return flippedResult
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

    // Each non-NONE branch allocates a fresh managed buffer; free the input afterwards so
    // an off-heap full-res export source is reclaimed (no-op for managed inputs).
    val rotated = when (rotation) {
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
        SourceRotation.NONE -> this  // unreachable: NONE returned at the top
    }
    close()
    return rotated
}
