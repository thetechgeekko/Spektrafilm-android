/*
 * SpectraFilm for Android — lib:libraw Kotlin facade.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 * Uses LibRaw (LGPL-2.1).
 *
 * Decodes a camera RAW / DNG into a linear, scene-referred float32 RGB buffer with
 * bit-parity to spektrafilm's desktop rawpy settings (see docs/RAW_DNG.md and
 * spektrafilm/utils/raw_file_processor.py):
 *   output_color = ACES (ACES2065-1), output_bps = 16, no_auto_bright,
 *   gamma = (1,1) [linear], white balance per WhiteBalance below.
 *
 * The result's direct ByteBuffer can be handed straight to the engine as a
 * LinearImage (primary integration point) with no intermediate 8-bit bitmap.
 */
package com.spectrafilm.libraw

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * White-balance modes, mirroring raw_file_processor.py:
 *  - [AS_SHOT]  uses LibRaw camera WB during demosaic (`use_camera_wb`).
 *  - [DAYLIGHT] uses LibRaw's daylight-balanced base output, no adaptation.
 *  - [TUNGSTEN] adapts a 2850 K scene white to the 6504 K reference (tint = 1.0).
 *  - [CUSTOM]   adapts [temperatureK] -> 6504 K with a green/magenta [tint],
 *               via Von-Kries chromatic adaptation in linear ACES RGB.
 *
 * [nativeMode] ordinals must match the switch in raw_decoder_jni.cpp.
 */
enum class WhiteBalance(val nativeMode: Int) {
    AS_SHOT(0),
    DAYLIGHT(1),
    TUNGSTEN(2),
    CUSTOM(3);

    companion object {
        /** Custom white balance from a colour temperature (K) and green tint. */
        fun custom(temperatureK: Double, tint: Double = 1.0): RawDecoder.Settings =
            RawDecoder.Settings(CUSTOM, temperatureK, tint)
    }
}

/**
 * Linear, scene-referred decode result. [data] is a *direct* float32 RGB
 * ByteBuffer (length = width*height*3*4 bytes), matching engine LinearImage. The
 * caller can construct `LinearImage(data, width, height, colorSpace)` directly.
 */
class LinearResult(
    val data: ByteBuffer,
    val width: Int,
    val height: Int,
    val colorSpace: String = "ACES2065-1",
)

object RawDecoder {

    /** Decode settings. Temperature/tint are only used for [WhiteBalance.CUSTOM]. */
    data class Settings(
        val whiteBalance: WhiteBalance = WhiteBalance.AS_SHOT,
        val temperatureK: Double = 6504.0,
        val tint: Double = 1.0,
    )

    /** Constructed by the native layer (raw_decoder_jni.cpp). */
    @Suppress("unused")
    internal class NativeResult(
        @JvmField val data: ByteBuffer,
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val colorSpace: String,
    )

    private val RAW_EXTENSIONS = setOf(
        "dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2",
    )

    /** True if [extension] (with or without a leading dot) is a supported RAW type. */
    fun isRawFile(extension: String): Boolean =
        extension.trimStart('.').lowercase(Locale.ROOT) in RAW_EXTENSIONS

    /** Convenience for full file names / paths / Uris ending in a RAW extension. */
    fun isRawFileName(name: String): Boolean =
        isRawFile(name.substringAfterLast('.', missingDelimiterValue = ""))

    /** Decode a fully-read RAW/DNG byte array to linear ACES RGB. */
    fun decodeToLinear(bytes: ByteArray, settings: Settings = Settings()): LinearResult =
        nativeDecodeBytes(
            bytes, settings.whiteBalance.nativeMode, settings.temperatureK, settings.tint,
        ).toLinear()

    /**
     * Decode from a direct ByteBuffer (zero input copy). If [input] is not direct
     * it is copied into a direct buffer first.
     */
    fun decodeToLinear(input: ByteBuffer, settings: Settings = Settings()): LinearResult {
        val direct = if (input.isDirect) {
            input
        } else {
            ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.nativeOrder())
                .also { it.put(input.duplicate()); it.flip() }
        }
        return nativeDecodeBuffer(
            direct, direct.remaining(),
            settings.whiteBalance.nativeMode, settings.temperatureK, settings.tint,
        ).toLinear()
    }

    /**
     * Decode from a file descriptor (e.g. `ParcelFileDescriptor.detachFd()` from a
     * SAF Uri). The fd is duplicated natively; the caller retains ownership.
     */
    fun decodeToLinear(fd: Int, settings: Settings = Settings()): LinearResult =
        nativeDecodeFd(
            fd, settings.whiteBalance.nativeMode, settings.temperatureK, settings.tint,
        ).toLinear()

    /** Decode by reading an InputStream fully into memory (SAF convenience). */
    fun decodeToLinear(stream: InputStream, settings: Settings = Settings()): LinearResult =
        decodeToLinear(stream.readBytes(), settings)

    private fun NativeResult.toLinear(): LinearResult {
        // Native gives little-/native-endian float32; tag the byte order so callers
        // reading as a FloatBuffer get correct values.
        data.order(ByteOrder.nativeOrder())
        return LinearResult(data, width, height, colorSpace)
    }

    // --- native bridge (raw_decoder_jni.cpp) ---
    private external fun nativeDecodeBytes(
        bytes: ByteArray, wbMode: Int, temperatureK: Double, tint: Double,
    ): NativeResult

    private external fun nativeDecodeBuffer(
        buffer: ByteBuffer, len: Int, wbMode: Int, temperatureK: Double, tint: Double,
    ): NativeResult

    private external fun nativeDecodeFd(
        fd: Int, wbMode: Int, temperatureK: Double, tint: Double,
    ): NativeResult

    init {
        System.loadLibrary("sfraw")
    }
}
