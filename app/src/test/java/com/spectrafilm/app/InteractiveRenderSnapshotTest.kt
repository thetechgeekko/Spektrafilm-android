/*
 * Spektrafilm for Android — immutable magnifier/ROI render snapshot regressions. GPLv3.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class InteractiveRenderSnapshotTest {
    @Test
    fun oneSnapshotFeedsBothRoiPassesAfterUiStateChanges() {
        val state = ParamsState().apply {
            exposureCompensationEv = 1.25f
            filmFormatMm = 36f
            savingCctfEncoding = false
            saturation = 0.2f
            vibrance = 0.3f
            gamutCompress = 0.4f
        }
        val snapshot = state.captureInteractiveRenderSnapshot(renderKey = 17)

        state.exposureCompensationEv = -3f
        state.filmFormatMm = 70f
        state.savingCctfEncoding = true
        state.saturation = 0.9f

        val draft = snapshot.paramsForCrop(cropFraction = 0.5f, previewMaxSize = 640)
        val final = snapshot.paramsForCrop(cropFraction = 0.5f, previewMaxSize = 1600)

        assertEquals(17, snapshot.renderKey)
        assertEquals(1.25f, draft.camera.exposureCompensationEv, 0f)
        assertEquals(1.25f, final.camera.exposureCompensationEv, 0f)
        assertEquals(18f, draft.camera.filmFormatMm, 0f)
        assertEquals(18f, final.camera.filmFormatMm, 0f)
        assertEquals(640, draft.settings.previewMaxSize)
        assertEquals(1600, final.settings.previewMaxSize)
        assertEquals(false, snapshot.cctfEncoded)
        assertEquals(0.2f, snapshot.saturation, 0f)
        assertEquals(0.3f, snapshot.vibrance, 0f)
        assertEquals(0.4f, snapshot.gamutCompress, 0f)
    }

    @Test
    fun aCroppedRenderKeepsTheContrastMaskTheSamePhysicalSize() {
        val state = ParamsState().apply {
            contrastMaskActive = true
            contrastMaskScale = 0.03f
        }
        val crop = state.captureInteractiveRenderSnapshot(renderKey = 1)
            .paramsForCrop(cropFraction = 0.25f)
        assertEquals(true, crop.printRender.contrastMask.active)
        assertEquals(0.12f, crop.printRender.contrastMask.scale, 1e-6f)
    }
}
