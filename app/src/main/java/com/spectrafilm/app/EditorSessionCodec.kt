/*
 * Spektrafilm for Android — editor session codec. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Versioned JSON encode/decode of the saved editor session document and its cost
 * limits. Split out of EditorSession.kt (#273).
 */
package com.spectrafilm.app

import java.net.URI
import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

internal class UnsupportedEditorSessionException(message: String) : Exception(message)

internal data class EditorSessionEncodedCost(
    val nodeCount: Int,
    val maxDepth: Int,
    val utf8Bytes: Long,
)

internal object EditorSessionDocumentCodec {
    const val SCHEMA_ID = "org.spektrafilm.editor-session"
    const val SCHEMA_VERSION = 1
    const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024
    private const val MAX_SOURCE_URI_CHARS = 16 * 1024
    private const val MAX_SOURCE_NAME_CHARS = 512
    private const val MAX_EXPORT_NAME_CHARS = 4_096
    // +1 for export.options.border (film border, #360), +1 for export.options.sharpen.
    private const val FIXED_ROOT_NODES = 32L
    private const val FIXED_ROOT_MAX_DEPTH = 4
    private const val DIRECT_PARAMS_DEPTH_OFFSET = 2
    private const val HISTORY_PARAMS_DEPTH_OFFSET = 4
    private const val HISTORY_ROTATION_DEPTH = 5
    private const val JSON_NULL_BYTES = 4L
    private const val JSON_SCALAR_WRAPPER_BYTES = 6L // {"v":<value>}

    private val limits = JsonStructureLimits(
        maxDepth = 48,
        maxNodes = 600_000,
        maxArrayLength = 128,
        maxObjectKeys = 4_096,
        maxStringChars = MAX_SOURCE_URI_CHARS,
        maxTokenChars = 128,
        maxInputChars = MAX_DOCUMENT_BYTES,
    )
    private val snapshotLimits = limits.copy(
        // Two mandatory snapshots must always leave room for the fixed session envelope. Optional
        // history/preset copies are removed by retainEncodableEditorSession when the aggregate root
        // reaches its stricter bound.
        maxDepth = 40,
        maxNodes = 200_000,
        maxInputChars = AtomicJsonStore.MAX_PRESET_BYTES,
    )

    private data class SnapshotEncodedCost(
        val paramsNodes: Int,
        val paramsMaxDepth: Int,
        val encodedSnapshotBytes: Long,
        val encodedParamsBytes: Long,
    ) {
        val snapshotNodes: Long get() = paramsNodes.toLong() + 2L
    }

    private data class MeasuredSnapshot(
        val cost: SnapshotEncodedCost,
        val params: ParamsState?,
    )

    private data class RetentionSelection(
        val undoDropped: Int,
        val redoDropped: Int,
        val dropClipboard: Boolean,
        val dropAnchors: Boolean,
    )

    private class SnapshotSuffixCosts(private val values: List<SnapshotEncodedCost>) {
        private val suffixNodes = LongArray(values.size + 1)
        private val suffixBytes = LongArray(values.size + 1)
        private val suffixDepth = IntArray(values.size + 1)

        init {
            for (index in values.lastIndex downTo 0) {
                suffixNodes[index] = suffixNodes[index + 1] + values[index].snapshotNodes
                suffixBytes[index] = suffixBytes[index + 1] + values[index].encodedSnapshotBytes
                suffixDepth[index] = maxOf(
                    suffixDepth[index + 1],
                    values[index].paramsMaxDepth +
                        EditorSessionDocumentCodec.HISTORY_PARAMS_DEPTH_OFFSET,
                    EditorSessionDocumentCodec.HISTORY_ROTATION_DEPTH,
                )
            }
        }

        fun nodeCount(dropped: Int): Long = suffixNodes[dropped]

        fun maxDepth(dropped: Int): Int = suffixDepth[dropped]

        fun arrayBytes(dropped: Int): Long {
            val count = values.size - dropped
            return EditorSessionDocumentCodec.jsonArrayBytes(count, suffixBytes[dropped])
        }
    }

