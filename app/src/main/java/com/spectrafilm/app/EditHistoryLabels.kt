/*
 * Spektrafilm for Android — names for edit-history steps. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Interaction rule: releasing a control creates a NAMED history entry. The history itself
 * stores only full snapshots (EditSnapshot), and settleDecision compares snapshots for
 * equality, so a label field there would turn "same state, different name" into a fake
 * edit. The name is therefore derived instead: diff two neighbouring snapshots and map the
 * changed JSON paths onto the editor's tools through CATEGORY_SECTIONS, the same table the
 * edited-dot and per-tool reset use.
 */
package com.spectrafilm.app

import org.json.JSONObject

/** What a history step changed, in tool terms. */
internal sealed interface HistoryTag {
    data class Tool(val category: Category) : HistoryTag
    data object Crop : HistoryTag
    data object Straighten : HistoryTag
    data object Rotate : HistoryTag
    /** A change no tool owns (an older schema key, say). Still an undo step. */
    data object Other : HistoryTag
}

/**
 * One step's name: the tools it touched, in [Category] order, and, when exactly one
 * numeric or on/off value changed, that new value (a drag of one slider reads
 * "Exposure +0.70", not just "Exposure").
 */
internal data class HistoryLabel(
    val tags: List<HistoryTag>,
    val number: Double? = null,
    val toggle: Boolean? = null,
)

/** Keys every snapshot carries that are not edits. */
private val IGNORED_KEYS = setOf("schema", "version")

/**
 * The changed JSON paths between two encoded params objects, one level deep: a changed
 * leaf inside a section object is reported as "section.key", anything else as "key".
 */
internal fun changedPaths(before: JSONObject, after: JSONObject): List<String> {
    val out = ArrayList<String>()
    val keys = (before.keys().asSequence() + after.keys().asSequence()).toSortedSet()
    for (k in keys) {
        if (k in IGNORED_KEYS) continue
        val a = before.opt(k)
        val b = after.opt(k)
        if (a is JSONObject && b is JSONObject) {
            val sub = (a.keys().asSequence() + b.keys().asSequence()).toSortedSet()
            for (s in sub) {
                if (a.opt(s)?.toString() != b.opt(s)?.toString()) out += "$k.$s"
            }
        } else if (a?.toString() != b?.toString()) {
            out += k
        }
    }
    return out
}

/** The tag a changed path belongs to. */
internal fun tagForPath(path: String): HistoryTag {
    when (path) {
        "filmProfile" -> return HistoryTag.Tool(Category.SIMULATION)
        "printProfile" -> return HistoryTag.Tool(Category.PAPER)
        "input.crop" -> return HistoryTag.Crop
        "input.straighten" -> return HistoryTag.Straighten
    }
    val section = path.substringBefore('.')
    // An exact "section.key" entry wins over a whole-section one, so camera.diffusion is
    // the Diffusion filter and not whatever else claims "camera".
    CATEGORY_SECTIONS.entries.firstOrNull { (_, paths) -> path in paths }
        ?.let { return HistoryTag.Tool(it.key) }
    CATEGORY_SECTIONS.entries.firstOrNull { (_, paths) -> section in paths }
        ?.let { return HistoryTag.Tool(it.key) }
    return HistoryTag.Other
}

/** Names the step from [before] to [after]; null when nothing differs. */
internal fun describeStep(before: EditSnapshot, after: EditSnapshot): HistoryLabel? {
    val a = runCatching { JSONObject(before.paramsJson) }.getOrNull()
    val b = runCatching { JSONObject(after.paramsJson) }.getOrNull()
    val paths = if (a != null && b != null) changedPaths(a, b) else emptyList()
    val tags = LinkedHashSet<HistoryTag>()
    paths.mapTo(tags, ::tagForPath)
    if (before.rotationDegrees != after.rotationDegrees) tags += HistoryTag.Rotate
    if (tags.isEmpty()) {
        // Unparseable JSON that still differs is a change we cannot name, not no change.
        return if (before != after) HistoryLabel(listOf(HistoryTag.Other)) else null
    }
    val ordered = tags.sortedBy { tagOrder(it) }
    var number: Double? = null
    var toggle: Boolean? = null
    if (paths.size == 1 && b != null) {
        when (val v = leaf(b, paths.single())) {
            is Boolean -> toggle = v
            is Number -> number = v.toDouble()
        }
    }
    return HistoryLabel(ordered, number, toggle)
}

private fun tagOrder(tag: HistoryTag): Int = when (tag) {
    is HistoryTag.Tool -> tag.category.ordinal
    HistoryTag.Crop -> Category.entries.size
    HistoryTag.Straighten -> Category.entries.size + 1
    HistoryTag.Rotate -> Category.entries.size + 2
    HistoryTag.Other -> Category.entries.size + 3
}

private fun leaf(o: JSONObject, path: String): Any? {
    val dot = path.indexOf('.')
    return if (dot < 0) o.opt(path) else o.optJSONObject(path.substring(0, dot))?.opt(path.substring(dot + 1))
}

/**
 * The whole history as one timeline, oldest first: the undo branch, the live state, then
 * the redo branch in the order redo would reach it. [EditHistory.redo] pops the LAST redo
 * entry, so the branch is reversed. Returns the list and the index of the live state.
 */
internal fun historyTimeline(state: EditHistoryState, current: EditSnapshot): Pair<List<EditSnapshot>, Int> =
    (state.undo + current + state.redo.asReversed()) to state.undo.size

/** The localized name of a history step, e.g. "Exposure +0.70" or "Grain, Halation". */
@androidx.compose.runtime.Composable
internal fun historyLabelText(label: HistoryLabel?): String {
    if (label == null) return androidx.compose.ui.res.stringResource(R.string.editor_history_start)
    val names = label.tags.map { tag ->
        when (tag) {
            is HistoryTag.Tool -> androidx.compose.ui.res.stringResource(tag.category.labelRes)
            HistoryTag.Crop -> androidx.compose.ui.res.stringResource(R.string.tool_crop_title)
            HistoryTag.Straighten -> androidx.compose.ui.res.stringResource(R.string.tool_crop_straighten)
            HistoryTag.Rotate -> androidx.compose.ui.res.stringResource(R.string.editor_history_rotate)
            HistoryTag.Other -> androidx.compose.ui.res.stringResource(R.string.editor_history_other)
        }
    }.joinToString(", ")
    val value = when {
        label.toggle == true -> androidx.compose.ui.res.stringResource(R.string.editor_history_on)
        label.toggle == false -> androidx.compose.ui.res.stringResource(R.string.editor_history_off)
        label.number != null -> formatHistoryNumber(label.number)
        else -> null
    }
    return if (value == null) names else "$names $value"
}

/** A step's new value: signed, whole numbers without decimals, others to two places. */
internal fun formatHistoryNumber(v: Double): String {
    val rounded = kotlin.math.round(v * 100) / 100
    return if (rounded == kotlin.math.floor(rounded)) {
        "%+d".format(java.util.Locale.ROOT, rounded.toLong())
    } else {
        "%+.2f".format(java.util.Locale.ROOT, rounded)
    }
}
