/*
 * Spektrafilm for Android — plain-language help for the opaque controls. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The editor exposes the engine's real, physically-named knobs (DIR couplers, halation
 * scatter, density-curve gamma…). They are honest but intimidating. This is the onboarding
 * answer (USER_DRIVEN_SOLUTIONS.md §6h, pain #8): a central registry of friendly,
 * photographer-facing explanations, surfaced by the "?" help sheet on each section header.
 *
 * Relabel-only — the engine still receives the identical params, so this NEVER changes the
 * render and carries zero parity risk. The text lives in res/values/strings_help.xml so it
 * can be translated; this file only maps each control to its three string resources, and
 * ParamHelpTest checks the English text through those ids.
 */
package com.spectrafilm.app

import androidx.annotation.StringRes

/**
 * One control's help: a [title], a one-line [summary] (the at-a-glance "what it does") and a
 * fuller [body] (what it does to the photo + when to reach for it), written for a photographer
 * rather than a film chemist.
 */
data class ParamHelp(
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @StringRes val body: Int,
)

/** Central registry of plain-language help, keyed by a stable id (not the display label). */
object ParamHelpText {
    // Stable keys so relabeling a section never breaks the lookup (or its test).
    const val GRAIN = "grain"
    const val HALATION = "halation"
    const val COUPLERS = "couplers"
    const val PRINT_GAMMA = "print_gamma"
    const val PREFLASH = "preflash"
    const val GLARE = "glare"
    const val CONTRAST_MASK = "contrast_mask"

    val sections: Map<String, ParamHelp> = mapOf(
        GRAIN to ParamHelp(
            title = R.string.help_grain_title,
            summary = R.string.help_grain_summary,
            body = R.string.help_grain_body,
        ),
        HALATION to ParamHelp(
            title = R.string.help_halation_title,
            summary = R.string.help_halation_summary,
            body = R.string.help_halation_body,
        ),
        COUPLERS to ParamHelp(
            title = R.string.help_couplers_title,
            summary = R.string.help_couplers_summary,
            body = R.string.help_couplers_body,
        ),
        PRINT_GAMMA to ParamHelp(
            title = R.string.help_print_gamma_title,
            summary = R.string.help_print_gamma_summary,
            body = R.string.help_print_gamma_body,
        ),
        PREFLASH to ParamHelp(
            title = R.string.help_preflash_title,
            summary = R.string.help_preflash_summary,
            body = R.string.help_preflash_body,
        ),
        GLARE to ParamHelp(
            title = R.string.help_glare_title,
            summary = R.string.help_glare_summary,
            body = R.string.help_glare_body,
        ),
        CONTRAST_MASK to ParamHelp(
            title = R.string.help_contrast_mask_title,
            summary = R.string.help_contrast_mask_summary,
            body = R.string.help_contrast_mask_body,
        ),
    )

    /** The help for [key], or null if none is registered. */
    fun forKey(key: String): ParamHelp? = sections[key]
}