    private data class CostProfile(
        val document: EditorSessionDocument,
        val current: SnapshotEncodedCost,
        val committed: SnapshotEncodedCost?,
        val undo: SnapshotSuffixCosts,
        val redo: SnapshotSuffixCosts,
        val undoCount: Int,
        val redoCount: Int,
        val base: SnapshotEncodedCost?,
        val full: SnapshotEncodedCost?,
        val clipboard: SnapshotEncodedCost?,
        val sourceBytes: Long,
        val toolBytes: Long,
        val exportBytes: Long,
    ) {
        val maximumRetentionStep: Int = undoCount + redoCount +
            (if (clipboard != null) 1 else 0) + (if (base != null) 1 else 0)

        fun selection(step: Int): RetentionSelection {
            require(step in 0..maximumRetentionStep)
            val undoDropped = minOf(step, undoCount)
            val redoDropped = minOf((step - undoCount).coerceAtLeast(0), redoCount)
            var auxiliaryStep = (step - undoCount - redoCount).coerceAtLeast(0)
            val dropClipboard = clipboard != null && auxiliaryStep-- > 0
            val dropAnchors = base != null && auxiliaryStep > 0
            return RetentionSelection(undoDropped, redoDropped, dropClipboard, dropAnchors)
        }

        fun cost(selection: RetentionSelection): EditorSessionEncodedCost {
            val retainedBase = base.takeUnless { selection.dropAnchors }
            val retainedFull = full.takeUnless { selection.dropAnchors }
            val retainedClipboard = clipboard.takeUnless { selection.dropClipboard }
            val undoBytes = undo.arrayBytes(selection.undoDropped)
            val redoBytes = redo.arrayBytes(selection.redoDropped)
            val historyBytes = EditorSessionDocumentCodec.jsonObjectBytes(
                "undo" to undoBytes,
                "redo" to redoBytes,
            )
            val presetBytes = EditorSessionDocumentCodec.jsonObjectBytes(
                "base" to (retainedBase?.encodedParamsBytes ?: EditorSessionDocumentCodec.JSON_NULL_BYTES),
                "full" to (retainedFull?.encodedParamsBytes ?: EditorSessionDocumentCodec.JSON_NULL_BYTES),
                "amount" to EditorSessionDocumentCodec.jsonScalarBytes(
                    if (selection.dropAnchors) 1.0 else document.preset.amount.toDouble(),
                ),
                "clipboard" to (
                    retainedClipboard?.encodedParamsBytes ?: EditorSessionDocumentCodec.JSON_NULL_BYTES
                ),
                "selectedPreset" to EditorSessionDocumentCodec.jsonScalarBytes(
                    document.preset.selectedPreset,
                ),
                "presetName" to EditorSessionDocumentCodec.jsonScalarBytes(document.preset.presetName),
            )
            val rootFields = ArrayList<Pair<String, Long>>(9).apply {
                add(
                    "schema" to EditorSessionDocumentCodec.jsonScalarBytes(
                        EditorSessionDocumentCodec.SCHEMA_ID,
                    ),
                )
                add(
                    "version" to EditorSessionDocumentCodec.jsonScalarBytes(
                        EditorSessionDocumentCodec.SCHEMA_VERSION,
                    ),
                )
                add("source" to sourceBytes)
                add("current" to current.encodedSnapshotBytes)
                committed?.let { add("committed" to it.encodedSnapshotBytes) }
                add("history" to historyBytes)
                add("tool" to toolBytes)
                add("preset" to presetBytes)
                add("export" to exportBytes)
            }

            var nodes = EditorSessionDocumentCodec.FIXED_ROOT_NODES + current.snapshotNodes +
                undo.nodeCount(selection.undoDropped) + redo.nodeCount(selection.redoDropped)
            committed?.let { nodes += it.snapshotNodes }
            nodes += retainedBase?.paramsNodes?.toLong() ?: 1L
            nodes += retainedFull?.paramsNodes?.toLong() ?: 1L
            nodes += retainedClipboard?.paramsNodes?.toLong() ?: 1L

            var maxDepth = EditorSessionDocumentCodec.FIXED_ROOT_MAX_DEPTH
            maxDepth = maxOf(
                maxDepth,
                current.paramsMaxDepth + EditorSessionDocumentCodec.DIRECT_PARAMS_DEPTH_OFFSET,
            )
            committed?.let {
                maxDepth = maxOf(
                    maxDepth,
                    it.paramsMaxDepth + EditorSessionDocumentCodec.DIRECT_PARAMS_DEPTH_OFFSET,
                )
            }
            maxDepth = maxOf(
                maxDepth,
                undo.maxDepth(selection.undoDropped),
                redo.maxDepth(selection.redoDropped),
            )
            retainedBase?.let {
                maxDepth = maxOf(
                    maxDepth,
                    it.paramsMaxDepth + EditorSessionDocumentCodec.DIRECT_PARAMS_DEPTH_OFFSET,
                )
            }
            retainedFull?.let {
                maxDepth = maxOf(
                    maxDepth,
                    it.paramsMaxDepth + EditorSessionDocumentCodec.DIRECT_PARAMS_DEPTH_OFFSET,
                )
            }
            retainedClipboard?.let {
                maxDepth = maxOf(
                    maxDepth,
                    it.paramsMaxDepth + EditorSessionDocumentCodec.DIRECT_PARAMS_DEPTH_OFFSET,
                )
            }
            return EditorSessionEncodedCost(
                nodeCount = nodes.toInt(),
                maxDepth = maxDepth,
                utf8Bytes = EditorSessionDocumentCodec.jsonObjectBytes(rootFields),
            )
        }
    }

