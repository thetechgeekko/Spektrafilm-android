/*
 * Spektrafilm for Android — lib:libraw Kotlin facade.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
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

    /**
     * Decode settings.
     *
     * [whiteBalance], [temperatureK], and [tint] control colour science; temperature
     * and tint are only used for [WhiteBalance.CUSTOM].
     *
     * ### Half-size (proxy) decode — [halfSize]
     *
     * When `halfSize = true`, LibRaw sets `imgdata.params.half_size = 1` before
     * `dcraw_process()`.  Instead of running the full Bayer demosaic interpolation,
     * it averages each **2×2 Bayer cell** into a **single output pixel**.  The result
     * is an image at roughly **half the linear dimensions** (i.e. **¼ the pixel
     * count**) of a full-resolution decode.
     *
     * **Benefits:**
     * - Peak native memory is approximately **¼** of a full-res decode — the primary
     *   OOM surface for 50–200 MP Expert RAW / large DNG files on low-RAM devices.
     * - Significantly faster: no demosaic neighbourhood search, smaller copy.
     *
     * **Limitations / tradeoffs:**
     * - **Lower quality**: each output pixel is a 2×2 Bayer average, not a full
     *   neighbourhood demosaic.  Colour aliasing and reduced sharpness are expected.
     *   This mode is suitable for proxy previews, proxy thumbnails, and fast
     *   "does it decode?" checks — not for export or spectral film simulation.
     * - `LinearResult.width` and `LinearResult.height` will be approximately half
     *   the full-res values.  LibRaw updates `imgdata.sizes` after processing;
     *   `dcraw_make_mem_image` reports the post-process dimensions, so the returned
     *   `width * height * 3 == rgb.size()` invariant is always satisfied.
     *
     * **Default `false`** → full-resolution decode; existing behaviour is unchanged.
     */
    data class Settings(
        val whiteBalance: WhiteBalance = WhiteBalance.AS_SHOT,
        val temperatureK: Double = 6504.0,
        val tint: Double = 1.0,
        /** When true, decode at half linear dimensions (~¼ pixels, ~¼ memory). */
        val halfSize: Boolean = false,
        /**
         * Hard cap on the decoded image's longest edge in pixels (0 = no cap). When >0,
         * the native decoder box-downsamples the result to fit BEFORE handing back the
         * direct buffer, so the allocation is bounded even if [halfSize] didn't reduce the
         * dimensions (some DNGs ignore LibRaw's half_size and decode full-resolution).
         */
        val maxLongEdge: Int = 0,
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
            settings.halfSize, settings.maxLongEdge,
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
            settings.halfSize, settings.maxLongEdge,
        ).toLinear()
    }

    /**
     * Decode from a file descriptor (e.g. `ParcelFileDescriptor.detachFd()` from a
     * SAF Uri). The fd is duplicated natively; the caller retains ownership.
     */
    fun decodeToLinear(fd: Int, settings: Settings = Settings()): LinearResult =
        nativeDecodeFd(
            fd, settings.whiteBalance.nativeMode, settings.temperatureK, settings.tint,
            settings.halfSize, settings.maxLongEdge,
        ).toLinear()

    /** Decode by reading an InputStream fully into memory (SAF convenience). */
    fun decodeToLinear(stream: InputStream, settings: Settings = Settings()): LinearResult =
        decodeToLinear(stream.readBytes(), settings)

    /**
     * Free a native (off-heap) RAW result buffer. The full-resolution decode hands back
     * a [LinearResult.data] / [NativeResult.data] backed by `malloc` + `NewDirectByteBuffer`
     * (NOT `ByteBuffer.allocateDirect`), so it lives outside the ART managed heap and is
     * NOT reclaimed by the GC — the owner must free it explicitly when done. Safe to call
     * with any direct buffer this object produced; a no-op on a null/unmapped buffer.
     */
    fun freeOffHeap(buf: ByteBuffer) = nativeFree(buf)

    private fun NativeResult.toLinear(): LinearResult {
        // Native gives little-/native-endian float32; tag the byte order so callers
        // reading as a FloatBuffer get correct values.
        data.order(ByteOrder.nativeOrder())
        return LinearResult(data, width, height, colorSpace)
    }

    /**
     * ## Mobile / Pixel / Samsung compressed DNG coverage
     *
     * Most mobile DNGs (Google Pixel computational RAW, plain camera DNGs) use
     * one of three raw-plane compressions, ALL decoded natively here:
     *
     *  * **Uncompressed** (Compression 1) — decoded natively.
     *  * **Lossless-JPEG / LJ92** (Compression 7) — the common Pixel encoding;
     *    decoded natively by LibRaw's own internal lossless-JPEG code (no
     *    libjpeg needed).
     *  * **DEFLATE/ZIP** (Compression 8) — decoded natively (this module is
     *    built with zlib; the NDK ships libz).
     *
     * Variants that still need a platform fallback (no libjpeg/libjxl vendored):
     *
     *  * **Lossy-baseline-JPEG DNG** (DNG 1.4 lossy, Compression 0x884C; or
     *    old-style JPEG, Compression 6) — common in Samsung Expert RAW. LibRaw
     *    `unpack()` fails; the native layer throws [RawDecodeException] with
     *    status [DecodeStatus.LOSSY_JPEG_DNG].
     *  * **JPEG-XL DNG** (DNG 1.7, recent Galaxy S24+ Expert RAW) — needs libjxl
     *    / Adobe DNG SDK 1.7; surfaces as [DecodeStatus.JPEGXL_DNG] (or, if the
     *    container can't be opened at all, [DecodeStatus.FILE_UNSUPPORTED]).
     *
     * ### Recommended app-side fallback (implemented in the app module, NOT here)
     *
     * On [DecodeStatus.LOSSY_JPEG_DNG] / [DecodeStatus.JPEGXL_DNG] (and,
     * defensively, on [DecodeStatus.FILE_UNSUPPORTED] for a `.dng`), fall back to
     * Android's platform DNG decoder, backed by the system codecs, which decodes
     * lossy / JPEG-XL DNG. Use [android.graphics.ImageDecoder] (API 28+):
     *
     * ```kotlin
     * // App-side (feature module), NOT in lib:libraw:
     * try {
     *     val r = RawDecoder.decodeToLinear(fd)   // engine path
     *     // ... hand r to SpektraEngine ...
     * } catch (e: RawDecodeException) {
     *     when (e.status) {
     *         DecodeStatus.LOSSY_JPEG_DNG,
     *         DecodeStatus.JPEGXL_DNG,
     *         DecodeStatus.FILE_UNSUPPORTED -> {
     *             val src = ImageDecoder.createSource(resolver, uri) // API 28+
     *             val bmp = ImageDecoder.decodeBitmap(src)
     *             // NOTE: bmp is display-referred sRGB, not linear ACES, so this
     *             // bypasses the spectral pipeline (preview / import only).
     *         }
     *         else -> throw e
     *     }
     * }
     * ```
     *
     * Full lossy-JPEG-DNG support on the engine (linear) path requires linking
     * libjpeg into this module (see `SFRAW_LIBRAW_JPEG_TARGET` in
     * `src/main/cpp/CMakeLists.txt`); JPEG-XL additionally requires the Adobe
     * DNG SDK 1.7.
     */

    // --- native bridge (raw_decoder_jni.cpp) ---
    private external fun nativeDecodeBytes(
        bytes: ByteArray, wbMode: Int, temperatureK: Double, tint: Double,
        halfSize: Boolean, maxLongEdge: Int,
    ): NativeResult

    private external fun nativeDecodeBuffer(
        buffer: ByteBuffer, len: Int, wbMode: Int, temperatureK: Double, tint: Double,
        halfSize: Boolean, maxLongEdge: Int,
    ): NativeResult

    private external fun nativeDecodeFd(
        fd: Int, wbMode: Int, temperatureK: Double, tint: Double,
        halfSize: Boolean, maxLongEdge: Int,
    ): NativeResult

    /** Free a native (malloc + NewDirectByteBuffer) result buffer (see [freeOffHeap]). */
    private external fun nativeFree(buffer: ByteBuffer)

    init {
        System.loadLibrary("sfraw")
    }
}

