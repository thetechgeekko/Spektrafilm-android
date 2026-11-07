/*
 * Spektrafilm for Android — the editor's Presets panel and its callbacks. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Presets panel with every callback it runs: built-in and saved presets, the amount
 * blend, copy/paste of settings, LUT export and the photo's named versions. Moved verbatim
 * out of EditorScreen (#273); editor `var`s arrive as their state holders.
 */
@Composable
internal fun EditorPresetPanel(
    dispatchers: AppDispatchers,
    presetVm: PresetViewModel,
    versionsVm: VersionsViewModel,
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    presetRepo: PresetRepository,
    snackbarHost: SnackbarHostState,
    recipeKey: String?,
    builtInGroups: Map<String, List<BuiltInPreset>>,
    presetUi: PresetUiState,
    presetImporter: ManagedActivityResultLauncher<Array<String>, Uri?>,
    presetExporter: ManagedActivityResultLauncher<String, Uri?>,
    savedPresetExporter: ManagedActivityResultLauncher<String, Uri?>,
    lutExporter: ManagedActivityResultLauncher<String, Uri?>,
    presetListState: MutableState<List<String>>,
    presetBaselineJsonState: MutableState<String?>,
    lastBuiltInOutputState: MutableState<String?>,
    lastBuiltInIdState: MutableState<String?>,
    presetPreviewHost: StockPreviewHost?,
    stripSourceKey: Any?,
    stripPaused: Boolean,
    bakingLutState: MutableState<Boolean>,
    pendingLutTextState: MutableState<String?>,
    pendingPresetExportState: MutableState<String?>,
    rotationState: MutableState<SourceRotation>,
    statusState: MutableState<String>,
    previewTickState: MutableIntState,
    engineState: State<SpektraEngine?>,
    applyWithAmountFn: (baseOverride: String?, apply: () -> Unit) -> Unit,
    previousEdit: PreviousEdit?,
    onApplyPrevious: () -> Unit,
) {
    var presetList by presetListState
    var presetBaselineJson by presetBaselineJsonState
    var lastBuiltInOutput by lastBuiltInOutputState
    var lastBuiltInId by lastBuiltInIdState
    var bakingLut by bakingLutState
    var pendingLutText by pendingLutTextState
    var pendingPresetExport by pendingPresetExportState
    var rotation by rotationState
    var status by statusState
    var previewTick by previewTickState
    val engine by engineState
    fun applyWithAmount(baseOverride: String? = null, apply: () -> Unit) = applyWithAmountFn(baseOverride, apply)
    // The strip marks the look on screen: the last one applied, until any other edit makes
    // the photo something else.
    val appliedBuiltIn by remember {
        derivedStateOf {
            val id = lastBuiltInId
            if (id != null && runCatching { Presets.toJsonString(state) }.getOrNull() == lastBuiltInOutput) id else ""
        }
    }
    PresetPanel(
        builtInGroups = builtInGroups,
        appliedBuiltInId = appliedBuiltIn,
        previewHost = presetPreviewHost,
        stripSourceKey = stripSourceKey,
        stripPaused = stripPaused,
        onApplyBuiltIn = { p ->
            val now = runCatching { Presets.toJsonString(state) }.getOrNull()
            val baseline =
                presetRunBaseline(now, lastBuiltInOutput, presetBaselineJson)
            presetBaselineJson = baseline
            applyWithAmount(baseline) {
                // Rewind before overlaying, so a sparse preset cannot
                // inherit the previous one's fields. When the run is not
                // continuing the baseline IS [now], so this is a no-op —
                // one source of truth, in presetRunBaseline.
                if (baseline != null && baseline != now) {
                    runCatching {
                        Presets.decode(org.json.JSONObject(baseline), state)
                    }
                }
                BuiltInPresets.apply(p, state)
            }
            lastBuiltInOutput = runCatching { Presets.toJsonString(state) }.getOrNull()
            lastBuiltInId = p.id
            status = ctx.getString(R.string.editor_status_applied_builtin, p.name); previewTick++
        },
        amount = presetUi.amount,
        amountEnabled = presetUi.amountEnabled,
        onAmountChange = { a ->
            presetVm.onAction(PresetAction.SetAmount(a))
            val base = presetVm.current.baseJson; val full = presetVm.current.fullJson
            if (base != null && full != null) {
                runCatching {
                    val blended = PresetAmount.blend(
                        org.json.JSONObject(base), org.json.JSONObject(full), a,
                    )
                    Presets.decodeLook(blended, state)
                }.onFailure { Diag.w("preset amount blend failed: ${it.message}") }
                previewTick++
            }
        },
        presets = presetList,
        selected = presetUi.selected,
        name = presetUi.name,
        onNameChange = { presetVm.onAction(PresetAction.SetName(it)) },
        onSelect = { presetVm.onAction(PresetAction.Select(it)) },
        onSave = {
            if (presetVm.current.name.isNotBlank()) {
                val name = presetVm.current.name
                // Serialize on main (toJsonString reads live Compose
                // state field-by-field — a torn snapshot off-main);
                // only the file write + listing cross to IO.
                scope.launch {
                    val json = Presets.toJsonString(state)
                    val names = withContext(dispatchers.io) {
                        presetRepo.saveJson(name, json); presetRepo.list()
                    }
                    presetList = names
                    // Report the name on disk, not the typed one:
                    // safeName may have rewritten it.
                    val stored = presetRepo.resolveName(name)
                    presetVm.onAction(PresetAction.Select(stored))
                    status = ctx.getString(R.string.editor_status_preset_saved, stored)
                }
            }
        },
        nameExists = { presetRepo.exists(it) },
        resolveName = { presetRepo.resolveName(it) },
        onExportSelected = {
            val selectedPreset = presetVm.current.selected
            if (selectedPreset.isNotBlank()) {
                pendingPresetExport = selectedPreset
                savedPresetExporter.launch("$selectedPreset.json")
            }
        },
        onApply = {
            if (presetVm.current.selected.isNotBlank()) {
                val name = presetVm.current.selected
                // Read the JSON off-main; decode (Compose-state write)
                // + applyWithAmount run back on the main thread.
                scope.launch {
                    val text = withContext(dispatchers.io) {
                        runCatching { presetRepo.read(name) }.getOrNull()
                    }
                    if (text == null) { status = ctx.getString(R.string.editor_status_preset_apply_failed); return@launch }
                    runCatching {
                        applyWithAmount { Presets.decodeLook(org.json.JSONObject(text), state) }
                    }
                        .onSuccess { status = ctx.getString(R.string.editor_status_preset_applied, name); previewTick++ }
                        .onFailure { status = ctx.getString(R.string.editor_status_preset_apply_failed_reason, it.message) }
                }
            }
        },
        onDelete = {
            if (presetVm.current.selected.isNotBlank()) {
                val name = presetVm.current.selected
                scope.launch {
                    // Read the preset BEFORE deleting it. This was a single
                    // tap with no confirmation and nothing to undo; holding
                    // the JSON is what lets Undo put it back byte-for-byte
                    // (— read and saveJson round-trip through the same
                    // parsePersistentJson normalisation).
                    val backup = withContext(dispatchers.io) {
                        runCatching { presetRepo.read(name) }.getOrNull()
                    }
                    val names = withContext(dispatchers.io) {
                        presetRepo.delete(name); presetRepo.list()
                    }
                    presetList = names
                    status = ctx.getString(R.string.editor_status_preset_deleted, name)
                    presetVm.onAction(PresetAction.Select(""))
                    if (backup != null) {
                        offerSnackbarSuggestion(
                            scope,
                            snackbarHost,
                            ctx.getString(R.string.editor_snack_preset_deleted, name),
                            ctx.getString(R.string.editor_action_undo),
                        ) {
                            scope.launch {
                                val restored = withContext(dispatchers.io) {
                                    runCatching { presetRepo.saveJson(name, backup) }
                                    presetRepo.list()
                                }
                                presetList = restored
                                if (name in restored) {
                                    presetVm.onAction(PresetAction.Select(name))
                                    status = ctx.getString(
                                        R.string.editor_status_preset_restored, name,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        onImport = { presetImporter.launch(arrayOf("application/json", "text/*", "*/*")) },
        onExport = { presetExporter.launch("spectrafilm_preset.json") },
        onCopySettings = {
            val copied = runCatching { Presets.toJsonString(state) }.getOrNull()
            presetVm.onAction(PresetAction.SetClipboard(copied))
            status = ctx.getString(
                if (copied != null) R.string.editor_status_settings_copied
                else R.string.editor_status_copy_failed,
            )
        },
        canPasteSettings = presetUi.canPaste,
        onPasteSettings = {
            presetVm.current.clipboardJson?.let { clip ->
                runCatching { Presets.decode(org.json.JSONObject(clip), state) }
                    .onSuccess {
                        // Pasting replaces the whole look, so the preset
                        // amount blend anchor is now stale — clear it so a
                        // later slider move can't overwrite the pasted look.
                        presetVm.onAction(PresetAction.ClearAmount)
                        status = ctx.getString(R.string.editor_status_settings_pasted); previewTick++
                    }
                    .onFailure { status = ctx.getString(R.string.editor_status_paste_failed, it.message) }
            }
        },
        previousEditName = previousEdit?.let { previousEditDisplayName(ctx, it) },
        onApplyPrevious = onApplyPrevious,
        onExportLut = { size, clf ->
            val e = engine ?: return@PresetPanel
            bakingLut = true; status = ctx.getString(R.string.editor_status_baking_lut, if (clf) "CLF" else ".cube")
            scope.launch {
                var lutRenderId = 0L
                val r = runCatching {
                    withContext(dispatchers.default) {
                        // UNSHAPED, deliberately: this file goes to
                        // other software, and a .cube carries no
                        // shaper metadata — it must stay in the
                        // linear domain it advertises.
                        val bake = runCancellableNative(
                            onLateResult = { late ->
                                late.reportOutcome(AppRenderOutcome.CANCELLED)
                            },
                        ) { cancellation ->
                            e.bakeCubeLut(
                                state.toParams(),
                                size,
                                SpektraEngine.SHAPER_NONE,
                                cancellation,
                            )
                        }
                        lutRenderId = bake.renderId
                        if (clf) {
                            val lut = CubeLut.parse(bake.text) ?: error("baked LUT could not be parsed")
                            val film = StockCatalog.displayName(ctx, state.filmProfile)
                            val print = StockCatalog.displayName(ctx, state.printProfile)
                            ClfWriter.write(lut, title = "$film / $print")
                        } else {
                            bake.text
                        }
                    }
                }
                bakingLut = false
                if (!isActive) {
                    SimResult.reportOutcome(lutRenderId, AppRenderOutcome.CANCELLED)
                    return@launch
                }
                r.onSuccess { text ->
                    pendingLutText = text
                    SimResult.reportOutcome(lutRenderId, AppRenderOutcome.CONSUMED)
                    val fileName = lutFileName(
                        StockCatalog.displayName(ctx, state.filmProfile),
                        StockCatalog.displayName(ctx, state.printProfile),
                        size, clf,
                    )
                    runCatching { lutExporter.launch(fileName) }
                        .onFailure {
                            Diag.w("LUT save dialog failed: ${it.message}")
                            status = ctx.getString(R.string.editor_msg_save_dialog_failed)
                            scope.showEditorMessage(snackbarHost, status)
                        }
                }.onFailure {
                    SimResult.reportOutcome(lutRenderId, AppRenderOutcome.FAILED)
                    Diag.w("LUT bake failed: ${it.message}")
                    status = ctx.getString(R.string.editor_msg_lut_bake_failed)
                    scope.showEditorMessage(snackbarHost, status)
                }
            }
        },
        bakingLut = bakingLut,
        versionsContent = {
            // Versions of THIS photo, keyed like its recipe. Bound while the
            // panel is open, so opening it always shows the current list.
            LaunchedEffect(recipeKey) { versionsVm.bind(recipeKey) }
            DisposableEffect(Unit) {
                versionsVm.onEvent = { event ->
                    when (event) {
                        is VersionEvent.Saved -> status =
                            ctx.getString(R.string.editor_status_version_saved, event.version.name)
                        is VersionEvent.Updated -> status =
                            ctx.getString(R.string.editor_status_version_updated, event.name)
                        is VersionEvent.Failed -> status =
                            ctx.getString(R.string.editor_status_version_failed, event.reason)
                        is VersionEvent.Quarantined -> scope.launch {
                            snackbarHost.currentSnackbarData?.dismiss()
                            snackbarHost.showSnackbar(
                                ctx.getString(R.string.editor_snack_versions_quarantined),
                                withDismissAction = true,
                            )
                        }
                        is VersionEvent.Deleted -> offerSnackbarSuggestion(
                            scope,
                            snackbarHost,
                            ctx.getString(R.string.editor_snack_version_deleted, event.version.name),
                            ctx.getString(R.string.editor_action_undo),
                        ) { versionsVm.restore(event.key, event.version) }
                    }
                }
                onDispose { versionsVm.onEvent = {} }
            }
            val versionsUi by versionsVm.uiState.collectAsStateWithLifecycle()
            VersionsSection(
                ui = versionsUi,
                onSave = { name ->
                    runCatching { Presets.toJsonString(state) }
                        .onSuccess { versionsVm.save(name, it, rotation.degrees) }
                        .onFailure { status = ctx.getString(R.string.editor_status_copy_failed) }
                },
                onApply = { version ->
                    // Exactly Paste Settings: the whole look is replaced (so the
                    // preset amount anchors go stale) and Undo takes it back.
                    runCatching { Presets.decode(org.json.JSONObject(version.paramsJson), state) }
                        .onSuccess {
                            presetVm.onAction(PresetAction.ClearAmount)
                            // Crop and masks were saved against this
                            // orientation, so it comes back with them.
                            rotation = SourceRotation.fromDegrees(version.rotationDegrees)
                            status = ctx.getString(R.string.editor_status_version_applied, version.name)
                            previewTick++
                        }
                        .onFailure { status = ctx.getString(R.string.editor_status_paste_failed, it.message) }
                },
                onReplaceWithCurrent = { version ->
                    runCatching { Presets.toJsonString(state) }
                        .onSuccess { versionsVm.replaceWithCurrent(version, it, rotation.degrees) }
                        .onFailure { status = ctx.getString(R.string.editor_status_copy_failed) }
                },
                onRename = { version, name -> versionsVm.rename(version.id, name) },
                onDelete = { version -> versionsVm.delete(version.id) },
            )
        },
    )
}