    fun encode(document: EditorSessionDocument): String {
        val profile = buildCostProfile(document)
        val cost = profile.cost(profile.selection(0))
        enforceAggregateCost(cost)
        return materializeAndEncode(document, cost)
    }

    internal fun measureEncodedCost(document: EditorSessionDocument): EditorSessionEncodedCost {
        val profile = buildCostProfile(document)
        return profile.cost(profile.selection(0)).also(::enforceAggregateCost)
    }

    internal fun retainAndEncode(
        document: EditorSessionDocument,
        onFinalRootMaterialization: () -> Unit,
    ): EncodableEditorSession {
        // Profile every branch before deciding what to drop. An invalid oldest history/clipboard
        // entry therefore fails closed instead of disappearing merely because aggregate retention
        // would otherwise remove it. Parsed trees and their canonical strings die one at a time.
        val profile = buildCostProfile(document)
        var retainedSelection: RetentionSelection? = null
        var retainedCost: EditorSessionEncodedCost? = null
        for (step in 0..profile.maximumRetentionStep) {
            val selection = profile.selection(step)
            val cost = profile.cost(selection)
            if (aggregateCostFailure(cost) == null) {
                retainedSelection = selection
                retainedCost = cost
                break
            }
        }
        val selection = retainedSelection ?: throw DocumentLimitException(
            "mandatory current/source/committed editor cursor exceeds the session contract",
        )
        val cost = requireNotNull(retainedCost)
        val retained = document.copy(
            history = EditHistoryState(
                undo = document.history.undo.drop(selection.undoDropped),
                redo = document.history.redo.drop(selection.redoDropped),
            ),
            preset = document.preset.copy(
                baseJson = if (selection.dropAnchors) null else document.preset.baseJson,
                fullJson = if (selection.dropAnchors) null else document.preset.fullJson,
                amount = if (selection.dropAnchors) 1f else document.preset.amount,
                clipboardJson = if (selection.dropClipboard) null else document.preset.clipboardJson,
            ),
        )
        // This callback is deliberately reachable only from the single final whole-root path;
        // subtree profiling and arithmetic candidate selection can never satisfy its regression.
        onFinalRootMaterialization()
        return EncodableEditorSession(retained, materializeAndEncode(retained, cost))
    }