/**
 * Stable decode status codes mirrored from the native layer
 * (`raw_decoder.h` `enum DecodeStatus`). Keep the [code] values in sync.
 *
 * Decodes NATIVELY (no exception — [RawDecoder] returns a result):
 *  - Uncompressed DNG (Compression 1) — plain mobile / Pixel DNGs.
 *  - Lossless-JPEG / LJ92 DNG (Compression 7) — common Google Pixel and other
 *    computational-RAW DNGs; LibRaw decodes these with its own internal
 *    lossless-JPEG code (no libjpeg required).
 *  - DEFLATE / ZIP DNG (Compression 8) — via zlib.
 *  - Mainstream camera RAW (CR2/CR3/NEF/ARW/RAF/ORF/RW2/...).
 */
enum class DecodeStatus(val code: Int) {
    OK(0),
    UNKNOWN(1),
    INPUT(2),
    OPEN(3),
    FILE_UNSUPPORTED(4),
    UNPACK(5),
    PROCESS(6),
    NO_MEMORY(7),
    FORMAT(8),

    /** DEFLATE-compressed DNG but the build lacks zlib (should not happen). */
    DEFLATE_DNG(10),

    /**
     * Lossy-baseline-JPEG compressed DNG (DNG 1.4 lossy, Compression 0x884C, or
     * old-style JPEG, Compression 6). The build has no libjpeg, so fall back to
     * the platform `ImageDecoder`. See [RawDecoder].
     */
    LOSSY_JPEG_DNG(11),

    /**
     * JPEG-XL compressed DNG (DNG 1.7+, Compression 0xCD42). The build has no
     * libjxl / DNG SDK; fall back to the platform `ImageDecoder` (Android 14+
     * decodes JXL). See [RawDecoder].
     */
    JPEGXL_DNG(12),
    ;

    companion object {
        fun fromCode(code: Int): DecodeStatus =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Thrown by the native decoder on failure. The [status] lets callers branch on
 * the failure kind (e.g. compressed Expert RAW DNG -> platform ImageDecoder
 * fallback); [librawCode] is the underlying LibRaw `LIBRAW_*` code (0 if N/A).
 *
 * Constructed from native code via `(String, int, int)`; the JNI bridge throws
 * it on every decode failure (see raw_decoder_jni.cpp).
 */
@Suppress("unused")
class RawDecodeException(
    message: String,
    val statusCode: Int,
    val librawCode: Int,
) : RuntimeException(message) {

    /** Typed status for `when` branching (e.g. ImageDecoder fallback). */
    val status: DecodeStatus get() = DecodeStatus.fromCode(statusCode)

    constructor(message: String) : this(message, DecodeStatus.UNKNOWN.code, 0)
}
