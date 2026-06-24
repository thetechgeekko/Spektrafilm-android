/*
 * Spektrafilm for Android — "balance to film stock" (virtual 85-filter). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Tungsten-balanced stocks (Kodak Vision3 200T/500T, reference illuminant "T" ≈ 2856 K) render a
 * daylight scene blue — exactly as the real film would, and exactly as upstream spektrafilm does. The
 * engine is bit-exact, so that cast is AUTHENTIC, not a bug. This optional Tier-1 pass is the escape
 * hatch for when the user wants a neutral look instead: it Bradford-adapts the linear-ProPhoto engine
 * input from the D50 working white to the film's OWN reference illuminant, the way an 85 amber filter
 * lets tungsten stock shoot neutral in daylight. Daylight stocks (D55) are a near-identity shift, so
 * the toggle is effectively a no-op there. It is applied in loadSource (alongside Creative WB) and
 * never touches engine/spektra-core, so the parity suite is unaffected.
 *
 * The reference illuminant is read straight from the engine profile asset's info.reference_illuminant
 * (the authoritative source) and mapped to a correlated colour temperature; results are cached per id.
 */
package com.spectrafilm.app

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object FilmStockBalance {

    // ProPhoto's working white is D50 (5003 K) — adapting D50 -> D50 is identity.
    private const val D50_CCT = 5003.0

    // info.reference_illuminant string -> correlated colour temperature (K).
    //   D50/D55/D60/D65 : CIE daylight series (still-film daylight balances).
    //   T / A           : CIE Incandescent / Illuminant A ≈ 2856 K (Vision3 tungsten stocks).
    //   TH-KG3          : tungsten-halogen through a KG3 heat-absorbing filter ≈ 3200 K (cine prints).
    private val ILLUMINANT_CCT = mapOf(
        "D50" to 5003.0,
        "D55" to 5503.0,
        "D60" to 6000.0,
        "D65" to 6504.0,
        "T" to 2856.0,
        "A" to 2856.0,
        "TH-KG3" to 3200.0,
    )

    // Unknown / unreadable profiles fall back to D55 — a daylight balance whose adaptation from D50 is
    // sub-visible, so an unrecognised stock degrades to a safe near-no-op rather than a wrong strong cast.
    private const val DEFAULT_CCT = 5503.0

    private val cctCache = ConcurrentHashMap<String, Double>()

    /** Reference-illuminant CCT (Kelvin) for engine profile [profileId]; read once from the asset, cached. */
    fun referenceCct(ctx: Context, profileId: String): Double =
        cctCache.getOrPut(profileId) {
            val illum = runCatching {
                ctx.assets.open("spektra/profiles/$profileId.json").use {
                    JSONObject(it.readBytes().decodeToString())
                }.optJSONObject("info")?.optString("reference_illuminant", "").orEmpty()
            }.getOrNull().orEmpty()
            ILLUMINANT_CCT[illum] ?: DEFAULT_CCT
        }

    /**
     * True when balancing to [profileId]'s reference white is a perceptible warm/cool — i.e. the stock is
     * tungsten-balanced (T ≈ 2856 K or TH-KG3 ≈ 3200 K, ≳1800 K from D50). Daylight stocks (D55, only
     * ~500 K away) fall below the threshold and are treated as a no-op: they already render neutral, so
     * the toggle skips both the per-pixel pass and a needless re-decode for them. The 1000 K threshold
     * sits cleanly between D55 and the tungsten references.
     */
    fun isMeaningful(ctx: Context, profileId: String): Boolean =
        abs(referenceCct(ctx, profileId) - D50_CCT) > 1000.0

    /** Row-major linear-ProPhoto 3x3 that balances the D50 input to [profileId]'s reference white. */
    fun matrix(ctx: Context, profileId: String): FloatArray =
        CreativeWhiteBalance.adaptD50ToCct(referenceCct(ctx, profileId))
}
