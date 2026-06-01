/*
 * Spektrafilm for Android — unit tests for tone-curve params plumbing. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Covers the JNI packing layout (SpektraParams.toneCurvePacked) and the preset/recipe
 * JSON round-trip of the tone curve through ParamsState.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ToneCurveChannel
import com.spectrafilm.engine.ToneCurveParams
import com.spectrafilm.engine.SpektraParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveParamsTest {

    @Test fun packLayoutMatchesContract() {
        val p = SpektraParams(
            filmProfile = "kodak_portra_400",
            printProfile = "kodak_portra_endura",
            toneCurve = ToneCurveParams(
                active = true,
                master = ToneCurveChannel(listOf(0f to 0f, 0.5f to 0.25f, 1f to 1f)),
                red = ToneCurveChannel(listOf(0f to 0.1f, 1f to 1f)),
                // green/blue empty
            ),
        )
        val packed = p.toneCurvePacked()
        // [active, mN, (x,y)*3, rN, (x,y)*2, gN, bN]
        assertEquals(1f, packed[0])      // active
        assertEquals(3f, packed[1])      // master count
        assertEquals(0.5f, packed[4])    // master[1].x
        assertEquals(0.25f, packed[5])   // master[1].y
        assertEquals(2f, packed[8])      // red count
        assertEquals(0.1f, packed[10])   // red[0].y
        assertEquals(0f, packed[13])     // green count
        assertEquals(0f, packed[14])     // blue count
        assertEquals(15, packed.size)
    }

    @Test fun inactiveCurvePacksEmptyChannels() {
        val p = SpektraParams("kodak_portra_400", "kodak_portra_endura")
        val packed = p.toneCurvePacked()
        assertEquals(0f, packed[0])      // inactive
        assertEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f).toList(), packed.toList())
    }

    @Test fun presetRoundTripPreservesToneCurve() {
        val src = ParamsState().apply {
            toneCurveActive = true
            toneCurveMaster = listOf(0f to 0f, 0.4f to 0.3f, 1f to 1f)
            toneCurveBlue = listOf(0f to 0.05f, 1f to 0.95f)
        }
        val json = Presets.encode(src)
        val dst = ParamsState()
        Presets.decode(JSONObject(json.toString()), dst)
        assertTrue(dst.toneCurveActive)
        assertEquals(src.toneCurveMaster, dst.toneCurveMaster)
        assertEquals(src.toneCurveBlue, dst.toneCurveBlue)
        assertTrue(dst.toneCurveRed.isEmpty())
        assertFalse(dst.toneCurveGreen.isNotEmpty())
    }
}
