/*
 * Spektrafilm for Android — output color management. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Maps the engine's output [ColorSpace] (the space the scan stage CCTF-encodes into) onto the
 * Android display + export color pipeline, so wide-gamut renders are no longer silently shown and
 * exported AS sRGB (the "every gamut judgment is made on a broken display path" bug). Two seams:
 *
 *   • DISPLAY — every preview/zoom/magnifier/export Bitmap is tagged (createBitmap with the matching
 *     android.graphics.ColorSpace, API 26+) so the system color-manages it to the panel instead of
 *     assuming sRGB. The engine's per-space CCTF (model/color_output.cpp::output_cctf_encode) matches
 *     the Android named space's transfer: sRGB↔SRGB, Adobe γ(563/256)↔ADOBE_RGB, ROMM↔PRO_PHOTO_RGB,
 *     BT.2020 OETF↔BT2020, linear↔LINEAR_SRGB.
 *   • EXPORT — 16-bit TIFF/PNG embed the matching bundled ICC profile; 8-bit JPEG/PNG/UltraHDR get it
 *     for free because Bitmap.compress embeds a tagged bitmap's profile (API 26+).
 *
 * Pure Kotlin; no engine/spektra-core/cpp is touched, so the 26-test host parity suite is unaffected
 * (Tier 0/2). ICC assets ship in the engine module under assets/spektra/icc (saucecontrol + elle-stone).
 */
package com.spectrafilm.app

import android.content.Context
import com.spectrafilm.engine.ColorSpace

object ColorManagement {

    /**
     * The android.graphics.ColorSpace.Named constant *name* to tag an 8-bit ARGB_8888 Bitmap with for
     * the engine output [cs], or null when no faithful 8-bit tag exists. Returned as a String (not the
     * API-26 enum) to keep this mapping pure and JVM-unit-testable; the caller resolves it via
     * ColorSpace.Named.valueOf(name) under an API guard.
     *
     * ACES2065_1 (AP0, linear) has a value range far outside [0,1]; android.graphics.ColorSpace.ACES is
     * rejected by ARGB_8888 (it needs RGBA_F16), so an ACES preview is left untagged — it is an
     * export-intent space, not a display space. All other spaces are RGB with a parametric transfer and
     * a [0,1] range, so they tag cleanly on an 8-bit bitmap.
     */
    fun displayColorSpaceName(cs: ColorSpace): String? = when (cs) {
        ColorSpace.SRGB -> "SRGB"
        ColorSpace.ADOBE_RGB -> "ADOBE_RGB"
        ColorSpace.PROPHOTO -> "PRO_PHOTO_RGB"
        ColorSpace.REC2020 -> "BT2020"
        ColorSpace.LINEAR_SRGB -> "LINEAR_SRGB"
        ColorSpace.ACES2065_1 -> null
    }

    /**
     * Bundled ICC profile asset path (relative to the merged app assets) describing the exact encoding
     * the scan stage produced for [cs], for embedding on 16-bit TIFF/PNG export. The saucecontrol files
     * are compact V4 standard-space profiles; the two linear spaces use elle-stone γ=1.0 profiles.
     * Transfers/primaries match model/color_output.cpp::output_cctf_encode + kRGB_to_XYZ.
     */
    fun iccAssetPath(cs: ColorSpace): String = "spektra/icc/" + when (cs) {
        ColorSpace.SRGB -> "saucecontrol/sRGB-v4.icc"
        ColorSpace.ADOBE_RGB -> "saucecontrol/AdobeCompat-v4.icc"
        ColorSpace.PROPHOTO -> "saucecontrol/ProPhoto-v4.icc"
        ColorSpace.REC2020 -> "saucecontrol/Rec2020-v4.icc"
        ColorSpace.ACES2065_1 -> "ellelstone/ACES-elle-V4-g10.icc"   // AP0 primaries + linear (γ=1.0)
        ColorSpace.LINEAR_SRGB -> "ellelstone/sRGB-elle-V4-g10.icc"  // 709 primaries + linear (γ=1.0)
    }

    /**
     * Load the ICC profile bytes for [cs] from app assets, or null if the asset is unavailable — in
     * which case export degrades cleanly to no embedded profile (plus the advisory EXIF tag for TIFF).
     */
    fun loadIccBytes(ctx: Context, cs: ColorSpace): ByteArray? =
        runCatching { ctx.assets.open(iccAssetPath(cs)).use { it.readBytes() } }.getOrNull()
}
