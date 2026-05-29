/*
 * SpectraFilm for Android — lib:tiffwriter Kotlin facade.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 *
 * Writes a baseline 16-bit-per-channel RGB TIFF (TIFF 6.0, little-endian) with an
 * optional embedded ICC profile and basic EXIF/TIFF metadata, for the M2 export
 * path. The native writer (libsftiff.so) is dependency-free; see tiff_writer.cpp.
 *
 * Pixel input is 16-bit RGB, interleaved R,G,B, row-major, width*height*3 samples.
 * This is the natural quantisation of the engine's display-referred float output
 * (SimResult / spk_image in the caller's chosen output color space). The caller
 * picks the matching ICC profile (sRGB / Display-P3 / ProPhoto / …) and passes its
 * bytes; this module does not bundle profiles.
 *
 * NOTE: app/UI wiring (choosing the profile asset, output Uri, threading) is a
 * later wave; this facade is the stable callable surface for it.
 */
package com.spectrafilm.tiffwriter

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EXIF ColorSpace tag values written into the EXIF sub-IFD. The authoritative
 * color definition is the embedded ICC profile; this is an advisory hint.
 */
enum class ExifColorSpace(val tagValue: Int) {
    /** EXIF ColorSpace = 1 (sRGB). Use when output space is sRGB. */
    SRGB(1),

    /** EXIF ColorSpace = 0xFFFF (Uncalibrated): any wide-gamut / non-sRGB space. */
    UNCALIBRATED(0xFFFF),
}

object TiffWriter {

    /**
     * Write a 16-bit RGB TIFF from a direct [ByteBuffer] of little-endian uint16
     * samples (length = width*height*3*2 bytes). Fastest path: no per-pixel copy.
     *
     * @param rgb16    direct ByteBuffer, width*height*3 little-endian uint16 samples
     * @param width    image width in pixels
     * @param height   image height in pixels
     * @param outPath  absolute filesystem path to write
     * @param icc      optional ICC profile bytes (null/empty => no ICCProfile tag)
     * @param exifColorSpace advisory EXIF ColorSpace hint
     * @param software Software/producer string (TIFF tag 305)
     * @param dateTime optional "YYYY:MM:DD HH:MM:SS" DateTime (tag 306); null => omit
     * @param packBits true => PackBits (lossless RLE); false => uncompressed
     * @return number of bytes written
     * @throws IllegalStateException on write failure
     */
    fun write(
        rgb16: ByteBuffer,
        width: Int,
        height: Int,
        outPath: String,
        icc: ByteArray? = null,
        exifColorSpace: ExifColorSpace = ExifColorSpace.UNCALIBRATED,
        software: String = "SpectraFilm",
        dateTime: String? = null,
        packBits: Boolean = false,
    ): Long {
        val direct = if (rgb16.isDirect) {
            rgb16
        } else {
            ByteBuffer.allocateDirect(rgb16.remaining()).order(ByteOrder.LITTLE_ENDIAN)
                .also { it.put(rgb16.duplicate()); it.flip() }
        }
        return nativeWriteBuffer(
            direct, width, height, exifColorSpace.tagValue,
            software, dateTime, icc, packBits, outPath,
        )
    }

    /**
     * Write a 16-bit RGB TIFF from a [ShortArray] of width*height*3 samples
     * (interpreted as unsigned 16-bit). Convenience overload.
     */
    fun write(
        rgb16: ShortArray,
        width: Int,
        height: Int,
        outPath: String,
        icc: ByteArray? = null,
        exifColorSpace: ExifColorSpace = ExifColorSpace.UNCALIBRATED,
        software: String = "SpectraFilm",
        dateTime: String? = null,
        packBits: Boolean = false,
    ): Long = nativeWriteShorts(
        rgb16, width, height, exifColorSpace.tagValue,
        software, dateTime, icc, packBits, outPath,
    )

    // --- native bridge (tiff_writer_jni.cpp) ---
    private external fun nativeWriteBuffer(
        rgb16: ByteBuffer, width: Int, height: Int, exifColorSpace: Int,
        software: String, dateTime: String?, icc: ByteArray?,
        packBits: Boolean, outPath: String,
    ): Long

    private external fun nativeWriteShorts(
        rgb16: ShortArray, width: Int, height: Int, exifColorSpace: Int,
        software: String, dateTime: String?, icc: ByteArray?,
        packBits: Boolean, outPath: String,
    ): Long

    init {
        System.loadLibrary("sftiff")
    }
}
