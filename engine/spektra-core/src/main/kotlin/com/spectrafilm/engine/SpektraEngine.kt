/*
 * Spektrafilm for Android — engine facade.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Kotlin entry point to the native engine (libspektra.so). Mirrors spektrafilm's
 * simulate(image, params) / simulate_preview(image, params). Image buffers are passed
 * as linear, scene-referred float RGB and returned as a display-referred result.
 *
 * The native methods throw a [RuntimeException] carrying the specific
 * `spk_status` message (e.g. "spektra: profile not found") on failure, so a real,
 * actionable error reaches the caller. A null return without an exception is
 * treated as an unexpected engine fault.
 */
package com.spectrafilm.engine

import java.nio.ByteBuffer

/**
 * A linear, scene-referred image: interleaved RGB float32, row-major.
 *
 * MEMORY OWNERSHIP — full-resolution buffers live OFF the managed heap. A
 * full-res RAW decode is ~140 MB; allocated as a JVM-managed direct ByteBuffer
 * (`ByteBuffer.allocateDirect`, which on Android is a non-movable `byte[]` on the
 * ART heap) two of those at export time blow the ~256 MB heap-growth limit
 * (OutOfMemoryError). Following Adobe Lightroom — whose native engine keeps all
 * full-res pixels in native memory and never crosses them to the Java heap — the
 * large RAW/engine buffers are allocated natively (`malloc` + `NewDirectByteBuffer`)
 * and reclaimed via [onClose]. Small/proxy buffers stay managed ([onClose] null),
 * so the GC handles them and [close] is a no-op.
 *
 * [close] must be called when an off-heap image is no longer needed (native memory
 * is NOT tracked by the GC). It is idempotent and safe on managed images.
 */
class LinearImage(
    val data: ByteBuffer,        // direct buffer, length = width*height*3*4 bytes
    val width: Int,
    val height: Int,
    val colorSpace: String = "ProPhoto RGB",
    private val onClose: ((ByteBuffer) -> Unit)? = null,
) : AutoCloseable {
    init {
        require(data.isDirect) { "LinearImage requires a direct ByteBuffer" }
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
    }

    private var closed = false

    /** Free the backing native buffer if this image owns one; no-op for managed buffers. */
    override fun close() {
        if (closed) return
        closed = true
        onClose?.invoke(data)
    }
}

/**
 * Result of a simulation: display-referred RGB in [SpektraParams.io].outputColorSpace.
 *
 * The engine output buffer is allocated in NATIVE memory (`malloc` +
 * `NewDirectByteBuffer`), off the ART managed heap (see [LinearImage]). Call [close]
 * once the result has been consumed (turned into a Bitmap, or written to a file) to
 * free it; it is idempotent.
 */
class SimResult(
    val data: ByteBuffer,
    val width: Int,
    val height: Int,
    val colorSpace: ColorSpace,
) : AutoCloseable {

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        freeDirectBuffer(data)
    }

    companion object {
        /**
         * Free a native (`NewDirectByteBuffer`-wrapped `malloc`) engine-output buffer.
         * Implemented in spektra_jni.cpp; libspektra is already loaded by [SpektraEngine].
         */
        @JvmStatic external fun freeDirectBuffer(buf: ByteBuffer)
    }
}

class SpektraEngine(assetDir: String? = null) : AutoCloseable {

    private val handle: Long = nativeCreate(assetDir)

    /** Available film/print profile ids bundled in assets (see docs/ASSETS.md). */
    fun listProfiles(): List<String> =
        nativeListProfiles(handle).split('\n').filter { it.isNotBlank() }

    /**
     * Full pipeline: RGB → negative → (print) → scan. Heavy; call off the main
     * thread. On a native failure the underlying [RuntimeException] (with the
     * specific `spk_status` message) propagates; a null return without an
     * exception is reported as an unexpected fault.
     */
    fun simulate(image: LinearImage, params: SpektraParams): SimResult =
        nativeSimulate(handle, image.data, image.width, image.height,
            image.colorSpace, params, /* preview = */ false)
            ?: error("spektra: simulate returned null (handle=$handle)")

    /** Downscaled fast path to [SettingsParams.previewMaxSize] for interactive tuning. */
    fun simulatePreview(image: LinearImage, params: SpektraParams): SimResult =
        nativeSimulate(handle, image.data, image.width, image.height,
            image.colorSpace, params, /* preview = */ true)
            ?: error("spektra: simulatePreview returned null (handle=$handle)")

    /**
     * Bake the current film look into a 3D `.cube` LUT (Adobe/Resolve format) and
     * return its text. Builds an identity RGB lattice of [size] (default 33) in the
     * engine's linear ProPhoto working space, runs each lattice point through the
     * same pointwise pipeline [simulate] uses, and emits `LUT_3D_SIZE N` + N^3 RGB
     * triples (blue-fastest order).
     *
     * INPUT domain: linear ProPhoto RGB in [0,1]. OUTPUT domain: display RGB in
     * [params].io.outputColorSpace (CCTF per outputCctfEncoding). Spatial/stochastic
     * effects (grain, halation, diffusion glare, DIR-coupler diffusion, scanner
     * unsharp) cannot be captured by a 3D LUT and are forced OFF for the bake;
     * this is documented in the emitted `.cube` header. Heavy; call off the main thread.
     */
    fun bakeCubeLut(params: SpektraParams, size: Int = 33): String =
        nativeBakeCubeLut(handle, params, size)
            ?: error("spektra: bakeCubeLut returned null (handle=$handle)")

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
    }

    // --- native bridge (see spektra_jni.cpp) ---
    private external fun nativeCreate(assetDir: String?): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeListProfiles(handle: Long): String
    private external fun nativeSimulate(
        handle: Long, inBuf: ByteBuffer, w: Int, h: Int, inCs: String,
        params: SpektraParams, preview: Boolean,
    ): SimResult?
    private external fun nativeBakeCubeLut(
        handle: Long, params: SpektraParams, size: Int,
    ): String?

    companion object {
        init { System.loadLibrary("spektra") }
    }
}
