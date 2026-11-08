/*
 * Spektrafilm for Android — unit tests for binding skin masks at render time. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Guards the map from the frame a render produces back to the source photo: the engine's crop is
 * cut inside whatever region was fed to it, so a skin mask stays on the skin when the photo is
 * cropped and when only a zoomed region is rendered.
 */
package com.spectrafilm.app

import com.spectrafilm.app.masks.BlendMode
import com.spectrafilm.app.masks.BoundSemantic
import com.spectrafilm.app.masks.FrameMap
import com.spectrafilm.app.masks.LocalAdjustment
import com.spectrafilm.app.masks.Mask
import com.spectrafilm.app.masks.MaskComponent
import com.spectrafilm.app.masks.SemanticClass
import com.spectrafilm.app.masks.SemanticField
import com.spectrafilm.app.masks.TierADelta
import com.spectrafilm.engine.IoParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinFieldsTest {

    private val key = SkinSourceKey("content://photo", "JPEG", 0)

    /** Face skin in the left half of a 4x2 source. */
    private val field = SemanticField(4, 2, mapOf(SemanticClass.FACE_SKIN to floatArrayOf(1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f)))

    private val skinMask = LocalAdjustment(
        Mask(listOf(Mask.Component(BlendMode.ADD, MaskComponent.Semantic(setOf(SemanticClass.FACE_SKIN))))),
        TierADelta(exposureEv = 1f),
    )
    private val radialMask = LocalAdjustment(
        Mask(listOf(Mask.Component(BlendMode.ADD, MaskComponent.Radial(0.5f, 0.5f, 0.3f, 0.3f, 0.5f)))),
        TierADelta(),
    )

    @Test
    fun straighteningIsADifferentSource() {
        // A field found before straightening sits on pixels that have since moved.
        assertTrue(key != key.copy(straightenDegrees = 2f))
        assertEquals(key, key.copy(straightenDegrees = 0f))
    }

    @Test
    fun noCropIsTheRegionItself() {
        assertEquals(FrameMap.IDENTITY, engineOutputFrame(IoParams(), 400, 200))
        val region = FrameMap(0.25f, 0f, 0.5f, 1f)
        assertEquals(region, engineOutputFrame(IoParams(), 200, 200, region))
    }

    @Test
    fun cropMatchesTheEngineCropBox() {
        // Right half of a 400x200 photo: centre (0.75, 0.5), size as fractions of the long side.
        val io = IoParams(crop = true, cropCenter = 0.75f to 0.5f, cropSize = 0.5f to 0.5f)
        val f = engineOutputFrame(io, 400, 200)
        val box = engineCropBox(400, 200, io.cropCenter, io.cropSize)
        assertEquals(box.x0 / 400f, f.offsetX, 1e-6f)
        assertEquals(box.width / 400f, f.scaleX, 1e-6f)
        assertEquals(box.height / 200f, f.scaleY, 1e-6f)
        assertEquals(1f, f.sourceX(1f), 1e-6f)
    }

    @Test
    fun cropIsCutInsideAZoomedRegion() {
        val region = regionOf(CropBox(100, 0, 200, 200), 400, 200) // middle half of the source
        val io = IoParams(crop = true, cropCenter = 0.5f to 0.5f, cropSize = 0.5f to 0.5f)
        val f = engineOutputFrame(io, 200, 200, region)
        // The crop is the centre 100x100 of the 200x200 region: source x 150..250 of 400.
        assertEquals(150f / 400f, f.sourceX(0f), 1e-6f)
        assertEquals(250f / 400f, f.sourceX(1f), 1e-6f)
        assertEquals(0.25f, f.sourceY(0f), 1e-6f)
    }

    @Test
    fun bindsOnlySkinMasksAndOnlyWithAField() {
        val list = listOf(radialMask, skinMask)
        assertSame(list, list.withSkinField(IoParams(), entry = null))
        assertSame(list, list.withSkinField(IoParams(), entry = SkinFieldEntry(key, null, 4, 2)))
        val noSkin = listOf(radialMask)
        assertSame(noSkin, noSkin.withSkinField(IoParams(), entry = SkinFieldEntry(key, field, 4, 2)))

        val bound = list.withSkinField(IoParams(), entry = SkinFieldEntry(key, field, 4, 2))
        assertEquals(radialMask, bound[0])
        val shape = bound[1].mask.components.single().shape
        assertTrue(shape is BoundSemantic)
        // Left half selected, right half not.
        assertEquals(1f, bound[1].mask.alphaAt(0.1f, 0.5f), 1e-6f)
        assertEquals(0f, bound[1].mask.alphaAt(0.9f, 0.5f), 1e-6f)
    }

    @Test
    fun aCroppedRenderSamplesTheSourceUnderTheCrop() {
        // Crop the right half: the whole rendered frame sits over the right (non-skin) half.
        val io = IoParams(crop = true, cropCenter = 0.75f to 0.5f, cropSize = 0.5f to 0.5f)
        val bound = listOf(skinMask).withSkinField(io, entry = SkinFieldEntry(key, field, 400, 200))
        assertEquals(0f, bound[0].mask.alphaAt(0.1f, 0.5f), 1e-6f)
        // Crop the left half: now the frame is all skin.
        val left = IoParams(crop = true, cropCenter = 0.25f to 0.5f, cropSize = 0.5f to 0.5f)
        val boundLeft = listOf(skinMask).withSkinField(left, entry = SkinFieldEntry(key, field, 400, 200))
        assertEquals(1f, boundLeft[0].mask.alphaAt(0.9f, 0.5f), 1e-6f)
    }
}
