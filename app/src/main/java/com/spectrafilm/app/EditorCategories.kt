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
import org.json.JSONObject
import androidx.compose.animation.core.tween

internal enum class Category(val label: String, @StringRes val labelRes: Int) {
    // One tool each. Enum order is the order inside a section, and a section's tools are
    // stacked in that order (StageTaxonomyTest pins the first of each). The names are also
    // the persisted editor-session value, so an existing name is never renamed or removed.
    // Looks: what you start from.
    SIMULATION("Film stock", R.string.editor_category_film_stock),
    PAPER("Paper", R.string.editor_category_paper),
    PRESETS("Presets", R.string.editor_category_presets),
    SOURCE("Photo", R.string.editor_category_source),
    // Light
    EXPOSURE("Exposure", R.string.editor_category_exposure),
    TONE_CURVE("Tone Curve", R.string.editor_category_tone_curve),
    SCENE_LATITUDE("Highlights & shadows", R.string.editor_category_scene_latitude),
    CONTRAST_MASK("Contrast mask", R.string.editor_category_contrast_mask),
    // Color
    RAW_WB("White balance", R.string.editor_category_raw_wb),
    SAT_VIB("Saturation", R.string.editor_category_sat_vib),
    PRINT_COLOR("Print color", R.string.editor_category_print_color),
    COUPLERS("Couplers", R.string.editor_category_couplers),
    // Film
    FILM_FORMAT("Film format", R.string.editor_category_film_format),
    GRAIN("Grain", R.string.editor_category_grain),
    HALATION("Halation", R.string.editor_category_halation),
    CAMERA_DIFFUSION("Diffusion filter", R.string.editor_category_camera_diffusion),
    // Print
    PRINT_EXPOSURE("Print exposure", R.string.editor_category_print_exposure),
    PREFLASH("Preflash", R.string.editor_category_preflash),
    ENLARGER("Enlarger lens", R.string.editor_category_enlarger),
    // Effects
    GLARE("Glare", R.string.editor_category_glare),
    LENS_BLUR("Lens blur", R.string.editor_category_lens_blur),
    DATE_STAMP("Date stamp", R.string.editor_category_date_stamp),
    // Detail
    SCANNER("Scan", R.string.editor_category_scanner),
    RAW_CLEANUP("RAW cleanup", R.string.editor_category_raw_cleanup),
    // Output
    OUTPUT("Output", R.string.editor_sim_tab_output),
    DISPLAY("Display", R.string.editor_category_display),
    // Lab
    INPUT("Spectral input", R.string.editor_category_input),
    EXPERIMENTAL("Experimental", R.string.editor_category_experimental),
    // Masks
    MASKS("Masks", R.string.editor_category_masks),
}

/**
 * The editor's bottom bar: where you start (Looks), the adjustments (Edit) and local
 * adjustments (Masks). Crop sits beside them as an action, not a destination.
 *
 * The earlier top level was the engine's own pipeline (Look, Film, Print, Scan, Finish). It
 * read well as a diagram and badly as a tool box: Exposure lived inside the film stock panel,
 * white balance was split across two stages, and Saturation sat under Output. These three are
 * what a photographer reaches for; the darkroom words survive as Edit's sections.
 */
internal enum class EditorMode(@StringRes val labelRes: Int, @StringRes val hintRes: Int) {
    LOOKS(R.string.editor_mode_looks, R.string.editor_mode_looks_hint),
    EDIT(R.string.editor_mode_edit, R.string.editor_mode_edit_hint),
    MASKS(R.string.editor_mode_masks, R.string.editor_mode_masks_hint),
}

/**
 * A group of tools shown together: Looks and Masks are one group each; Edit has eight
 * sections, by what each changes (Light, Color, ...), shown as tabs over a stacked list.
 */
internal enum class Stage(@StringRes val labelRes: Int, @StringRes val hintRes: Int, val mode: EditorMode) {
    LOOKS(R.string.editor_mode_looks, R.string.editor_mode_looks_hint, EditorMode.LOOKS),
    LIGHT(R.string.editor_section_light, R.string.editor_section_light_hint, EditorMode.EDIT),
    COLOR(R.string.editor_section_color, R.string.editor_section_color_hint, EditorMode.EDIT),
    FILM(R.string.editor_section_film, R.string.editor_section_film_hint, EditorMode.EDIT),
    PRINT(R.string.editor_section_print, R.string.editor_section_print_hint, EditorMode.EDIT),
    EFFECTS(R.string.editor_section_effects, R.string.editor_section_effects_hint, EditorMode.EDIT),
    DETAIL(R.string.editor_section_detail, R.string.editor_section_detail_hint, EditorMode.EDIT),
    OUTPUT(R.string.editor_section_output, R.string.editor_section_output_hint, EditorMode.EDIT),
    LAB(R.string.editor_section_lab, R.string.editor_section_lab_hint, EditorMode.EDIT),
    MASKS(R.string.editor_mode_masks, R.string.editor_mode_masks_hint, EditorMode.MASKS),
}

/** The stages (sections) a mode holds, in enum order. */
internal fun stagesOf(mode: EditorMode): List<Stage> = Stage.entries.filter { it.mode == mode }

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