    private fun materializeAndEncode(
        document: EditorSessionDocument,
        expectedCost: EditorSessionEncodedCost,
    ): String {
        val root = JSONObject().apply {
            put("schema", SCHEMA_ID)
            put("version", SCHEMA_VERSION)
            put("source", sourceToJson(document.source))
            put("current", snapshotToJson(document.current))
            document.committed?.let { put("committed", snapshotToJson(it)) }
            put("history", JSONObject().apply {
                put("undo", snapshotsToJson(document.history.undo))
                put("redo", snapshotsToJson(document.history.redo))
            })
            put("tool", JSONObject().apply {
                put("category", document.tool.category?.name ?: JSONObject.NULL)
                put("overlay", document.tool.overlay.name)
                put("maskIndex", document.tool.maskIndex)
            })
            put("preset", presetToJson(document.preset))
            put("export", JSONObject().apply {
                put("sheetOpen", document.export.sheetOpen)
                put("keepGps", document.export.keepGps)
                put("phase", document.export.phase.name)
                put("runtimeRunId", document.export.runtimeRunId ?: JSONObject.NULL)
                put("options", exportOptionsToJson(document.export.options))
            })
        }
        val encoded = root.toString()
        // Validate the one final serialized root with the exact reader preflight. This covers every
        // aggregate limit (including token/string/object/array bounds) and proves the arithmetic
        // profile, without creating a second object tree.
        val actual = AtomicJsonStore.measureText(encoded, limits)
        check(actual.nodeCount == expectedCost.nodeCount) {
            "editor-session node profile disagrees with final serialization"
        }
        check(actual.maxDepth == expectedCost.maxDepth) {
            "editor-session depth profile disagrees with final serialization"
        }
        check(actual.utf8Bytes.toLong() == expectedCost.utf8Bytes) {
            "editor-session cost profile disagrees with final serialization"
        }
        enforceAggregateCost(expectedCost)
        return encoded
    }

    fun decode(text: String): EditorSessionDocument {
        val encodedBytes = AtomicJsonStore.utf8Length(text)
        if (encodedBytes > MAX_DOCUMENT_BYTES) {
            throw DocumentLimitException("editor session exceeds $MAX_DOCUMENT_BYTES bytes")
        }
        val root = AtomicJsonStore.parseObject(text, limits)
        require(root.optString("schema") == SCHEMA_ID) { "unsupported editor-session schema" }
        val version = exactLong(root.opt("version"), "version")
        if (version > SCHEMA_VERSION) {
            throw UnsupportedEditorSessionException("editor session version $version is newer than $SCHEMA_VERSION")
        }
        require(version == SCHEMA_VERSION.toLong()) { "unsupported editor-session version: $version" }

        val source = sourceFromJson(root.requiredObject("source"))
        val current = snapshotFromJson(root.requiredObject("current"))
        val committed = root.opt("committed")
            ?.takeUnless { it === JSONObject.NULL }
            ?.let { value ->
                require(value is JSONObject) { "committed must be an object" }
                snapshotFromJson(value)
            }
        val historyJson = root.requiredObject("history")
        val history = EditHistoryState(
            undo = snapshotsFromJson(historyJson.requiredArray("undo")),
            redo = snapshotsFromJson(historyJson.requiredArray("redo")),
        )
        val toolJson = root.requiredObject("tool")
        val category = toolJson.opt("category")
            ?.takeUnless { it === JSONObject.NULL }
            ?.let {
                require(it is String) { "tool category must be a string" }
                strictEnum<Category>(it, "tool category")
            }
        val tool = EditorToolState(
            category = category,
            overlay = strictEnum(toolJson.requiredString("overlay"), "overlay tool"),
            maskIndex = exactInt(toolJson.opt("maskIndex"), "maskIndex"),
        )
        val preset = presetFromJson(root.requiredObject("preset"))
        val exportJson = root.requiredObject("export")
        val runId = exportJson.opt("runtimeRunId")
            ?.takeUnless { it === JSONObject.NULL }
            ?.let { exactLong(it, "runtimeRunId") }
        val export = EditorExportState(
            sheetOpen = exportJson.requiredBoolean("sheetOpen"),
            options = exportOptionsFromJson(exportJson.requiredObject("options")),
            keepGps = exportJson.requiredBoolean("keepGps"),
            phase = strictEnum(exportJson.requiredString("phase"), "export phase"),
            runtimeRunId = runId,
        )
        return EditorSessionDocument(source, current, committed, history, tool, preset, export).also {
            validateDocument(it)
        }
    }

    private fun validateDocument(document: EditorSessionDocument) {
        buildCostProfile(document)
    }

