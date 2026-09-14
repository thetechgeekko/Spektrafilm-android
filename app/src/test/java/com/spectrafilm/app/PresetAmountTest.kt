/*
 * Spektrafilm for Android — unit tests for preset "amount" cross-fading. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * PresetAmount's header promises it "linearly cross-fade[s] every continuous parameter"
 * and that only categorical leaves snap — "booleans, strings, and integer counts like
 * nSubLayers / nBounces". blendValue decides which is which by the RUNTIME TYPE of the
 * parsed JSON value.
 *
 * That signal is not trustworthy, because the amount slider re-parses the anchor from
 * TEXT (MainActivity's onAmountChange does JSONObject(presetBaseJson), and the anchors
 * are strings from Presets.toJsonString). org.json writes an integral Double without its
 * ".0", so a continuous float that happens to land on a whole number comes back as an
 * Integer and takes the categorical branch.
 *
 * Runs on the plain JVM with the real org.json on the test classpath — the same parser
 * the device uses, which is what makes these assertions meaningful.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetAmountTest {

    /** Round-trip through text exactly the way the amount slider does. */
    private fun asStored(o: JSONObject): JSONObject = JSONObject(o.toString(2))

    @Test
    fun integralDoubleLosesItsTypeThroughTheTextRoundTrip() {
        // The mechanism the rest of this file depends on. If org.json ever starts
        // preserving "0.0", the snap below disappears and these tests should be revisited.
        val stored = asStored(JSONObject().put("ev", 0.0))
        assertTrue(
            "expected an integral Double to re-parse as Int, got ${stored.get("ev")::class.java}",
            stored.get("ev") is Int,
        )
        // A non-integral Double does NOT come back as a Double either — it is a
        // BigDecimal. That still takes the continuous branch (it is a Number), which is
        // why the bug only ever bit parameters that landed on whole numbers.
        val fractional = asStored(JSONObject().put("ev", 0.5)).get("ev")
        assertTrue("got ${fractional::class.java.name}", fractional is java.math.BigDecimal)
    }

    @Test
    fun continuousFieldWithWholeNumberEndpoints_interpolates() {
        // enlarger.yFilterShift 0 -> -2 is a real pair: three shipped built-ins author it.
        val base = asStored(JSONObject().put("yFilterShift", 0.0))
        val full = asStored(JSONObject().put("yFilterShift", -2.0))

        assertEquals(-0.5, PresetAmount.blend(base, full, 0.25f).getDouble("yFilterShift"), 1e-9)
        assertEquals(-1.0, PresetAmount.blend(base, full, 0.5f).getDouble("yFilterShift"), 1e-9)
        assertEquals(-1.5, PresetAmount.blend(base, full, 0.75f).getDouble("yFilterShift"), 1e-9)
    }

    @Test
    fun exposureCompensation_interpolates() {
        val base = asStored(JSONObject().put("exposureCompensationEv", 0.0))
        val full = asStored(JSONObject().put("exposureCompensationEv", 1.0))
        assertEquals(
            0.25,
            PresetAmount.blend(base, full, 0.25f).getDouble("exposureCompensationEv"),
            1e-9,
        )
    }

    @Test
    fun endpointsAreExact() {
        val base = asStored(JSONObject().put("yFilterShift", 0.0))
        val full = asStored(JSONObject().put("yFilterShift", -2.0))
        assertEquals(0.0, PresetAmount.blend(base, full, 0f).getDouble("yFilterShift"), 1e-9)
        assertEquals(-2.0, PresetAmount.blend(base, full, 1f).getDouble("yFilterShift"), 1e-9)
    }

    @Test
    fun genuinelyCategoricalCountsStillSnap() {
        // The cases the categorical branch exists for must keep snapping: a sub-layer
        // count of 2.5 is not a thing.
        val base = asStored(JSONObject().put("nSubLayers", 1).put("nBounces", 1))
        val full = asStored(JSONObject().put("nSubLayers", 3).put("nBounces", 2))

        val low = PresetAmount.blend(base, full, 0.25f)
        assertEquals(1, low.getInt("nSubLayers"))
        assertEquals(1, low.getInt("nBounces"))

        val high = PresetAmount.blend(base, full, 0.75f)
        assertEquals(3, high.getInt("nSubLayers"))
        assertEquals(2, high.getInt("nBounces"))
    }

    @Test
    fun booleansAndStringsStillSnap() {
        val base = asStored(JSONObject().put("active", false).put("filmProfile", "a"))
        val full = asStored(JSONObject().put("active", true).put("filmProfile", "b"))

        assertEquals(false, PresetAmount.blend(base, full, 0.25f).getBoolean("active"))
        assertEquals("a", PresetAmount.blend(base, full, 0.25f).getString("filmProfile"))
        assertEquals(true, PresetAmount.blend(base, full, 0.75f).getBoolean("active"))
        assertEquals("b", PresetAmount.blend(base, full, 0.75f).getString("filmProfile"))
    }

    @Test
    fun numericArraysWithWholeNumberElementsInterpolate() {
        // Per-channel Triples round-trip as arrays; the same typing trap applies per element.
        val base = asStored(JSONObject().put("couplers", org.json.JSONArray(listOf(20.0, 20.0, 20.0))))
        val full = asStored(JSONObject().put("couplers", org.json.JSONArray(listOf(18.0, 18.0, 18.0))))
        val mid = PresetAmount.blend(base, full, 0.5f).getJSONArray("couplers")
        assertEquals(19.0, mid.getDouble(0), 1e-9)
        assertEquals(19.0, mid.getDouble(1), 1e-9)
        assertEquals(19.0, mid.getDouble(2), 1e-9)
    }

    @Test
    fun realEncodedStateRoundTripsThroughTheSamePath() {
        // Not synthetic: go through the encoder the anchors actually use, so this fails
        // if Presets ever stops emitting bare ints for integral floats.
        val state = ParamsState()
        val text = Presets.toJsonString(state)
        val reparsed = JSONObject(text)
        val intish = reparsed.keys().asSequence().filter { reparsed.get(it) is Int }.toList()
        assertTrue(
            "expected the flat encode to contain integral-valued floats typed as Int",
            intish.isNotEmpty(),
        )
    }
}
