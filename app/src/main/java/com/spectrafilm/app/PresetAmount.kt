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

    private fun blendObject(base: JSONObject, full: JSONObject, t: Float): JSONObject {
        val out = JSONObject()
        val keys = base.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val b = base.get(k)
            // A field absent from [full] has no preset target — keep the base value.
            if (!full.has(k)) { out.put(k, b); continue }
            out.put(k, blendValue(b, full.get(k), t))
        }
        return out
    }

    private fun blendValue(b: Any, f: Any, t: Float): Any = when {
        b is JSONObject && f is JSONObject -> blendObject(b, f, t)
        b is JSONArray && f is JSONArray -> blendArray(b, f, t)
        // Integer counts are categorical here (layer/bounce counts, sizes) — snap them.
        b is Int && f is Int -> if (t < 0.5f) b else f
        b is Number && f is Number -> b.toDouble() + (f.toDouble() - b.toDouble()) * t
        b is Boolean && f is Boolean -> if (t < 0.5f) b else f
        b is String && f is String -> if (t < 0.5f) b else f
        else -> if (t < 0.5f) b else f
    }

    private fun blendArray(b: JSONArray, f: JSONArray, t: Float): JSONArray {
        // Channel Triples / pairs are equal-length numeric arrays; interpolate element-
        // wise. On any shape mismatch fall back to a hard switch at the midpoint.
        if (b.length() != f.length()) return if (t < 0.5f) b else f
        val out = JSONArray()
        for (i in 0 until b.length()) {
            out.put(blendValue(b.get(i), f.get(i), t))
        }
        return out
    }
}
