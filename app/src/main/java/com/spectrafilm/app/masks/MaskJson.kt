/*
 * Spektrafilm for Android — local-adjustment (mask) recipe serialization. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Round-trips a List<LocalAdjustment> to/from JSON for the recipe/preset `"masks"` block, using the
 * same android org.json the rest of Presets uses. App-internal schema for now (mirrors our Mask model);
 * a future increment can additionally emit the full crs:MaskGroupBasedCorrections XMP for Lightroom
 * interop (see docs/MASKING_SPEC.md). Old recipes with no `"masks"` key → empty list → today's exact
 * global-only behavior. Pure Kotlin, no engine touched.
 */
package com.spectrafilm.app.masks

import org.json.JSONArray
import org.json.JSONObject

object MaskJson {

    fun toJson(adjustments: List<LocalAdjustment>): JSONArray {
        val arr = JSONArray()
        for (adj in adjustments) {
            arr.put(JSONObject().apply {
                put("delta", deltaToJson(adj.delta))
                put("mask", maskToJson(adj.mask))
            })
        }
        return arr
    }

    fun fromJson(arr: JSONArray?): List<LocalAdjustment> {
        if (arr == null) return emptyList()
        val out = ArrayList<LocalAdjustment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(LocalAdjustment(maskFromJson(o.optJSONObject("mask")), deltaFromJson(o.optJSONObject("delta"))))
        }
        return out
    }

    private fun deltaToJson(d: TierADelta) = JSONObject().apply {
        put("exposureEv", d.exposureEv.toDouble()); put("temp", d.temp.toDouble()); put("tint", d.tint.toDouble())
        put("saturation", d.saturation.toDouble()); put("contrast", d.contrast.toDouble())
    }

    private fun deltaFromJson(o: JSONObject?): TierADelta {
        if (o == null) return TierADelta()
        return TierADelta(f(o, "exposureEv"), f(o, "temp"), f(o, "tint"), f(o, "saturation"), f(o, "contrast"))
    }

    private fun maskToJson(m: Mask) = JSONObject().apply {
        put("invert", m.invert); put("opacity", m.opacity.toDouble())
        put("components", JSONArray().apply { for (c in m.components) put(componentToJson(c)) })
    }

    private fun maskFromJson(o: JSONObject?): Mask {
        if (o == null) return Mask()
        val comps = ArrayList<Mask.Component>()
        o.optJSONArray("components")?.let { ca ->
            for (i in 0 until ca.length()) ca.optJSONObject(i)?.let { comps.add(componentFromJson(it)) }
        }
        return Mask(comps, o.optBoolean("invert", false), f(o, "opacity", 1f))
    }

    private fun componentToJson(c: Mask.Component) = JSONObject().apply {
        put("mode", c.mode.name); put("invert", c.invert); put("value", c.value.toDouble())
        put("shape", shapeToJson(c.shape))
    }

    private fun componentFromJson(o: JSONObject) = Mask.Component(
        mode = enumOf(o.optString("mode"), BlendMode.ADD),
        shape = shapeFromJson(o.optJSONObject("shape")),
        invert = o.optBoolean("invert", false),
        value = f(o, "value", 1f),
    )

    private fun shapeToJson(s: MaskComponent): JSONObject = when (s) {
        is MaskComponent.Linear -> JSONObject().apply {
            put("type", "linear")
            put("x0", s.x0.toDouble()); put("y0", s.y0.toDouble())
            put("x1", s.x1.toDouble()); put("y1", s.y1.toDouble())
        }
        is MaskComponent.Radial -> JSONObject().apply {
            put("type", "radial")
            put("cx", s.cx.toDouble()); put("cy", s.cy.toDouble())
            put("rx", s.rx.toDouble()); put("ry", s.ry.toDouble())
            put("feather", s.feather.toDouble()); put("angleDeg", s.angleDeg.toDouble())
        }
    }

    private fun shapeFromJson(o: JSONObject?): MaskComponent {
        if (o == null) return MaskComponent.Radial(0.5f, 0.5f, 0.25f, 0.25f)
        return when (o.optString("type")) {
            "linear" -> MaskComponent.Linear(f(o, "x0"), f(o, "y0"), f(o, "x1", 1f), f(o, "y1"))
            else -> MaskComponent.Radial(
                f(o, "cx", 0.5f), f(o, "cy", 0.5f), f(o, "rx", 0.25f), f(o, "ry", 0.25f),
                f(o, "feather", 0.5f), f(o, "angleDeg"),
            )
        }
    }

    private fun f(o: JSONObject, k: String, def: Float = 0f) = o.optDouble(k, def.toDouble()).toFloat()

    private inline fun <reified T : Enum<T>> enumOf(name: String, def: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(def)
}