/** Which section each tool belongs to. Every Category must appear exactly once. */
internal val Category.stage: Stage
    get() = when (this) {
        Category.SIMULATION, Category.PAPER, Category.PRESETS, Category.SOURCE -> Stage.LOOKS
        Category.EXPOSURE, Category.TONE_CURVE, Category.SCENE_LATITUDE,
        Category.CONTRAST_MASK -> Stage.LIGHT
        Category.RAW_WB, Category.SAT_VIB, Category.PRINT_COLOR, Category.COUPLERS -> Stage.COLOR
        Category.FILM_FORMAT, Category.GRAIN, Category.HALATION,
        Category.CAMERA_DIFFUSION -> Stage.FILM
        Category.PRINT_EXPOSURE, Category.PREFLASH, Category.ENLARGER -> Stage.PRINT
        Category.GLARE, Category.LENS_BLUR, Category.DATE_STAMP -> Stage.EFFECTS
        Category.SCANNER, Category.RAW_CLEANUP -> Stage.DETAIL
        Category.OUTPUT, Category.DISPLAY -> Stage.OUTPUT
        Category.INPUT, Category.EXPERIMENTAL -> Stage.LAB
        Category.MASKS -> Stage.MASKS
    }

/** The mode a tool opens in. */
internal val Category.mode: EditorMode get() = stage.mode

/**
 * The panel to show after the user opens a new photo: always Film & look (SIMULATION).
 * Picking a stock is the next step for almost everyone, and whatever panel was open belonged
 * to the previous photo -- often a session restored from days ago (found on the device test:
 * a leftover Contrast mask panel kept Film & look from opening).
 */
internal val CATEGORY_AFTER_NEW_PHOTO: Category = Category.SIMULATION

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
 * The encoded parameters each tool owns, for the "this tool is doing something" dot and the
 * per-tool reset.
 *
 * An entry is either a whole top-level `Presets.encode` section ("grain") or one key inside
 * one ("camera.exposureCompensationEv"). Keys matter since tools were split by what they do
 * rather than by engine stage: Exposure, Film format and Lens blur all live in "camera", and
 * a reset of one must leave the others alone. `Presets.applyJson` keeps the current value of
 * any key a section object leaves out, which is what makes a one-key reset safe.
 *
 * SIMULATION and PAPER are pickers (the stock is the look, not an edit); PRESETS, SOURCE and
 * DISPLAY are pickers and app settings rather than parameter groups. A tool that is absent
 * simply shows no dot: a missing dot is a smaller defect than a wrong one, and this table is
 * the thing that keeps it honest.
 */
internal val CATEGORY_SECTIONS: Map<Category, List<String>> = mapOf(
    Category.EXPOSURE to listOf("camera.exposureCompensationEv", "camera.autoExposure", "camera.autoExposureMethod"),
    Category.TONE_CURVE to listOf("toneCurve", "grade.contrast"),
    Category.SCENE_LATITUDE to listOf("camera.sceneLatitude"),
    Category.CONTRAST_MASK to listOf("contrastMask"),
    Category.RAW_WB to listOf("raw.whiteBalance", "raw.temperature", "raw.tint", "creativeWb"),
    Category.SAT_VIB to listOf("grade.saturation", "grade.vibrance"),
    Category.PRINT_COLOR to listOf("enlarger.yFilterShift", "enlarger.mFilterShift"),
    Category.COUPLERS to listOf("couplers"),
    Category.FILM_FORMAT to listOf("camera.filmFormatMm"),
    Category.GRAIN to listOf("grain"),
    Category.HALATION to listOf("halation"),
    Category.CAMERA_DIFFUSION to listOf("camera.diffusion"),
    Category.PRINT_EXPOSURE to listOf("enlarger.printExposure", "enlarger.printExposureCompensation", "enlarger.paperGrade"),
    Category.PREFLASH to listOf("enlarger.preflashExposure", "enlarger.preflashYFilterShift", "enlarger.preflashMFilterShift"),
    Category.ENLARGER to listOf("enlarger.lensBlur", "enlarger.diffusion"),
    Category.GLARE to listOf("glare"),
    Category.LENS_BLUR to listOf("camera.lensBlurUm"),
    Category.DATE_STAMP to listOf("camera.dateStamp"),
    Category.SCANNER to listOf("scanner"),
    Category.RAW_CLEANUP to listOf("raw.hotPixels", "raw.chromaNoise"),
    Category.OUTPUT to listOf(
        "output.outputColorSpace", "output.savingCctfEncoding", "output.outputGamutCompress",
        "output.inputGamutCompress", "grade.gamutCompress",
    ),
    Category.INPUT to listOf("input"),
    Category.EXPERIMENTAL to listOf("experimental"),
    Category.MASKS to listOf("masks"),
)

/** The value at a [CATEGORY_SECTIONS] entry ("section" or "section.key"), or null. */
private fun JSONObject.entry(path: String): Any? {
    val dot = path.indexOf('.')
    if (dot < 0) return opt(path)
    return optJSONObject(path.substring(0, dot))?.opt(path.substring(dot + 1))
}

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
            current.entry(key)?.toString() != defaults.entry(key)?.toString()
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
 * row compounds them (`enlarger.paperGrade` is authored only by Tri-X 400, so no colour
 * look can put it back). The editor therefore rewinds to the look a browsing
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
    for (path in sections) {
        val v = defaults.entry(path) ?: return null
        val copy: Any = when (v) {
            is JSONObject -> JSONObject(v.toString())
            is org.json.JSONArray -> org.json.JSONArray(v.toString())
            else -> v
        }
        val dot = path.indexOf('.')
        if (dot < 0) {
            out.put(path, copy)
        } else {
            val section = path.substring(0, dot)
            val holder = out.optJSONObject(section) ?: JSONObject().also { out.put(section, it) }
            holder.put(path.substring(dot + 1), copy)
        }
    }
    return out
}