    private fun buildCostProfile(document: EditorSessionDocument): CostProfile {
        validateSource(document.source)
        require(document.history.undo.size + document.history.redo.size <= EditHistory.DEFAULT_CAP) {
            "editor history exceeds ${EditHistory.DEFAULT_CAP} entries"
        }
        val current = measureSnapshot(document.current, retainParams = true)
        val committed = document.committed?.let { measureSnapshot(it) }
        val undo = document.history.undo.map { measureSnapshot(it) }
        val redo = document.history.redo.map { measureSnapshot(it) }
        // EditSnapshot retains the canonical params string after a disk round trip. Account that
        // durable representation, not a potentially smaller noncanonical input, so admission is
        // symmetric before and after process recreation.
        val historyBytes = (undo + redo).sumOf {
            it.cost.encodedParamsBytes + Int.SIZE_BYTES
        }
        require(historyBytes <= EditHistory.DEFAULT_BYTE_CAP.toLong()) {
            "editor history exceeds ${EditHistory.DEFAULT_BYTE_CAP} bytes"
        }
        require(document.tool.maskIndex in 0 until com.spectrafilm.app.masks.MaskJson.MAX_ADJUSTMENTS) {
            "mask selection is out of range"
        }
        val selectedMask = checkNotNull(current.params).localAdjustments
            .getOrNull(document.tool.maskIndex)
            ?.mask
        if (document.tool.overlay in MASK_TOOLS) {
            requireNotNull(selectedMask) { "selected mask no longer exists" }
        }
        when (document.tool.overlay) {
            EditorOverlayTool.MASK_SAMPLE_COLOR ->
                require(selectedMask?.colorRange != null) { "selected mask has no color range" }
            EditorOverlayTool.MASK_SAMPLE_LUMINANCE ->
                require(selectedMask?.luminanceRange != null) { "selected mask has no luminance range" }
            else -> Unit
        }
        validatePresetScalars(document.preset)
        val base = document.preset.baseJson?.let { measureParamsJson(it) }
        val full = document.preset.fullJson?.let { measureParamsJson(it) }
        val clipboard = document.preset.clipboardJson?.let { measureParamsJson(it) }
        validateExport(document.export)

        val toolJson = JSONObject().apply {
            put("category", document.tool.category?.name ?: JSONObject.NULL)
            put("overlay", document.tool.overlay.name)
            put("maskIndex", document.tool.maskIndex)
        }
        val exportJson = JSONObject().apply {
            put("sheetOpen", document.export.sheetOpen)
            put("keepGps", document.export.keepGps)
            put("phase", document.export.phase.name)
            put("runtimeRunId", document.export.runtimeRunId ?: JSONObject.NULL)
            put("options", exportOptionsToJson(document.export.options))
        }
        return CostProfile(
            document = document,
            current = current.cost,
            committed = committed?.cost,
            undo = SnapshotSuffixCosts(undo.map { it.cost }),
            redo = SnapshotSuffixCosts(redo.map { it.cost }),
            undoCount = undo.size,
            redoCount = redo.size,
            base = base?.cost,
            full = full?.cost,
            clipboard = clipboard?.cost,
            sourceBytes = encodedSmallObjectBytes(sourceToJson(document.source)),
            toolBytes = encodedSmallObjectBytes(toolJson),
            exportBytes = encodedSmallObjectBytes(exportJson),
        )
    }

    private fun validateSource(source: EditorSourceState) {
        require(source.displayName.isNotBlank() && source.displayName.length <= MAX_SOURCE_NAME_CHARS) {
            "invalid source display name"
        }
        when (source.kind) {
            SourceKind.DEMO -> {
                require(source.uri == null) { "demo source must not carry a URI" }
                require(!source.authorizationRequired) { "demo source cannot require authorization" }
            }
            SourceKind.PHOTO, SourceKind.RAW -> {
                val raw = requireNotNull(source.uri) { "non-demo source requires a URI" }
                require(raw.isNotBlank() && raw == raw.trim() && raw.length <= MAX_SOURCE_URI_CHARS) {
                    "invalid source URI"
                }
                val parsed = runCatching { URI(raw) }
                    .getOrElse { throw IllegalArgumentException("malformed source URI", it) }
                require(parsed.scheme.equals("content", ignoreCase = true)) {
                    "only content:// source URIs are accepted"
                }
                require(!parsed.rawAuthority.isNullOrBlank()) { "content URI has no authority" }
            }
        }
    }

