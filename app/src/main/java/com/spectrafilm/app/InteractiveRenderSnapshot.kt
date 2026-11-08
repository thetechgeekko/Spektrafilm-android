/*
 * Spektrafilm for Android — immutable magnifier/ROI render state. GPLv3.
 */
package com.spectrafilm.app

import com.spectrafilm.app.masks.LocalAdjustment
import com.spectrafilm.engine.SpektraParams

/** One edit generation used unchanged by every worker pass and its final grade. */
internal data class InteractiveRenderSnapshot(
    val renderKey: Int,
    val params: SpektraParams,
    val cctfEncoded: Boolean,
    val saturation: Float,
    val vibrance: Float,
    val gamutCompress: Float,
    val localAdjustments: List<LocalAdjustment>,
) {
    fun paramsForCrop(
        cropFraction: Float,
        previewMaxSize: Int? = null,
    ): SpektraParams {
        require(cropFraction.isFinite() && cropFraction > 0f) {
            "crop fraction must be finite and positive"
        }
        val settings = if (previewMaxSize == null) {
            params.settings
        } else {
            require(previewMaxSize > 0) { "preview size must be positive" }
            params.settings.copy(previewMaxSize = previewMaxSize)
        }
        return params.copy(
            camera = params.camera.copy(
                filmFormatMm = params.camera.filmFormatMm * cropFraction,
                // Auto-exposure meters the image the engine is given. On a crop that is only
                // the zoomed region, so the zoom patch and the magnifier came out brighter or
                // darker than the same pixels in the full preview. The caller meters the WHOLE
                // frame instead ([frameExposureGain]) and folds that gain into the crop's
                // pixels ([cropLinearImageBox]), so the crop render must not meter again.
                autoExposure = false,
                // The date-back imprint is placed in the corner of the frame the engine
                // sees; on a cropped ROI/magnifier source that corner is the wrong place
                // (and its size would follow the crop), so these partial renders omit it.
                dateStamp = params.camera.dateStamp.copy(active = false),
            ),
            // The contrast mask's blur is a fraction of the long edge the engine sees. On
            // a crop that edge is shorter, so the fraction grows by 1/cropFraction to keep
            // the mask the same physical size as on the whole frame. (The cropped render
            // still cannot see the mask's context beyond the crop; close is the best a
            // partial render can do, and it is far closer than dropping the mask.)
            printRender = params.printRender.copy(
                contrastMask = params.printRender.contrastMask.copy(
                    scale = params.printRender.contrastMask.scale / cropFraction,
                ),
            ),
            settings = settings,
        )
    }
}

/** Must be called on the UI thread that owns [ParamsState]. */
internal fun ParamsState.captureInteractiveRenderSnapshot(
    renderKey: Int,
): InteractiveRenderSnapshot = InteractiveRenderSnapshot(
    renderKey = renderKey,
    params = toParams(),
    cctfEncoded = savingCctfEncoding,
    saturation = saturation,
    vibrance = vibrance,
    gamutCompress = gamutCompress,
    localAdjustments = localAdjustments.toList(),
)
