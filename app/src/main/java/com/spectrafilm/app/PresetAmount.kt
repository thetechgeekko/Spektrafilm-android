/*
 * Spektrafilm for Android — preset "amount" blending. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Lightroom-style preset amount: dial how strongly an applied preset/recipe is mixed
 * over the look that was active just before it was applied. amount = 0 reproduces the
 * pre-apply state, amount = 1 the full preset, and values in between linearly cross-fade
 * every continuous parameter.
 *
 * Blending operates on the shared flat preset JSON (Presets.encode / Presets.decode), so
 * it covers exactly the same field set that round-trips through a saved preset — both
 * built-in and user presets feed it the same schema. Continuous leaves (floats and
 * float arrays such as the per-channel Triples) are linearly interpolated; categorical
 * leaves (booleans, strings, and integer counts like nSubLayers / nBounces) cannot be
 * partially applied, so they snap to the preset value once amount crosses 0.5 and stay
 * at the base value below it.
 *
 * Which leaves are categorical is decided by KEY, not by runtime type. The anchors are
 * re-parsed from text on every drag frame (MainActivity's onAmountChange does
 * JSONObject(presetBaseJson), and the anchors are Presets.toJsonString output), and
 * org.json writes an integral Double without its ".0" — so 0.0 comes back as an
 * Integer and 0.5 comes back as a BigDecimal. Typing off that made every continuous
 * parameter whose endpoints happened to be whole numbers snap at 50% instead of
 * cross-fading: exposure compensation 0 -> 1, the enlarger filter shifts 0 -> -2, the
 * coupler amounts 20 -> 18. See PresetAmountTest.
 */
package com.spectrafilm.app

import org.json.JSONArray
import org.json.JSONObject

object PresetAmount {

    /**
     * Cross-fade [base] (the pre-apply look) toward [full] (the applied preset) by
     * [amount] in [0, 1]. Both objects must use the [Presets] flat schema; [base]
     * always carries the full field set (it is an encode of live state), so blending
     * walks [base]'s keys and is robust to a sparser [full].
     */
    fun blend(base: JSONObject, full: JSONObject, amount: Float): JSONObject {
        val t = amount.coerceIn(0f, 1f)
        return blendObject(base, full, t)
    }

    /**
     * The only genuinely categorical numeric leaves in the flat schema: a document
     * version, a grain sub-layer count and a halation bounce count. These are exactly
     * the three keys [Presets] reads back with its integer reader; every other numeric
     * leaf is read as a float and is therefore continuous, whatever type org.json
     * happened to give it after the text round-trip.
     */
    private val CATEGORICAL_NUMERIC_KEYS = setOf("version", "nSubLayers", "nBounces")

    private fun blendObject(base: JSONObject, full: JSONObject, t: Float): JSONObject {
        val out = JSONObject()
        val keys = base.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val b = base.get(k)
            // A field absent from [full] has no preset target — keep the base value.
            if (!full.has(k)) { out.put(k, b); continue }
            out.put(k, blendValue(k, b, full.get(k), t))
        }
        return out
    }

    private fun blendValue(key: String, b: Any, f: Any, t: Float): Any = when {
        b is JSONObject && f is JSONObject -> blendObject(b, f, t)
        b is JSONArray && f is JSONArray -> blendArray(key, b, f, t)
        b is Boolean && f is Boolean -> if (t < 0.5f) b else f
        b is String && f is String -> if (t < 0.5f) b else f
        // Counts cannot be partially applied. Keyed, not typed — see the file header.
        key in CATEGORICAL_NUMERIC_KEYS && b is Number && f is Number ->
            if (t < 0.5f) b else f
        b is Number && f is Number -> b.toDouble() + (f.toDouble() - b.toDouble()) * t
        else -> if (t < 0.5f) b else f
    }

    private fun blendArray(key: String, b: JSONArray, f: JSONArray, t: Float): JSONArray {
        // Channel Triples / pairs are equal-length numeric arrays; interpolate element-
        // wise. On any shape mismatch fall back to a hard switch at the midpoint. The
        // parent key rides along so an array under a categorical key would still snap;
        // none of the three are arrays today, which keeps this a no-op safeguard.
        if (b.length() != f.length()) return if (t < 0.5f) b else f
        val out = JSONArray()
        for (i in 0 until b.length()) {
            out.put(blendValue(key, b.get(i), f.get(i), t))
        }
        return out
    }
}
