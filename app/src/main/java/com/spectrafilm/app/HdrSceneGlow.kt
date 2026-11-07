/*
 * Spektrafilm for Android — film halation glow for the Ultra HDR scene tap. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * The halation sigma and strength the engine actually renders a stock with. They are NOT the
 * user-facing [com.spectrafilm.engine.HalationParams] fields: `digest_halation_params`
 * (runtime/params.cpp, after upstream `_apply_halation_preset`) bakes them from the profile's
 * `info.use` / `info.antihalation` tags, and `apply_user_halation` leaves them alone. A stock with
 * an unknown tag gets no halation at all (null).
 */
internal class FilmHalationPreset(val sigmaUm: Float, val strength: Triple<Float, Float, Float>) {
    companion object {
        fun of(use: String, antihalation: String): FilmHalationPreset? {
            val sigma = when (use) {
                "still" -> 65f
                "cine" -> 50f
                else -> return null
            }
            val strength = when (antihalation) {
                "strong" -> Triple(0.015f, 0.005f, 0f)
                "weak" -> Triple(0.08f, 0.02f, 0f)
                "no" -> Triple(0.30f, 0.10f, 0.015f)
                else -> return null
            }
            return FilmHalationPreset(sigma, strength)
        }

        private val cache = ConcurrentHashMap<String, Result<FilmHalationPreset?>>()

        /** The preset for engine profile [profileId], read once from its asset; null if none. */
        fun forProfile(ctx: Context, profileId: String): FilmHalationPreset? =
            cache.getOrPut(profileId) {
                runCatching {
                    val info = ctx.assets.open("spektra/profiles/$profileId.json").use {
                        JSONObject(it.readBytes().decodeToString())
                    }.optJSONObject("info")
                    of(info?.optString("use").orEmpty(), info?.optString("antihalation").orEmpty())
                }
            }.getOrNull()
    }
}

/**
 * [tap] with the film's halation glow (see [SceneHighlightTap.withHalationGlow]), using the same
 * strength, sigma and bounce model the engine renders with: sigma/strength from the stock's
 * [preset], amount/spatial scale/bounces from the user params, as the engine combines them.
 * The sigma is converted to map cells through the film area a render pixel covers. The engine sizes its kernels from the PRE-crop
 * source (film format long edge / source long edge, then / upscale factor); a crop does not
 * enlarge the film. So: [sourceLongEdge] is the decoded source's long edge, [renderedSourceWidth]
 * the width in source pixels the render covers (the crop box, or the whole source), and
 * [renderWidth] the render's width -- which also absorbs any upscale or resize.
 */
internal fun withFilmHalationGlow(
    tap: SceneLuma,
    params: com.spectrafilm.engine.SpektraParams,
    preset: FilmHalationPreset?,
    sourceLongEdge: Int,
    renderedSourceWidth: Int,
    renderWidth: Int,
    downsample: Int,
): SceneLuma {
    val h = params.filmRender.halation
    if (!h.active || preset == null) return tap
    if (sourceLongEdge <= 0 || renderedSourceWidth <= 0 || renderWidth <= 0) return tap
    val fmm = params.camera.filmFormatMm.takeIf { it > 0f } ?: 35f
    val sourcePixelUm = fmm * 1000f / sourceLongEdge
    val cellUm = sourcePixelUm * renderedSourceWidth / renderWidth * downsample
    // Luminance weights of linear ProPhoto, the space the scene tap is measured in.
    val wr = 0.2880402f
    val wg = 0.7118741f
    val wb = 0.0000857f
    val strength = h.halationAmount *
        (wr * preset.strength.first + wg * preset.strength.second + wb * preset.strength.third)
    val sigmaUm = h.halationSpatialScale * preset.sigmaUm
    return SceneHighlightTap.withHalationGlow(
        scene = tap,
        strength = strength,
        sigmaCells = sigmaUm / cellUm,
        bounces = h.halationNBounces,
        decay = h.halationBounceDecay,
        renormalize = h.halationRenormalize,
    )
}
