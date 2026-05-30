/*
 * SpectraFilm for Android — lib:pngwriter Kotlin facade.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 *
 * Writes a 16-bit-per-channel RGB PNG (bit_depth=16, color_type=2, filter=None,
 * zlib-deflated IDAT) with an optional embedded iCCP chunk and tEXt Software
 * tag. The native writer (libsfpng.so) depends only on the system zlib; see
 * png_writer.cpp for the full spec-compliance notes.
 *
 * Pixel input is 16-bit RGB, interleaved R,G,B, row-major, width*height*3
 * samples in the engine's native little-endian byte order. The writer byte-
 * swaps to big-endian before deflating, as required by the PNG spec (RFC 2083
 * §2.3). Sample values in the output range from 0 (black) to 65535 (white).
 *
 * NOTE: app/UI wiring (output Uri, threading, color-space/ICC selection) is a
 * later wave; this facade is the stable callable surface for it.
 */
package com.spectrafilm.pngwriter

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PngWriter {

    /**
     * Write a 16-bit RGB PNG from a direct [ByteBuffer] of little-endian uint16
     * samples (length = width*height*3*2 bytes). Fastest path: no per-pixel copy.
     *
     * The engine's display-referred float output should be quantised to uint16
     * ([0,1] → [0,65535], round-to-nearest) before calling. The writer byte-swaps
     * to big-endian internally; the caller never needs to think about byte order.
     *
     * @param rgb16     direct ByteBuffer, width*height*3 little-endian uint16 samples
     * @param width     image width in pixels
     * @param height    image height in pixels
     * @param outPath   absolute filesystem path to write
     * @param icc       optional raw ICC profile bytes (null/empty => no iCCP chunk)
     * @param software  producer string written as tEXt "Software" tag; empty => omit
     * @return number of bytes written
     * @throws IllegalStateException on any write failure
     */
    fun write(
        rgb16: ByteBuffer,
        width: Int,
        height: Int,
        outPath: String,
        icc: ByteArray? = null,
        software: String = "SpectraFilm",
    ): Long {
        val direct = if (rgb16.isDirect) {
            rgb16
        } else {
            ByteBuffer.allocateDirect(rgb16.remaining())
                .order(ByteOrder.LITTLE_ENDIAN)
                .also { it.put(rgb16.duplicate()); it.flip() }
        }
        return nativeWriteBuffer(direct, width, height, software, icc, outPath)
    }

    /**
     * Write a 16-bit RGB PNG from a [ShortArray] of width*height*3 samples
     * (interpreted as unsigned 16-bit, little-endian). Convenience overload for
     * callers that already have a short[].
     */
    fun write(
        rgb16: ShortArray,
        width: Int,
        height: Int,
        outPath: String,
        icc: ByteArray? = null,
        software: String = "SpectraFilm",
    ): Long = nativeWriteShorts(rgb16, width, height, software, icc, outPath)

    /**
     * Write a 16-bit RGB PNG from a float RGB buffer quantised to uint16.
     *
     * `rgbFloat` is width*height*3 float samples in [0,1] (values outside are
     * clamped). Quantisation is round-to-nearest over [0,65535]. Convenience
     * overload that matches the TIFF writer's `writeTiffFloatToFile` signature
     * so the two can be used interchangeably from the export layer.
     *
     * This overload builds a temporary uint16 buffer on the JVM and delegates to
     * [write]; for large images callers may prefer to quantise in the engine and
     * pass a pre-built uint16 buffer to avoid the extra allocation.
     */
    fun writeFloat(
        rgbFloat: FloatArray,
        width: Int,
        height: Int,
        outPath: String,
        icc: ByteArray? = null,
        software: String = "SpectraFilm",
    ): Long {
        val n = width.toLong() * height.toLong() * 3L
        require(rgbFloat.size >= n) { "float buffer too small for $width x $height x 3" }
        val buf = ByteBuffer.allocateDirect((n * 2L).toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        val sBuf = buf.asShortBuffer()
        for (i in 0 until n.toInt()) {
            val v = rgbFloat[i].coerceIn(0f, 1f)
            sBuf.put((v * 65535f + 0.5f).toInt().toShort())
        }
        buf.rewind()
        return nativeWriteBuffer(buf, width, height, software, icc, outPath)
    }

    // --- native bridge (png_writer_jni.cpp / libsfpng.so) ---
    private external fun nativeWriteBuffer(
        rgb16: ByteBuffer,
        width: Int,
        height: Int,
        software: String,
        icc: ByteArray?,
        outPath: String,
    ): Long

    private external fun nativeWriteShorts(
        rgb16: ShortArray,
        width: Int,
        height: Int,
        software: String,
        icc: ByteArray?,
        outPath: String,
    ): Long

    init {
        System.loadLibrary("sfpng")
    }
}