    private fun measureSnapshot(
        snapshot: EditSnapshot,
        retainParams: Boolean = false,
    ): MeasuredSnapshot {
        require(snapshot.rotationDegrees in VALID_EDITOR_ROTATIONS) { "invalid editor rotation" }
        val measured = measureParamsJson(snapshot.paramsJson, retainParams)
        return measured.copy(
            cost = measured.cost.copy(
                encodedSnapshotBytes = jsonObjectBytes(
                    "params" to measured.cost.encodedParamsBytes,
                    "rotationDegrees" to jsonScalarBytes(snapshot.rotationDegrees),
                ),
            ),
        )
    }

    private fun measureParamsJson(
        json: String,
        retainParams: Boolean = false,
    ): MeasuredSnapshot {
        val parsed = AtomicJsonStore.parseMeasuredObject(json, snapshotLimits)
        if (parsed.measurement.utf8Bytes > AtomicJsonStore.MAX_PRESET_BYTES) {
            throw DocumentLimitException(
                "editor snapshot input is ${parsed.measurement.utf8Bytes} bytes; " +
                    "limit is ${AtomicJsonStore.MAX_PRESET_BYTES}",
            )
        }
        val canonical = canonicalParams(parsed.value)
        val params = ParamsState().also { Presets.decode(parsed.value, it) }
        // Canonical text is held only for this one subtree. Its byte count is exact for the same
        // JSONObject that final root materialization embeds; only the scalar count survives.
        return MeasuredSnapshot(
            cost = SnapshotEncodedCost(
                paramsNodes = parsed.measurement.nodeCount,
                paramsMaxDepth = parsed.measurement.maxDepth,
                encodedSnapshotBytes = 0L,
                encodedParamsBytes = canonical.utf8Bytes.toLong(),
            ),
            params = params.takeIf { retainParams },
        )
    }

    private data class CanonicalParams(val text: String, val utf8Bytes: Int)

    private fun canonicalParams(value: JSONObject): CanonicalParams {
        val text = value.toString()
        val utf8Bytes = AtomicJsonStore.utf8Length(text)
        if (utf8Bytes > AtomicJsonStore.MAX_PRESET_BYTES) {
            throw DocumentLimitException(
                "editor snapshot canonical JSON is $utf8Bytes bytes; " +
                    "limit is ${AtomicJsonStore.MAX_PRESET_BYTES}",
            )
        }
        return CanonicalParams(text, utf8Bytes)
    }

    private fun validateExport(export: EditorExportState) {
        require(export.options.jpegQuality in 1..100) { "invalid JPEG quality" }
        require(export.options.customLongEdge in 0..ExportOptions.MAX_CUSTOM_EDGE) {
            "invalid custom export edge"
        }
        require(export.options.customName.length <= MAX_EXPORT_NAME_CHARS) {
            "export name is too long"
        }
        require(export.runtimeRunId == null || export.runtimeRunId > 0L) {
            "invalid export run id"
        }
        require((export.phase == EditorExportPhase.RUNNING) == (export.runtimeRunId != null)) {
            "only a running export may carry a run id"
        }
    }

    private fun validatePresetScalars(preset: EditorPresetState) {
        require((preset.baseJson == null) == (preset.fullJson == null)) {
            "preset amount anchors must be present as a pair"
        }
        require(preset.amount.isFinite() && preset.amount in 0f..1f) {
            "invalid preset amount"
        }
        require(preset.selectedPreset.length <= 96 && preset.presetName.length <= 96) {
            "preset selection/name is too long"
        }
    }

    private fun encodedSmallObjectBytes(value: JSONObject): Long {
        AtomicJsonStore.validate(value, limits)
        return AtomicJsonStore.utf8Length(value.toString()).toLong()
    }

    private fun jsonScalarBytes(value: Any?): Long {
        val wrapper = JSONObject().put("v", value ?: JSONObject.NULL).toString()
        return AtomicJsonStore.utf8Length(wrapper).toLong() - JSON_SCALAR_WRAPPER_BYTES
    }

    private fun jsonObjectBytes(vararg fields: Pair<String, Long>): Long =
        jsonObjectBytes(fields.asList())

