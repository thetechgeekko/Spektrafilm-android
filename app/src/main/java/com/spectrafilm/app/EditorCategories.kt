/*
 * Spektrafilm for Android — editor categories and stages. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The bottom-rail taxonomy (Category -> Stage), the per-category "dirty" dot and the
 * per-category reset baseline. Moved out of MainActivity.kt (#273).
 */
package com.spectrafilm.app

import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import org.json.JSONObject
import androidx.compose.animation.core.tween

internal enum class Category(val label: String, @StringRes val labelRes: Int) {
    SOURCE("Source", R.string.editor_category_source),
    PRESETS("Presets", R.string.editor_category_presets),
    SIMULATION("Simulation", R.string.editor_category_simulation),
    INPUT("Input", R.string.editor_category_input),
    RAW_WB("White Bal", R.string.editor_category_raw_wb),
    GRAIN("Grain", R.string.editor_category_grain),
    HALATION("Halation", R.string.editor_category_halation),
    GLARE("Glare", R.string.editor_category_glare),
    COUPLERS("Couplers", R.string.editor_category_couplers),
    PREFLASH("Preflash", R.string.editor_category_preflash),
    CONTRAST_MASK("Contrast mask", R.string.editor_category_contrast_mask),
    EXPERIMENTAL("Experimental", R.string.editor_category_experimental),
    TONE_CURVE("Tone Curve", R.string.editor_category_tone_curve),
    MASKS("Masks", R.string.editor_category_masks),
    DISPLAY("Display", R.string.editor_category_display),
}

/**
 * The darkroom the engine actually simulates, as the editor's top level.
 *
 * Fourteen [Category] chips in one scrolling row mixed three incompatible kinds of thing --
 * modes (SOURCE, PRESETS), tools (MASKS, DISPLAY) and parameter groups -- and named several
 * of them after engine internals (COUPLERS, PREFLASH, GLARE) rather than anything a
 * photographer says out loud. Worse, the correct taxonomy was already in the file, one level
 * too deep: SIMULATION contains a Film / Print / Scanner / Output sub-tab row.
 *
 * These five are `runtime/stages/` -- filming -> printing -> scanning -- plus the look you
 * start from and the grade you end with. Every stage is nameable; none is a chemical.
 *
 * PRINT and SCAN each hold a single group today, and that is honest rather than tidy: the
 * print and scan parameters live inside SIMULATION's own sub-tabs, and prising them out is a
 * separate change to that section's internals. A stage holding one group opens it directly,
 * so the thinness costs no extra tap.
 */
internal enum class Stage(@StringRes val labelRes: Int, @StringRes val hintRes: Int) {
    LOOK(R.string.editor_stage_look, R.string.editor_stage_look_hint),
    FILM(R.string.editor_stage_film, R.string.editor_stage_film_hint),
    PRINT(R.string.editor_stage_print, R.string.editor_stage_print_hint),
    SCAN(R.string.editor_stage_scan, R.string.editor_stage_scan_hint),
    FINISH(R.string.editor_stage_finish, R.string.editor_stage_finish_hint),
}

/**
 * Motion specs for the editor rail.
 *
 * All three drive PAINT or a width inside a chip -- never the rail's own height. The preview
 * is a `weight(1f)` sibling of the rail, so an animated rail height re-measures the preview
 * on every frame of the animation, which is the churn that forced the GPU preview surface off
 * and jolted the CPU preview (documented on PanelOverlay). Colour and a 20dp indicator cost
 * the renderer nothing.
 *
 * Springs rather than tweens, to match the panel's existing entrance vocabulary.
 */
internal val RAIL_PRESS = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
internal val RAIL_PAINT = tween<Color>(durationMillis = 180)
internal val RAIL_PILL = spring<Dp>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

/** Which stage each existing category belongs to. Every Category must appear exactly once. */
internal val Category.stage: Stage
    get() = when (this) {
        Category.SOURCE, Category.PRESETS -> Stage.LOOK
        Category.SIMULATION, Category.INPUT, Category.RAW_WB,
        Category.GRAIN, Category.HALATION, Category.COUPLERS -> Stage.FILM
        Category.PREFLASH, Category.CONTRAST_MASK -> Stage.PRINT
        Category.GLARE -> Stage.SCAN
        Category.TONE_CURVE, Category.MASKS, Category.DISPLAY,
        Category.EXPERIMENTAL -> Stage.FINISH
    }

/** The categories in a stage, in enum order. */
internal fun categoriesOf(stage: Stage): List<Category> =
    Category.entries.filter { it.stage == stage }

// Neutral parameter defaults (a fresh ParamsState) — source for slider
// double-tap-to-reset targets.
internal val PARAM_DEFAULTS = ParamsState()

