/*
 * Spektrafilm for Android — film halation glow for the Ultra HDR scene tap. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

/**
 * [tap] with the film's halation glow (see [SceneHighlightTap.withHalationGlow]), using the same
 * strength, sigma and bounce model the engine renders with. The sigma is converted to map cells
 * with the engine's own film pixel size (film format long edge / render long edge).
 */
internal fun withFilmHalationGlow(
    tap: SceneLuma,
    params: com.spectrafilm.engine.SpektraParams,
    renderLongEdge: Int,
    downsample: Int,
): SceneLuma {
    val h = params.filmRender.halation
    if (!h.active || renderLongEdge <= 0) return tap
    val fmm = params.camera.filmFormatMm.takeIf { it > 0f } ?: 35f
    val cellUm = fmm * 1000f / renderLongEdge * downsample
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
