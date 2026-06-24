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
 * render and carries zero parity risk. Pure data (no Android/Compose types) so the content
 * coverage is JVM-unit-testable (ParamHelpTest).
 */
package com.spectrafilm.app

/**
 * One control's help: a [title], a one-line [summary] (the at-a-glance "what it does") and a
 * fuller [body] (what it does to the photo + when to reach for it), written for a photographer
 * rather than a film chemist.
 */
data class ParamHelp(
    val title: String,
    val summary: String,
    val body: String,
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

    val sections: Map<String, ParamHelp> = mapOf(
        GRAIN to ParamHelp(
            title = "Grain",
            summary = "The fine, sparkly texture that makes film look like film.",
            body = "Real film is built from microscopic light-sensitive particles, and their random " +
                "clumping is what you see as grain. Bigger particles — a faster, higher-ISO film — give " +
                "coarser, more visible grain; the per-channel scales let one colour layer be grainier " +
                "than the others. Turn it down for a clean, modern look or up for a gritty, vintage one. " +
                "Grain is measured in microns, so it can look finer in the small on-screen preview than " +
                "in your full-resolution export.",
        ),
        HALATION to ParamHelp(
            title = "Halation",
            summary = "The soft reddish glow that blooms around bright highlights.",
            body = "On real film, strong light passes through the emulsion, reflects off the back of " +
                "the base and re-exposes the film around bright areas — street lamps, the sun through " +
                "leaves, specular highlights. \"Halation amount\" sets how strong that glow is, while " +
                "\"scatter\" adds a broader, subtler spread of light inside the emulsion; \"Boost EV\" " +
                "decides how bright a highlight must be before it blooms. Raise it for a dreamy, " +
                "cinematic look, or switch the whole section off for clinical, digital-clean highlights.",
        ),
        COUPLERS to ParamHelp(
            title = "Film colour character (couplers)",
            summary = "Film's signature colour separation, edge punch and saturation roll-off.",
            body = "As film develops, dye forming in one colour layer chemically holds back its " +
                "neighbours — the \"DIR coupler\" effect. That crosstalk is a big part of why film " +
                "colours look the way they do: crisper edges and a distinctive, non-linear saturation. " +
                "\"Effect strength\" scales the whole character, and the cross-colour controls set how " +
                "much each colour bleeds into the others. This is a character control, not a plain " +
                "saturation slider — for ordinary \"more / less colourful\", use Saturation / Vibrance " +
                "under Output.",
        ),
        PRINT_GAMMA to ParamHelp(
            title = "Film & print contrast (gamma)",
            summary = "The raw contrast baked into the negative and the print paper.",
            body = "Every film and paper has a characteristic curve, and its \"gamma\" is how steep " +
                "that curve is — the photochemical equivalent of a contrast control. A higher print " +
                "gamma gives a punchier, higher-contrast print; lower flattens it out. For everyday " +
                "contrast tweaks reach for the Contrast slider first; these are the underlying " +
                "photochemical knobs for shaping the backbone of the look.",
        ),
        PREFLASH to ParamHelp(
            title = "Preflash",
            summary = "A faint, even pre-exposure of the paper that tames harsh contrast.",
            body = "Darkroom printers sometimes flash the paper with a low, uniform light before the " +
                "real exposure. That lifts the deepest shadows and gently lowers contrast in the print " +
                "without touching the highlights. Raise \"Exposure\" for softer, more open shadows; the " +
                "Y and M filter shifts tint that pre-flash light, the same way enlarger filtration does.",
        ),
        GLARE to ParamHelp(
            title = "Glare",
            summary = "Veiling haze from light scattering across the print surface.",
            body = "This models stray light bouncing around the print and scanner, lifting the blacks a " +
                "little and laying a soft haze over the image. It is a random (stochastic) effect, so it " +
                "is left off by default and is not part of the exact, repeatable export. Raise " +
                "\"Percent\" for a hazier, lower-contrast, more nostalgic feel.",
        ),
    )

    /** The help for [key], or null if none is registered. */
    fun forKey(key: String): ParamHelp? = sections[key]
}
