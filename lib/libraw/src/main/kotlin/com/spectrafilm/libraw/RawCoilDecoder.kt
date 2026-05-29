/*
 * SpectraFilm for Android — lib:libraw Coil 3 decoder (secondary integration point).
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 * Uses LibRaw (LGPL-2.1).
 *
 * This is the "full-res RAW in the gallery" integration point from docs/RAW_DNG.md:
 * a Coil 3 Decoder.Factory that decodes camera RAW / DNG to full resolution so RAW
 * files open at full quality throughout the host app — not just as embedded
 * previews (ImageToolbox's existing NefDecoder stays for fast thumbnails).
 *
 * It reuses RawDecoder for the sensor decode, then tone-maps the linear ACES RGB to
 * a displayable 8-bit sRGB Bitmap for Coil. (The *engine* path keeps the full
 * 16-bit linear buffer; this preview/open path only needs something displayable.)
 *
 * --------------------------------------------------------------------------------
 * WHERE TO REGISTER (host app)
 * --------------------------------------------------------------------------------
 * In ImageToolbox:
 *   core/data/src/main/java/.../core/data/di/ImageLoaderModule.kt
 *   -> provideComponentRegistry(...): ComponentRegistry.Builder()
 *        ...
 *        add(NefDecoder.Factory())          // existing: NEF preview (thumbnails)
 *        add(RawCoilDecoder.Factory())      // ADD: full-res LibRaw RAW/DNG open
 *        ...
 * Register it *before* generic bitmap decoders so RAW extensions are claimed here.
 * The class would live alongside NefDecoder in core/data/.../coil/ in the host;
 * this copy in lib:libraw is the reference sketch the host wires up.
 * --------------------------------------------------------------------------------
 *
 * NOTE: imports are written against Coil 3's API. They resolve once this module
 * (or the host) depends on coil3 (already present in ImageToolbox). Marked with
 * TODO where host-side tone-mapping choices are made.
 */
package com.spectrafilm.libraw

import android.graphics.Bitmap
import android.graphics.Color
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlin.math.pow

class RawCoilDecoder private constructor(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = runCatching {
            source.source().peek().readByteArray()
        }.getOrNull() ?: return null

        val linear = runCatching {
            // Gallery open uses as-shot WB (what the camera intended); the editor
            // screen lets the user pick other modes through RawDecoder.Settings.
            RawDecoder.decodeToLinear(bytes, RawDecoder.Settings(WhiteBalance.AS_SHOT))
        }.getOrNull() ?: return null

        val bitmap = linear.toDisplayBitmap()
        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = false,
        )
    }

    /**
     * Tone-map linear ACES2065-1 float RGB to a displayable 8-bit ARGB_8888 Bitmap.
     * This is the minimal "make it visible" path for the gallery; it is NOT the
     * scene-referred buffer the engine consumes.
     *
     * TODO(host): replace this naive ACES->sRGB approximation with the host's
     * proper colour management (ICC / RRT+ODT) if gallery colour accuracy matters.
     */
    private fun LinearResult.toDisplayBitmap(): Bitmap {
        val floats = data.asFloatBuffer()
        val pixels = IntArray(width * height)
        val gamma = 1.0f / 2.2f  // crude sRGB-ish encoding for preview only
        var i = 0
        while (i < pixels.size) {
            val base = i * 3
            val r = encode(floats.get(base), gamma)
            val g = encode(floats.get(base + 1), gamma)
            val b = encode(floats.get(base + 2), gamma)
            pixels[i] = Color.rgb(r, g, b)
            i++
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun encode(value: Float, gamma: Float): Int {
        val clamped = value.coerceIn(0f, 1f)
        return (clamped.pow(gamma) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            // Claim only files whose name/Uri ends in a supported RAW extension.
            val name = options.diskCacheKey
                ?: result.source.file().name
            return if (RawDecoder.isRawFileName(name)) {
                RawCoilDecoder(result.source, options)
            } else {
                null
            }
        }
    }
}