/** Date-back LED colours (linear ProPhoto): the classic orange, a deeper red, and amber. */
internal val DATE_STAMP_COLORS = listOf(
    Triple(1.0f, 0.18f, 0.0f) to R.string.editor_sim_date_stamp_color_orange,
    Triple(1.0f, 0.06f, 0.0f) to R.string.editor_sim_date_stamp_color_red,
    Triple(1.0f, 0.45f, 0.02f) to R.string.editor_sim_date_stamp_color_amber,
)

/**
 * The encoded section each category owns, for the "this category is doing something" dot.
 *
 * Only categories whose panel maps EXACTLY onto one or more top-level `Presets.encode`
 * sections appear here. SIMULATION and PREFLASH both edit fields inside `enlarger`, so
 * neither can be attributed from the encoding without the dot lying about the other;
 * SOURCE, PRESETS and DISPLAY are pickers and app settings rather than parameter groups.
 * A category that is absent simply shows no dot — a missing dot is a smaller defect than
 * a wrong one, and this table is the thing that keeps it honest.
 */
internal val CATEGORY_SECTIONS: Map<Category, List<String>> = mapOf(
    Category.INPUT to listOf("input"),
    Category.RAW_WB to listOf("raw", "creativeWb"),
    Category.GRAIN to listOf("grain"),
    Category.HALATION to listOf("halation"),
    Category.GLARE to listOf("glare"),
    Category.CONTRAST_MASK to listOf("contrastMask"),
    Category.COUPLERS to listOf("couplers"),
    Category.EXPERIMENTAL to listOf("experimental"),
    Category.TONE_CURVE to listOf("toneCurve"),
    Category.MASKS to listOf("masks"),
)

/** Encoded neutral state, built once; the right-hand side of every dirty comparison. */
private val DEFAULT_ENCODED: JSONObject? by lazy {
    runCatching { Presets.encode(PARAM_DEFAULTS) }.getOrNull()
}

/**
 * Which categories currently differ from a fresh [ParamsState].
 *
 * Compares the JSON each section encodes to, rather than field-by-field, so a new parameter
 * is covered the moment it is added to `Presets.toJson` and cannot silently fall out of the
 * dot. Encoding validates operational limits and can throw mid-edit, so a failure yields an
 * empty set: no dots, never a crash.
 */
internal fun dirtyCategories(state: ParamsState): Set<Category> {
    val defaults = DEFAULT_ENCODED ?: return emptySet()
    val current = runCatching { Presets.encode(state) }.getOrNull() ?: return emptySet()
    return CATEGORY_SECTIONS.entries.mapNotNullTo(mutableSetOf()) { (category, sections) ->
        val changed = sections.any { key ->
            current.opt(key)?.toString() != defaults.opt(key)?.toString()
        }
        category.takeIf { changed }
    }
}

/**
 * The neutral value of just one category, as a partial recipe.
 *
 * [Presets.applyJson] reads each section behind its own `optJSONObject(...)?.let { }`, so an
 * object carrying only this category's sections provably cannot touch any other parameter.
 * That is what makes a per-category reset safe to build out of the same defaults the dirty
 * dot already compares against, rather than a hand-written list of fields to clear — which
 * is where a forgotten field would silently survive the reset.
 *
 * The section values are deep-copied. [DEFAULT_ENCODED] is a lazily-built singleton shared by
 * every dirty comparison in the app; handing a live reference to a decoder is a mutation
 * hazard that would corrupt every later comparison at once, for no saving.
 */
/**
 * The look a built-in preset should be overlaid onto.
 *
 * Built-ins are sparse: each authors only the fields it means to set, so applying two in a
 * row compounds them (`camera.diffusionFilter` is authored by exactly one of the 27, so
 * nothing else can switch it back off). The editor therefore rewinds to the look a browsing
 * run STARTED from before overlaying the next preset.
 *
 * A run continues only while [now] still equals what the last apply produced ([lastOutput]).
 * Anything else — a hand edit, a reset, a new photo — makes the current look the new
 * baseline, so manual work is never silently discarded. Returning [now] when the run is not
 * continuing is what makes the caller's rewind a no-op in that case.
 */
internal fun presetRunBaseline(now: String?, lastOutput: String?, runBaseline: String?): String? =
    if (now != null && now == lastOutput) (runBaseline ?: now) else now

internal fun categoryDefaults(category: Category): JSONObject? {
    val defaults = DEFAULT_ENCODED ?: return null
    val sections = CATEGORY_SECTIONS[category] ?: return null
    val out = JSONObject()
    for (key in sections) {
        when (val v = defaults.opt(key)) {
            null -> return null
            is JSONObject -> out.put(key, JSONObject(v.toString()))
            is org.json.JSONArray -> out.put(key, org.json.JSONArray(v.toString()))
            else -> out.put(key, v)
        }
    }
    return out
}