    private fun jsonObjectBytes(fields: List<Pair<String, Long>>): Long {
        var bytes = 2L // braces
        if (fields.size > 1) bytes += fields.size - 1L
        fields.forEach { (key, valueBytes) ->
            bytes += jsonScalarBytes(key) + 1L + valueBytes // quoted key + colon + value
        }
        return bytes
    }

    private fun jsonArrayBytes(count: Int, valuesBytes: Long): Long =
        2L + valuesBytes + (count - 1).coerceAtLeast(0)

    /*
     * Every variable subtree has already passed the same lexical contract, semantic Presets
     * decode, and canonical-byte measurement. The fixed envelope cannot exceed its collection,
     * string, or token limits, so only the three additive root limits vary with retention. The
     * one final serialized-root preflight below rechecks the complete contract as an invariant.
     */
    private fun aggregateCostFailure(cost: EditorSessionEncodedCost): String? = when {
        cost.maxDepth > limits.maxDepth -> "JSON depth exceeds ${limits.maxDepth}"
        cost.nodeCount > limits.maxNodes -> "JSON node count exceeds ${limits.maxNodes}"
        cost.utf8Bytes > MAX_DOCUMENT_BYTES ->
            "editor session is ${cost.utf8Bytes} bytes; limit is $MAX_DOCUMENT_BYTES"
        else -> null
    }

    private fun enforceAggregateCost(cost: EditorSessionEncodedCost) {
        aggregateCostFailure(cost)?.let { throw DocumentLimitException(it) }
    }

    private fun sourceToJson(source: EditorSourceState) = JSONObject().apply {
        put("uri", source.uri ?: JSONObject.NULL)
        put("kind", source.kind.name)
        put("displayName", source.displayName)
        put("authorizationRequired", source.authorizationRequired)
    }

    private fun sourceFromJson(value: JSONObject) = EditorSourceState(
        uri = value.opt("uri")?.takeUnless { it === JSONObject.NULL }?.let {
            require(it is String) { "source URI must be a string" }
            it
        },
        kind = strictEnum(value.requiredString("kind"), "source kind"),
        displayName = value.requiredString("displayName"),
        authorizationRequired = value.requiredBoolean("authorizationRequired"),
    )

    private fun snapshotToJson(snapshot: EditSnapshot) = JSONObject().apply {
        put("params", AtomicJsonStore.parseObject(snapshot.paramsJson, snapshotLimits))
        put("rotationDegrees", snapshot.rotationDegrees)
    }

    private fun snapshotFromJson(value: JSONObject): EditSnapshot {
        val params = value.requiredObject("params")
        val canonical = canonicalParams(params)
        // Validate now so a poisoned undo entry cannot survive until the user taps Undo.
        Presets.decode(params, ParamsState())
        return EditSnapshot(
            paramsJson = canonical.text,
            rotationDegrees = exactInt(value.opt("rotationDegrees"), "rotationDegrees"),
        )
    }

    private fun snapshotsToJson(snapshots: List<EditSnapshot>) = JSONArray().apply {
        snapshots.forEach { put(snapshotToJson(it)) }
    }

    private fun snapshotsFromJson(array: JSONArray): List<EditSnapshot> =
        List(array.length()) { index ->
            array.optJSONObject(index)
                ?.let(::snapshotFromJson)
                ?: throw IllegalArgumentException("history[$index] must be an object")
        }

    private fun exportOptionsToJson(options: ExportOptions) = JSONObject().apply {
        put("format", options.format.name)
        put("jpegQuality", options.jpegQuality)
        put("size", options.size.name)
        put("customLongEdge", options.customLongEdge)
        put("customName", options.customName)
        put("border", options.border.name)
        put("sharpen", options.sharpen.name)
        if (!options.frame.isDefault) put("frame", options.frame.toJson())
    }

    private fun presetToJson(preset: EditorPresetState) = JSONObject().apply {
        put("base", preset.baseJson?.let(::parseSnapshotObject) ?: JSONObject.NULL)
        put("full", preset.fullJson?.let(::parseSnapshotObject) ?: JSONObject.NULL)
        put("amount", preset.amount.toDouble())
        put("clipboard", preset.clipboardJson?.let(::parseSnapshotObject) ?: JSONObject.NULL)
        put("selectedPreset", preset.selectedPreset)
        put("presetName", preset.presetName)
    }

