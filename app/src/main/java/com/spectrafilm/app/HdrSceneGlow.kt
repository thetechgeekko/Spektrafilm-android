/*
 * Spektrafilm for Android — film halation glow for the Ultra HDR scene tap. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

/**
 * [tap] with the film's halation glow (see [SceneHighlightTap.withHalationGlow]), using the same
 * strength, sigma and bounce model the engine renders with. The sigma is converted to map cells
 * through the film area a render pixel covers. The engine sizes its kernels from the PRE-crop
 * source (film format long edge / source long edge, then / upscale factor); a crop does not
 * enlarge the film. So: [sourceLongEdge] is the decoded source's long edge, [renderedSourceWidth]
 * the width in source pixels the render covers (the crop box, or the whole source), and
 * [renderWidth] the render's width -- which also absorbs any upscale or resize.
 */
internal fun withFilmHalationGlow(
    tap: SceneLuma,
    params: com.spectrafilm.engine.SpektraParams,
    sourceLongEdge: Int,
    renderedSourceWidth: Int,
    renderWidth: Int,
    downsample: Int,
): SceneLuma {
    val h = params.filmRender.halation
    if (!h.active || sourceLongEdge <= 0 || renderedSourceWidth <= 0 || renderWidth <= 0) return tap
    val fmm = params.camera.filmFormatMm.takeIf { it > 0f } ?: 35f
    val sourcePixelUm = fmm * 1000f / sourceLongEdge
    val cellUm = sourcePixelUm * renderedSourceWidth / renderWidth * downsample
    // Luminance weights of linear ProPhoto, the space the scene tap is measured in.
    val wr = 0.2880402f
    val wg = 0.7118741f
    val wb = 0.0000857f
    val strength = h.halationAmount *
        (wr * h.halationStrength.first + wg * h.halationStrength.second + wb * h.halationStrength.third)
    val sigmaUm = h.halationSpatialScale *
        (wr * h.halationFirstSigmaUm.first + wg * h.halationFirstSigmaUm.second +
            wb * h.halationFirstSigmaUm.third)
    return SceneHighlightTap.withHalationGlow(
        scene = tap,
        strength = strength,
        sigmaCells = sigmaUm / cellUm,
        bounces = h.halationNBounces,
        decay = h.halationBounceDecay,
        renormalize = h.halationRenormalize,
    )
}
