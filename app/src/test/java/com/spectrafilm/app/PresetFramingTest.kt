/*
 * Spektrafilm for Android — a preset carries a LOOK, not a photo's framing. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A user preset is a complete document encode, which is what keeps it from compounding
 * (see PresetRunBaselineTest for the sparse built-in half of that story). But a complete
 * encode also contains input.crop / cropCenter / cropSize and the masks block, and those
 * describe ONE photo: a crop saved off a 3:2 landscape re-frames the portrait you apply it
 * to, and mask positions are normalized, so they land somewhere else entirely.
 *
 * Presets.decode is deliberately NOT changed — undo/redo and session restore go through it
 * and must restore framing exactly. Only the preset-apply seam drops it.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetFramingTest {

    private fun cropped(): ParamsState = ParamsState().apply {
        crop = true
        cropCenter = 0.25f to 0.75f
        cropSize = 0.4f to 0.3f
        straightenDegrees = 1.5f
        contrast = 0.5f
    }

    @Test
    fun applyingAPresetKeepsThePhotosOwnCrop() {
        val savedElsewhere = Presets.encode(
            ParamsState().apply {
                crop = true
                cropCenter = 0.9f to 0.1f
                cropSize = 0.2f to 0.2f
                straightenDegrees = -7f
                contrast = 1.25f
            },
        )
        val live = cropped()
        Presets.decodeLook(savedElsewhere, live)

        assertEquals("the look must still be applied", 1.25f, live.contrast, 1e-6f)
        assertTrue(live.crop)
        assertEquals(0.25f to 0.75f, live.cropCenter)
        assertEquals(0.4f to 0.3f, live.cropSize)
        assertEquals("straighten is framing too", 1.5f, live.straightenDegrees, 0f)
    }

    @Test
    fun anUncroppedPhotoIsNotSuddenlyCropped() {
        val live = ParamsState()
        val wasCroppedWhenSaved = Presets.encode(cropped())
        Presets.decodeLook(wasCroppedWhenSaved, live)

        assertEquals(false, live.crop)
        assertEquals(0.5f to 0.5f, live.cropCenter)
        assertEquals(0f, live.straightenDegrees, 0f)
    }

    @Test
    fun masksStayWithThePhotoTheyWereDrawnOn() {
        val live = ParamsState()
        val before = live.localAdjustments
        val other = JSONObject(Presets.encode(ParamsState()).toString())
        other.put("masks", JSONObject().put("version", 2).put("masks", org.json.JSONArray()))
        Presets.decodeLook(other, live)

        assertTrue("mask list must be untouched by a preset apply", live.localAdjustments === before)
    }

    @Test
    fun onlySkinMasksCarryToOtherPhotos() {
        // A batch and the second half-frame take the look's skin masks (intent, means the same on
        // any photo) but never a radial or gradient placed on this one.
        val radial = com.spectrafilm.app.masks.LocalAdjustment(
            com.spectrafilm.app.masks.Mask(listOf(com.spectrafilm.app.masks.Mask.Component(
                com.spectrafilm.app.masks.BlendMode.ADD,
                com.spectrafilm.app.masks.MaskComponent.Radial(0.5f, 0.5f, 0.3f, 0.3f, 0.5f),
            ))),
            com.spectrafilm.app.masks.TierADelta(exposureEv = 1f),
        )
        val skin = com.spectrafilm.app.masks.LocalAdjustment(
            com.spectrafilm.app.masks.Mask(listOf(com.spectrafilm.app.masks.Mask.Component(
                com.spectrafilm.app.masks.BlendMode.ADD,
                com.spectrafilm.app.masks.MaskComponent.Semantic(
                    setOf(com.spectrafilm.app.masks.SemanticClass.FACE_SKIN), softness = 0.3f, expand = 0.2f,
                ),
            ))),
            com.spectrafilm.app.masks.TierADelta(temp = 20f),
        )
        val look = Presets.encode(ParamsState().apply { localAdjustments = listOf(radial, skin) })
        assertEquals(listOf(skin), Presets.decodeSkinMasks(look))
        assertEquals(emptyList<Any>(), Presets.decodeSkinMasks(Presets.encode(ParamsState())))
    }

    @Test
    fun decodeItselfStillRestoresFramingForUndoAndSessionRestore() {
        // The regression guard on the seam: undo/redo and session restore call decode, and a
        // crop that does not come back is a lost edit.
        val live = ParamsState()
        Presets.decode(Presets.encode(cropped()), live)

        assertTrue(live.crop)
        assertEquals(0.25f to 0.75f, live.cropCenter)
        assertEquals(0.4f to 0.3f, live.cropSize)
    }

    @Test
    fun theCallerSJsonIsNotMutated() {
        val source = Presets.encode(cropped())
        val before = source.toString()
        Presets.decodeLook(source, ParamsState())
        assertEquals(before, source.toString())
    }
}