    private fun presetFromJson(value: JSONObject) = EditorPresetState(
        baseJson = value.optionalObjectString("base"),
        fullJson = value.optionalObjectString("full"),
        amount = value.requiredFiniteFloat("amount"),
        clipboardJson = value.optionalObjectString("clipboard"),
        selectedPreset = value.requiredString("selectedPreset"),
        presetName = value.requiredString("presetName"),
    )

    private fun parseSnapshotObject(text: String): JSONObject =
        AtomicJsonStore.parseObject(text, snapshotLimits)

    private fun exportOptionsFromJson(value: JSONObject) = ExportOptions(
        format = strictEnum(value.requiredString("format"), "export format"),
        jpegQuality = exactInt(value.opt("jpegQuality"), "jpegQuality"),
        size = strictEnum(value.requiredString("size"), "export size"),
        customLongEdge = exactInt(value.opt("customLongEdge"), "customLongEdge"),
        customName = value.requiredString("customName"),
        // Optional: sessions written before film borders existed have no key.
        border = FilmBorder.parse(value.optString("border", FilmBorder.NONE.name)),
        // Added after schema v1 shipped: absent in older journals, which meant no sharpening.
        sharpen = if (value.has("sharpen")) {
            strictEnum(value.requiredString("sharpen"), "export sharpening")
        } else {
            OutputSharpen.NONE
        },
        // Optional: absent before the Frame tab, and whenever the look is the default.
        frame = FrameLook.fromJson(value.optJSONObject("frame")),
    )

    private fun JSONObject.requiredObject(key: String): JSONObject =
        optJSONObject(key) ?: throw IllegalArgumentException("$key must be an object")

    private fun JSONObject.requiredArray(key: String): JSONArray =
        optJSONArray(key) ?: throw IllegalArgumentException("$key must be an array")

    private fun JSONObject.requiredString(key: String): String {
        val value = opt(key)
        require(value is String) { "$key must be a string" }
        return value
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean {
        val value = opt(key)
        require(value is Boolean) { "$key must be a boolean" }
        return value
    }

    private fun JSONObject.optionalObjectString(key: String): String? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        require(value is JSONObject) { "$key must be an object" }
        return value.toString()
    }

    private fun JSONObject.requiredFiniteFloat(key: String): Float {
        val value = opt(key)
        require(value is Number) { "$key must be a number" }
        return value.toDouble().toFloat().also { require(it.isFinite()) { "$key must be finite" } }
    }

    private inline fun <reified T : Enum<T>> strictEnum(value: String, field: String): T =
        runCatching { enumValueOf<T>(value) }
            .getOrElse { throw IllegalArgumentException("unsupported $field: $value") }

    private fun exactInt(value: Any?, field: String): Int {
        val number = exactInteger(value, field)
        require(number >= INT_MIN && number <= INT_MAX) { "$field is outside Int range" }
        return number.toInt()
    }

    private fun exactLong(value: Any?, field: String): Long {
        val number = exactInteger(value, field)
        require(number >= LONG_MIN && number <= LONG_MAX) { "$field is outside Long range" }
        return number.toLong()
    }

    private fun exactInteger(value: Any?, field: String): BigInteger = try {
        when (value) {
            is BigInteger -> value
            is BigDecimal -> value.toBigIntegerExact()
            is Byte, is Short, is Int, is Long -> BigInteger.valueOf((value as Number).toLong())
            is Float -> {
                require(value.isFinite()) { "$field must be finite" }
                BigDecimal(value.toString()).toBigIntegerExact()
            }
            is Double -> {
                require(value.isFinite()) { "$field must be finite" }
                BigDecimal(value.toString()).toBigIntegerExact()
            }
            else -> throw IllegalArgumentException("$field must be an integer")
        }
    } catch (fractional: ArithmeticException) {
        throw IllegalArgumentException("$field must be an integer", fractional)
    }

    private val MASK_TOOLS = setOf(
        EditorOverlayTool.MASK_GEOMETRY,
        EditorOverlayTool.MASK_SAMPLE_COLOR,
        EditorOverlayTool.MASK_SAMPLE_LUMINANCE,
    )
    private val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
}
