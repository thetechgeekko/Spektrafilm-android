/*
 * Spektrafilm for Android — the editor screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * EditorScreen and its top bar / preview region, moved out of MainActivity.kt (#273). They are
 * MainActivity extensions for now so the move changes no behaviour; later #273 slices split
 * them into stateless per-panel composables.
 */
package com.spectrafilm.app

import android.content.Intent
import androidx.activity.OnBackPressedDispatcher
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.app.masks.hasSemantic
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.animation.core.animateDpAsState

internal const val OPEN_PLACEHOLDER_FADE_MS = 220
/**
 * True once the editor has restored in this process. Distinguishes a cold start (the first
 * restore) from an Activity recreation (every later one), which is what decides whether a
 * checkpointed export sheet comes back on screen — see [exportSheetVisibleAtRestore].
 */
private var editorRestoredInThisProcess = false

/**
 * Deliberately non-composed one-shot read: the durable restore adopts an already-running
 * export only when its bound source identity is owned by the editor's exact current
 * binding (ownership, not publication — a revoked source still tracks its own run).
 * Called once from a keyless remember at EditorScreen entry.
 */
internal fun authorizedRunningExportId(editorIdentity: ExportSourceIdentity): Long? =
    (ExportWorkRuntime.state.value as? ExportRuntimeState.Running)
        ?.takeIf { running ->
            exportRunOwnedByEditor(running.sourceIdentity, editorIdentity)
        }
        ?.runId

/**
 * The editor screen. It used to be a MainActivity extension; everything it took from the
 * Activity is now a parameter, passed by [AppRoot] (#452):
 *  - [dispatchers] and the editor's ViewModels (Activity-scoped, see MainActivity);
 *  - [incomingImageIntent], the unconsumed VIEW/SEND intent (#162);
 *  - [launcherShortcut], the unconsumed launcher shortcut (#467);
 *  - [setEditorShortcutHandler], which installs or clears the hardware keyboard handler;
 *  - [heldDropPermissions] and [requestDragAndDropPermissions] for cross-app drops;
 *  - [lifecycle], the Activity lifecycle whose ON_STOP checkpoints the session, and
 *    [onBackPressedDispatcher] for the Esc shortcut.
 */
@Composable
internal fun EditorScreen(
    dispatchers: AppDispatchers,
    editorVm: EditorViewModel,
    exportVm: ExportViewModel,
    exportPresetVm: ExportPresetViewModel,
    filmPreviewVm: StockPreviewViewModel,
    printPreviewVm: StockPreviewViewModel,
    presetPreviewVm: StockPreviewViewModel,
    presetVm: PresetViewModel,
    toolVm: EditorToolViewModel,
    versionsVm: VersionsViewModel,
    viewerVm: EditorViewerViewModel,
    incomingImageIntent: MutableState<Intent?>,
    launcherShortcut: MutableState<LauncherShortcut?>,
    setEditorShortcutHandler: (((EditorShortcut) -> Boolean)?) -> Unit,
    heldDropPermissions: MutableList<android.view.DragAndDropPermissions>,
    requestDragAndDropPermissions: (android.view.DragEvent) -> android.view.DragAndDropPermissions?,
    lifecycle: Lifecycle,
    onBackPressedDispatcher: OnBackPressedDispatcher,
    settings: AppSettings,
    restoration: EditorRestoration,
    sessionRestoreNotice: String?,
    onSessionRestoreNoticeConsumed: () -> Unit,
    onSessionCheckpoint: (EditorSessionDocument, liveMutation: Boolean) -> Boolean,
    activeCategoryState: MutableState<Category?>,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLibrary: () -> Unit,
    onProfileGroups: (List<DropdownGroup>, List<DropdownGroup>) -> Unit,
    onOpenFilmCurves: (id: String, name: String) -> Unit,
    onOpenPrintCurves: (id: String, name: String) -> Unit,
    onSettingsAction: (SettingsAction) -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    // Editor-local UI waiters are cancelled when this destination leaves composition. Durable
    // source/recipe/export work is submitted to its process owner first; AppRoot reconciles
    // SourceAccess again before a later editor composition, so disposed state is never mutated.
    val scope = rememberCoroutineScope()
    val editorInstance = remember { editorProbe.beginLiveEditorInstance() }
    // Parent checkpoints update while this composition is alive. Restoration is intentionally
    // one-shot; the newest checkpoint becomes the initializer only after navigation creates a
    // fresh EditorScreen composition.
    val initialRestoration = remember { restoration }
    val initialRestoredSession = initialRestoration.document
    val initialSessionRestoreNotice = remember { sessionRestoreNotice }
    SideEffect {
        if (sessionRestoreNotice != null) onSessionRestoreNoticeConsumed()
    }

    // Per-composition editing state, owned by EditorViewModel and fresh for every composition
    // (see EditorViewModel for why): params, manual rotation, undo/redo history.
    val session = remember { editorVm.beginSession(initialRestoration) }
    // The catalog this composition has applied (null until its restore ran, #452). Engine,
    // profiles and built-in looks are read-only views of that one value, not separate copies.
    val appliedCatalog = session.appliedCatalog
    val engineState = remember { derivedStateOf { appliedCatalog.value?.engine } }
    val engine by engineState
    val profilesState = remember { derivedStateOf { appliedCatalog.value?.profiles.orEmpty() } }
    val profiles by profilesState
    val state = session.params

    // PERF: decoded proxy-source cache. The interactive preview path re-uses this decoded
    // LinearImage across look/film param edits instead of re-running source decode (LibRaw
    // RAW decode or bitmap decode + sRGB→ProPhoto linearization + EXIF/manual rotation) on
    // every previewTick. Keyed by the decode-affecting inputs only (URI + kind + RAW WB/
    // temp/tint + manual rotation + target edge); any change to one of those invalidates it
    // (the key mismatches → fresh decode). See DecodedSourceCache for the read-only proof
    // that the same buffer can be re-fed to the engine without a defensive copy.
    // EXPORT does NOT use this cache — it always decodes fresh at EXPORT_MAX_EDGE_PX.
    val sourceCache = remember { DecodedSourceCache() }
    // Retained-result grade cache: grade-only edits (saturation/vibrance/gamut/
    // masks) re-grade the last settle render's pristine engine output in pure
    // Kotlin — zero native work. Keyed by engine params + decode key + edge.
    val gradeCache = remember { GradeCache() }
    DisposableEffect(Unit) { onDispose { sourceCache.invalidate() } }

    // PERF/OOM: a SECOND single-entry cache for the MAX_EDGE_PX whole-image proxy used by the
    // Lightroom zoom (renderRoi) and the 100% magnifier. Previously both decoded fresh on every
    // gesture settle — each a ~1s LibRaw decode + a 36MB managed-heap LinearImage (1500×2000×3×
    // 4B) — so rapid pan/zoom stacked several before GC reclaimed them and the ART heap OOM'd
    // ("Failed to allocate a 36000019 byte allocation"). Keeping the 2048px proxy resident (and
    // reusing it for every crop) removes both the OOM pileup and the per-gesture decode latency,
    // which is what makes zoom-pan feel Lightroom-smooth. It is a SEPARATE instance from
    // sourceCache so the 640px preview proxy and the 2048px zoom proxy don't evict each other
    // (the single-entry cache is keyed by maxEdge — sharing one would thrash both back to a
    // fresh decode every render).
    val zoomSourceCache = remember { DecodedSourceCache() }
    // Single-flight guards: a cancelled zoom/preview render's still-running native decode is
    // reused by the next gesture instead of triggering an overlapping re-decode (battery).
    val zoomDecodeFlight = remember { SingleFlight<Unit>() }
    val previewDecodeFlight = remember { SingleFlight<Unit>() }
    DisposableEffect(Unit) { onDispose { zoomSourceCache.invalidate() } }
    DisposableEffect(Unit) {
        onDispose {
            gradeCache.close()
            zoomDecodeFlight.invalidate()
            previewDecodeFlight.invalidate()
        }
    }

    // bundled catalog (friendly stock names + grouping) and built-in presets
    val builtInGroups by remember {
        derivedStateOf { appliedCatalog.value?.builtInGroups.orEmpty() }
    }
    val catalogReady by remember { derivedStateOf { appliedCatalog.value != null } }

    // SourceAccess and the complete session are reconciled before this Composable exists.
    // Keep the resulting identity as ordinary live state: destination SavedState is never a
    // second restoration authority and therefore cannot resurrect an older subject.
    // The visible photo is one immutable EditorSourceState on the session (#452), seeded from
    // initialRestoration.source; these are read-only views of it that stay current inside
    // callbacks. Every change goes through a session transition (showSource / showDemo /
    // requireSourceAuthorization), so uri, kind, name and authorization move together.
    val sourceState = session.source
    val sourceUriState = remember { derivedStateOf { sourceState.value.uri?.let(Uri::parse) } }
    val sourceUri by sourceUriState
    val sourceKindState = remember { derivedStateOf { sourceState.value.kind } }
    val sourceKind by sourceKindState
    val sourceName by remember { derivedStateOf { sourceState.value.displayName } }
    val sourceRuntime = remember(ctx.applicationContext) { ctx.appContainer.sources }
    val sourceAccess = sourceRuntime.coordinator
    val sourceMutationGate = sourceRuntime.mutations
    val sourceAuthorizationRequired by remember {
        derivedStateOf { sourceState.value.authorizationRequired }
    }
    val sourceRenderAllowed = !sourceAuthorizationRequired
    val exportSourceIdentity = remember(
        sourceUri,
        sourceKind,
        sourceAuthorizationRequired,
    ) {
        ExportSourceIdentityAuthority.bind(
            uri = sourceUri?.toString(),
            kind = sourceKind,
            authorizationRequired = sourceAuthorizationRequired,
        )
    }
    val previewState = remember { mutableStateOf<Bitmap?>(null) }
    var preview by previewState
    // Time to full display (MAD audit fix 4): the editor is fully drawn once the first
    // rendered preview is on screen, so startup can be measured as TTFD.
    ReportDrawnWhen { preview != null }
    val beforePreviewState = remember { mutableStateOf<Bitmap?>(null) }
    var beforePreview by beforePreviewState
    // The histogram captures its pixels during composition and never retains either Bitmap.
    // Once a frame is replaced (or the editor leaves composition), no asynchronous reader can
    // still touch it, so release the native pixel storage deterministically after composition.
    DisposableEffect(preview) {
        val owned = preview
        onDispose { owned?.let(::retirePreviewBitmap) }
    }
    DisposableEffect(beforePreview) {
        val owned = beforePreview
        onDispose { owned?.let(::retirePreviewBitmap) }
    }
    val previewBusyState = remember { mutableStateOf(false) }
    var previewBusy by previewBusyState
    val decodingState = remember { mutableStateOf(false) }
    var decoding by decodingState
    val lastRenderMsState = remember { mutableStateOf<Long?>(null) }
    var lastRenderMs by lastRenderMsState
    val renderErrState = remember { mutableStateOf<String?>(null) }
    var renderErr by renderErrState
    // One-shot at-restore snapshot (remember, no keys): later runtime transitions reach the
    // UI through its Compose observer, never through this initializer.
    val activeExportRunAtRestore = remember { authorizedRunningExportId(exportSourceIdentity) }
    val initialExportState = reconcileRestoredExport(
        initialRestoredSession?.export ?: EditorExportState(
            sheetOpen = false,
            options = ExportOptions(
                settings.exportFormat,
                settings.exportQuality,
                ExportSize.FULL,
                2048,
                "",
                settings.exportBorder,
            ),
            keepGps = settings.exportKeepGps,
            phase = EditorExportPhase.IDLE,
            runtimeRunId = null,
        ),
        activeExportRunAtRestore,
    )
    val initialEditorStatus = initialSessionRestoreNotice ?: when (initialExportState.phase) {
        EditorExportPhase.IDLE -> stringResource(R.string.editor_status_initializing)
        EditorExportPhase.RUNNING -> stringResource(R.string.editor_status_resuming_export)
        EditorExportPhase.SUCCESS -> stringResource(R.string.editor_status_prev_export_saved)
        EditorExportPhase.FAILURE -> stringResource(R.string.editor_status_prev_export_failed)
        EditorExportPhase.CANCELLED -> stringResource(R.string.editor_status_prev_export_cancelled)
        EditorExportPhase.RECONCILING -> stringResource(R.string.editor_status_prev_export_reconciling)
    }
    val statusState = remember { mutableStateOf(initialEditorStatus) }
    var status by statusState
    // Lightroom-style export sheet (per-export format / quality / size / colour / name,
    // seeded from Settings and remembered back on export) and the export phase machine
    // live in ExportViewModel (#272). Each editor composition seeds it once from the
    // reconciled checkpoint, so the session document stays the only restoration authority.
    // Process-lifetime, not remember: the FIRST editor restore in a process is a cold start,
    // every later one is an Activity recreation (which keeps the process).
    val coldStart = remember {
        val first = !editorRestoredInThisProcess
        editorRestoredInThisProcess = true
        first
    }
    remember {
        exportVm.restore(
            initialExportState.copy(
                sheetOpen = exportSheetVisibleAtRestore(
                    initialExportState.sheetOpen, initialExportState.phase, coldStart,
                ),
            ),
        )
        true
    }
    // Composition reads (UI and effect keys) use this collected copy. Effects and callbacks
    // read exportVm.current: the collected copy trails a write by one dispatch, and a
    // checkpoint taken right after an action must journal the new state.
    //
    // #270: this function's own body must NOT read `exportUi` directly. A read here
    // subscribes the whole editor to every export-state change (opening the sheet, each
    // option edit, the Export tap), and each one then recomposes this entire screen -- the
    // 37-63 ms recomposes the device trace measured. The body reads the derived fields
    // below, which only invalidate when that one field changes; the export sheet reads
    // `exportUiState` inside its own recompose scope (EditorExportSheetHost).
    val exportUiState = exportVm.uiState.collectAsStateWithLifecycle()
    // #161: busy/done are DERIVED from the one export state machine, never parallel
    // flags — the pill can no longer claim "Exporting…" over a finished export, and no
    // path can forget to clear a boolean the phase transition already implies.
    val exportInFlight by remember(exportUiState) { derivedStateOf { exportUiState.value.inFlight } }
    val exportDone by remember(exportUiState) { derivedStateOf { exportUiState.value.done } }
    val exportLastShare by remember(exportUiState) { derivedStateOf { exportUiState.value.lastShare } }
    val exportOptions by remember(exportUiState) { derivedStateOf { exportUiState.value.options } }
    // Only the render is modal; the result is a Snackbar and the pill's check, which never
    // block the editor.
    val exportMaskVisible = exportInFlight
    val previewTickState = remember { mutableIntStateOf(0) }
    var previewTick by previewTickState
    val publicationGate = remember { RenderPublicationGate() }
    val previewRevision = remember(previewTick) { publicationGate.nextRevision() }
    // Slider drag tracking, the HUD readout and the sideways photo scrub (EditorSliderTracking.kt).
    val sliderTracking = rememberEditorSliderTracking(viewerVm)
    val interacting = sliderTracking.interacting
    val sliderReadout = sliderTracking.sliderReadout
    val lastControlAlive = sliderTracking.lastControlAlive
    val scrubArmed = sliderTracking.scrubArmed
    val sliderInteraction = sliderTracking.sliderInteraction
    val photoScrub = sliderTracking.photoScrub

    // source rotation (applied to the decoded LinearImage -> preview AND export).
    // This is the user's MANUAL step only; the EXIF baseline is derived fresh per load
    // (see loadSource) and is NOT persisted in the recipe.
    val rotationState = session.rotation
    var rotation by rotationState

    // Open placeholder, swipe to the neighbouring photo and the folder strip (EditorPhotoBrowse.kt).
    val photoBrowse = rememberEditorPhotoBrowse(
        ctx = ctx,
        dispatchers = dispatchers,
        settings = settings,
        profiles = profiles,
        engine = engine,
        state = state,
        sourceUri = sourceUri,
        sourceKind = sourceKind,
        sourceAuthorizationRequired = sourceAuthorizationRequired,
        rotationState = rotationState,
        previewState = previewState,
        previewBusyState = previewBusyState,
        interacting = interacting,
        previewTick = previewTick,
        incomingImageIntent = incomingImageIntent,
    )
    val openPlaceholder = photoBrowse.openPlaceholder
    val photoSwipe = photoBrowse.photoSwipe
    val folderStrip = photoBrowse.folderStrip

    // Set by loadSource when a compressed (lossy/JPEG-XL) DNG fell back to the platform
    // ImageDecoder. Drives a one-shot snackbar (a render path can't show UI directly).
    var dngFallbackNotice by remember { mutableStateOf(false) }

    // LUT export
    val bakingLutState = remember { mutableStateOf(false) }
    var bakingLut by bakingLutState
    val pendingLutTextState = remember { mutableStateOf<String?>(null) }
    var pendingLutText by pendingLutTextState

    // Viewer modes (compare, histogram, hidden chrome) live in EditorViewerViewModel (#272).
    // Not journalled: every editor composition starts with them off, as before.
    remember { viewerVm.reset(); true }
    val viewerUi by viewerVm.uiState.collectAsStateWithLifecycle()

    // Experimental GPU LUT preview (Settings → Experimental, default OFF). When on we
    // keep the latest linear proxy + a baked 3D LUT of the current look; PreviewRegion
    // GPU-samples them instead of the CPU bitmap. Read at composition — toggling in
    // Settings applies on the next return to the editor.
    val gpuEnabled = settings.gpuPreview
    val gpuProxyLeaseState = remember { mutableStateOf<DecodedSourceCache.Lease?>(null) }
    var gpuProxyLease by gpuProxyLeaseState
    val gpuProxy = gpuProxyLease?.image
    val gpuLutState = remember { mutableStateOf<CubeLut?>(null) }
    var gpuLut by gpuLutState
    // Auto-exposure gain (2^ev) for the GPU path: the LUT is baked AE-off at unity
    // gain, so the shader multiplies this in before the lookup (see LutGpuPreview).
    val gpuGainState = remember { mutableFloatStateOf(1f) }
    var gpuGain by gpuGainState
    DisposableEffect(Unit) {
        onDispose { gpuProxyLease?.close() }
    }

    // interactive crop overlay (Lightroom-style); hosts on top of everything.
    val restoredTool = initialRestoredSession?.tool
    val restoredOverlay = restorableEditorOverlay(
        restoredTool?.overlay ?: EditorOverlayTool.NONE,
    )
    // Crop, mask-geometry editor, eyedropper (WB gray point / mask colour / mask luminance)
    // and the selected mask live in EditorToolViewModel (#272), seeded once per editor
    // composition from the checkpoint. Composition reads use toolUi; effects and
    // callbacks read toolVm.current.
    remember { toolVm.restore(restoredOverlay, restoredTool?.maskIndex ?: 0); true }
    val toolUi by toolVm.uiState.collectAsStateWithLifecycle()

    // Lightroom's single tap on the photo: everything but the picture gets out of the way
    // (viewerUi.chromeHidden). Deliberately NOT animated. Hiding the top bar and the rail
    // changes the preview's height, and the preview is a weight(1f) sibling — animating
    // that would remeasure it every frame, which is the churn documented on PanelOverlay.
    // A one-shot layout change is a different thing and is what this is.
    // 100% grain magnifier + Lightroom-style ROI zoom patch (EditorZoom.kt).
    val zoom = rememberEditorZoom(
        ctx = ctx,
        scope = scope,
        dispatchers = dispatchers,
        state = state,
        engineState = engineState,
        previewTickState = previewTickState,
        sourceRenderAllowed = sourceRenderAllowed,
        patchSuppressed = { toolVm.current.anyOverlayOpen || viewerVm.current.compare },
    )
    val magnifierOpen by zoom.magnifierOpenState
    val magnifierBitmap by zoom.magnifierBitmapState
    val magnifierRendering by zoom.magnifierRenderingState
    val magnifierStatus by zoom.magnifierStatusState
    val roiOverlay by zoom.roiOverlayState
    val zoomPrewarmEligible = zoom.zoomPrewarmEligible
    fun closeMagnifier() = zoom.closeMagnifier()

    /** Retire every source-derived resource before committing a different source identity. */
    fun retireSourceResources() {
        publicationGate.invalidate()
        sourceCache.invalidate()
        zoomSourceCache.invalidate()
        gradeCache.clear()
        previewDecodeFlight.invalidate()
        zoomDecodeFlight.invalidate()

        // A failed authorization/decode must not leave pixels from the prior source visible or
        // retained. State removal drives the DisposableEffects above, whose read leases defer
        // physical recycle only until an already-running histogram sample completes.
        preview = null
        beforePreview = null

        gpuProxyLease?.close()
        gpuProxyLease = null
        gpuLut = null
        gpuGain = 1f

        zoom.retireSource()
        // Evidence is published only after every production gate/cache/lease owner has been
        // invalidated or retired; callers never observe a start-of-cleanup false positive.
        editorProbe.publishSourceRetirement()
    }

    // Authorization is part of the render identity. Revocation of the same URI must retire
    // cached/native work just as aggressively as replacing the URI, and no render effect is
    // allowed to restart until a successful SourceAccess mutation clears this gate.
    LaunchedEffect(sourceRenderAllowed) {
        if (!sourceRenderAllowed) retireSourceResources()
    }

    // Invalidate an old edit generation before its next main-thread publication, cancel its
    // remaining native work, and retire its overlay while the replacement preview is pending.
    LaunchedEffect(previewTick) { zoom.onEdit(previewTick) }

    // presets
    val restoredPreset = initialRestoredSession?.preset
    val presetListState = remember { mutableStateOf<List<String>>(emptyList()) }
    var presetList by presetListState
    // Preset panel selection + name, the Lightroom-style amount blend anchors and the
    // copy/paste settings clipboard (backlog #10) live in PresetViewModel (#272), seeded
    // once per editor composition from the checkpoint. The clipboard is part of the
    // bounded editor-session document, so navigation/recreation does not silently erase a
    // deliberate Copy action; no pixels or source metadata are included.
    remember { presetVm.restore(restoredPreset); true }
    // Composition reads use the collected copy; effects and callbacks read presetVm.current.
    val presetUi by presetVm.uiState.collectAsStateWithLifecycle()

    // Built-in presets are SPARSE overlays: each authors only the fields it means to
    // set and deliberately leaves everything else alone, so tapping one after another
    // COMPOUNDS them. enlarger.paperGrade is authored only by Tri-X 400, so a colour look
    // tapped after it would silently keep grade 3 (and any field a future look authors
    // alone would leak the same way).
    //
    // So remember the look a browsing run STARTED from and apply each built-in onto that
    // rather than onto the compounded result. There is no list of clear-sites to keep in
    // sync: a run ends by itself the moment the live state stops matching what the last
    // apply produced, which covers source changes, resets, pastes and any hand edit in
    // one condition. (A hand-maintained effect-key list in this file has drifted before.)
    val presetBaselineJsonState = remember { mutableStateOf<String?>(null) }
    var presetBaselineJson by presetBaselineJsonState
    val lastBuiltInOutputState = remember { mutableStateOf<String?>(null) }
    var lastBuiltInOutput by lastBuiltInOutputState
    // Id of the built-in look whose result [lastBuiltInOutput] is: the Look strip's selection.
    val lastBuiltInIdState = remember { mutableStateOf<String?>(null) }

    // Capture the pre-apply look, run [apply], then snapshot the full preset and arm
    // the amount slider at 100%. Used by both built-in and saved-preset apply paths.
    // [baseOverride] pins the amount slider's 0% end to a caller-chosen look — the
    // built-in path needs it because it restores a baseline INSIDE [apply], so the live
    // state just before the call is the wrong anchor.
    fun applyWithAmount(baseOverride: String? = null, apply: () -> Unit) {
        val base = baseOverride ?: runCatching { Presets.toJsonString(state) }.getOrNull()
        apply()
        val full = runCatching { Presets.toJsonString(state) }.getOrNull()
        if (base != null && full != null) {
            presetVm.onAction(PresetAction.Applied(base, full))
        }
    }

    // active adjustment category (null = panel closed); hoisted to AppRoot so it
    // survives navigation to Settings/About and back.
    var activeCategory by activeCategoryState
    // 0..1 while a predictive-back swipe is closing the open panel; drives its shrink.
    var panelBackProgress by remember { mutableFloatStateOf(0f) }

    val presetRepo = remember(ctx) { ctx.appContainer.presets }
    fun refreshPresets() { presetList = presetRepo.list() }

    // --- non-destructive recipe (sidecar) layer ---
    val recipeReadyState = remember { mutableStateOf(false) }
    var recipeReady by recipeReadyState
    val hasRecipeState = remember { mutableStateOf(false) }
    var hasRecipe by hasRecipeState
    val defaultsJsonState = remember { mutableStateOf<String?>(null) }
    var defaultsJson by defaultsJsonState
    LaunchedEffect(recipeReady, state.localAdjustments.size) {
        if (recipeReady) {
            toolVm.onAction(EditorToolAction.ClampMask(state.localAdjustments.size))
        }
    }
    val snackbarHost = remember { SnackbarHostState() }
    val recipeRepo = remember(ctx) { ctx.appContainer.recipes }
    val recipeKey = remember(sourceUri) { Recipes.keyFor(sourceUri) }
    // Lifecycle-aware like every other collector here, but kept active down to CREATED on
    // purpose: an export that finishes while the app is in the background must still be
    // claimed by this editor. ExportWorkRuntime retains a Finished outcome for only five
    // minutes, so pausing at STARTED (the default) could let it expire unseen.
    val exportRuntimeStateState = ExportWorkRuntime.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.CREATED,
    )
    val exportRuntimeState by exportRuntimeStateState
    val lastHandledExportRunState = remember { mutableLongStateOf(0L) }
    var lastHandledExportRun by lastHandledExportRunState
    val exportPublicationState = remember {
        mutableStateOf<Pair<Long, RenderPublicationTicket>?>(null)
    }
    var exportPublication by exportPublicationState
    val sessionCheckpointAction = remember { arrayOfNulls<() -> Boolean>(1) }

    // The process-owned export survives this Activity. A recreated UI observes exactly one
    // retained terminal outcome instead of turning a committed MediaStore row into CANCELLED.
    LaunchedEffect(
        exportRuntimeState,
        exportSourceIdentity,
        recipeReady,
        onSessionCheckpoint,
    ) {
        runExportRuntimeObserver(
            dispatchers,
            exportVm,
            ctx = ctx,
            exportSourceIdentity = exportSourceIdentity,
            publicationGate = publicationGate,
            sessionCheckpointAction = sessionCheckpointAction,
            exportRuntimeStateState = exportRuntimeStateState,
            exportPublicationState = exportPublicationState,
            lastHandledExportRunState = lastHandledExportRunState,
            recipeReadyState = recipeReadyState,
            previewState = previewState,
            statusState = statusState,
            snackbarHost = snackbarHost,
            messageScope = scope,
        )
    }

    // Predictive-back progress of the open full-screen tool (crop / mask geometry / sampler).
    var overlayBackProgress by remember { mutableFloatStateOf(0f) }

    // --- in-session undo / redo edit history ---
    // A snapshot = the full preset/recipe JSON (Presets.toJsonString — covers every
    // ParamsState field incl. raw WB/temp/tint) PLUS the editor-local manual rotation.
    val editHistory = session.history
    // The last SETTLED snapshot we committed. Capture coalescing pushes THIS (the
    // pre-edit state) onto undo when a newer settled state differs (see the capture
    // effect below), so one slider drag (which settles once) == one undo step.
    val committedSnapshotState = session.committedSnapshot
    var committedSnapshot by committedSnapshotState
    // Set true while we are programmatically restoring a snapshot (undo/redo). The
    // capture effect skips one settle cycle so the restore is NOT recorded as a new
    // edit — without this the restored state would push itself and we'd never escape.
    val restoringState = session.restoring
    var restoring by restoringState
    // Explicit conflict marker used only while AppRoot is recovering transient session IO.
    // Programmatic restore/default application keeps it false; a settled user edit sets it.
    var liveCursorMutated by remember { mutableStateOf(false) }

    // Snapshot, restore, undo/redo and the settle step live on EditorSession (#452).
    fun snapshotNow(): EditSnapshot = session.snapshot()

    fun doUndo() {
        session.undo() ?: return
        liveCursorMutated = true
        previewTick++
        status = ctx.getString(R.string.editor_status_undo)
    }

    fun doRedo() {
        session.redo() ?: return
        liveCursorMutated = true
        previewTick++
        status = ctx.getString(R.string.editor_status_redo)
    }

    // Hold Undo: the whole history as a named list (timeline, index of the live state).
    var historySheet by remember { mutableStateOf<Pair<List<EditSnapshot>, Int>?>(null) }

    /** Walks undo or redo from the live state at [from] to timeline index [to]. */
    fun jumpHistory(from: Int, to: Int) {
        var moved = false
        repeat(from - to) { if (session.undo() != null) moved = true }
        repeat(to - from) { if (session.redo() != null) moved = true }
        if (!moved) return
        liveCursorMutated = true
        previewTick++
        status = ctx.getString(if (to < from) R.string.editor_status_undo else R.string.editor_status_redo)
    }

    fun captureEditorSessionDocument(): EditorSessionDocument? = buildEditorSessionDocument(
        recipeReady = recipeReady,
        toolVm = toolVm,
        state = state,
        sourceState = sourceState,
        session = session,
        committedSnapshot = committedSnapshot,
        editHistory = editHistory,
        activeCategory = activeCategory,
        presetVm = presetVm,
        exportVm = exportVm,
    )

    fun checkpointEditorSession(): Boolean = runCatching {
        captureEditorSessionDocument()?.let { document ->
            editorProbe.publishLiveEditorSnapshot(editorInstance, document)
            onSessionCheckpoint(document, liveCursorMutated)
        } ?: false
    }.getOrElse {
        // Never echo session/source values into diagnostics; the exception class is enough
        // to distinguish schema/limit/programming failures for this app-generated file.
        Diag.w("editor session checkpoint rejected (${it.javaClass.simpleName})")
        false
    }
    sessionCheckpointAction[0] = ::checkpointEditorSession

    // Live-editor readiness and the lifecycle checkpoints (EditorSessionCheckpoint.kt).
    val currentSessionCheckpoint by rememberUpdatedState<() -> Boolean>(
        newValue = { checkpointEditorSession() },
    )
    EditorSessionLifecycleEffects(
        recipeReady = recipeReady,
        lifecycle = lifecycle,
        editorInstance = editorInstance,
        captureEditorSessionDocument = ::captureEditorSessionDocument,
        liveCursorMutated = { liveCursorMutated },
        onSessionCheckpoint = onSessionCheckpoint,
        currentSessionCheckpoint = { currentSessionCheckpoint() },
    )

    fun resetEditorCursorForSourceChange() {
        editHistory.clear()
        committedSnapshot = null
        restoring = true
        presetVm.onAction(PresetAction.ClearAmount)
        toolVm.onAction(EditorToolAction.ResetForSourceChange)
        if (recipeReady) {
            Recipes.resetToDefaults(state, settings, profiles)
        }
    }

    // One-time engine init. EditorViewModel loads the catalog (process-wide engine from
    // EngineHolder, profile ids, built-in looks) once, off the main thread. This effect
    // applies it to the composition: engine, profiles and the checkpoint restore land in the
    // same main-thread step, so no render can start with un-restored params.
    val editorCatalog by editorVm.catalog.collectAsStateWithLifecycle()
    LaunchedEffect(editorCatalog) {
        val loaded = editorCatalog ?: return@LaunchedEffect
        val list = loaded.profiles
        val restore = session.applyCatalog(
            profiles = list,
            applyDefaults = settings::applyDefaultsTo,
            restored = initialRestoredSession?.takeIf { restored ->
                sourceUri?.toString() == restored.source.uri &&
                    sourceKind == restored.source.kind
            },
        )
        val freshDefaults = restore.freshDefaults
        if (restore.restored) toolVm.onAction(EditorToolAction.ClampMask(state.localAdjustments.size))
        if (restore.rebaselined) {
            presetVm.onAction(PresetAction.ClearAmount)
            presetVm.onAction(PresetAction.SetClipboard(null))
            status = ctx.getString(R.string.editor_status_session_migrated)
        }
        session.adoptCatalog(loaded)
        refreshPresets()
        if (
            initialSessionRestoreNotice == null &&
            status == ctx.getString(R.string.editor_status_initializing)
        ) {
            status = if (initialRestoredSession == null) {
                ctx.resources.getQuantityString(R.plurals.editor_status_ready_profiles, list.size, list.size)
            } else {
                ctx.resources.getQuantityString(R.plurals.editor_status_session_restored_profiles, list.size, list.size)
            }
        }
        defaultsJson = freshDefaults
        recipeReady = true
        previewTick++
    }

    // --- Non-destructive recipe: restore-on-open ---
    val restoredRecipeKey = initialRestoredSession?.source?.uri
        ?.let(Uri::parse)
        ?.let(Recipes::keyFor)
    val lastRestoredKeyState = remember { mutableStateOf(restoredRecipeKey) }
    var lastRestoredKey by lastRestoredKeyState
    // A restored source identity proves nothing about its sidecar contents. Every composition
    // starts PENDING and only the exact generation classified by readResult may become writable.
    val recipeAccessState = remember(recipeKey) {
        mutableStateOf<EditorRecipeAccess>(
            recipeKey?.let { EditorRecipeAccess.Pending(it, recipeRepo.generation(it)) }
                ?: EditorRecipeAccess.None,
        )
    }
    var recipeAccess by recipeAccessState
    // --- Photo info (PhotoInfo.kt) ---
    // Read on demand, off the main thread, for the photo on screen; a source change while it
    // loads drops the result instead of showing the previous photo's facts.
    var photoInfo by remember { mutableStateOf<PhotoInfo?>(null) }
    fun openPhotoInfo() {
        val uri = sourceUri ?: return
        val name = sourceName
        scope.launch {
            val info = withContext(dispatchers.io) {
                runCatching { readPhotoInfo(ctx, uri, name, null, null) }.getOrNull()
            }
            if (info != null && sourceUri == uri) photoInfo = info
        }
    }
    LaunchedEffect(sourceUri) { photoInfo = null }
    photoInfo?.let { info -> PhotoInfoSheet(info, onDismiss = { photoInfo = null }) }
    historySheet?.let { (timeline, current) ->
        EditHistorySheet(
            timeline = timeline,
            current = current,
            onPick = { to -> historySheet = null; jumpHistory(current, to) },
            onDismiss = { historySheet = null },
        )
    }
    // A new photo has its own history; a list from the last one must not survive the switch.
    LaunchedEffect(sourceUri) { historySheet = null }

    // --- Apply previous edit (PreviousEdit.kt) ---
    // The newest OTHER photo's saved look, looked up on the recipe worker so it is ordered after
    // any autosave of the photo just left. Applying it is exactly Paste settings: the whole look
    // is replaced (rotation stays this photo's), and the settle effect below makes it undoable.
    var previousEdit by remember(recipeKey) { mutableStateOf<PreviousEdit?>(null) }
    suspend fun lookUpPreviousEdit(): PreviousEdit? {
        val key = recipeKey
        return RecipeWorkRuntime.submit { runCatching { recipeRepo.previousEdit(key) }.getOrNull() }
            .await()
            .also { previousEdit = it }
    }
    LaunchedEffect(recipeKey, recipeReady) {
        if (recipeReady) lookUpPreviousEdit()
    }
    fun applyPreviousEdit(edit: PreviousEdit) {
        // Accepted right after opening, the settle effect may not have taken this photo's
        // baseline yet (it is debounced and was cleared by the source change); capture it now
        // so settleDecision pushes it and the applied look stays one undo away.
        if (committedSnapshot == null) committedSnapshot = snapshotNow()
        runCatching { Presets.decode(JSONObject(edit.paramsJson), state) }
            .onSuccess {
                presetVm.onAction(PresetAction.ClearAmount)
                status = ctx.getString(R.string.editor_status_previous_applied, previousEditDisplayName(ctx, edit))
                previewTick++
            }
            .onFailure { status = ctx.getString(R.string.editor_status_paste_failed, it.message) }
    }
    suspend fun offerPreviousEdit() {
        val edit = lookUpPreviousEdit() ?: return
        val result = snackbarHost.showSnackbar(
            message = ctx.getString(R.string.editor_snack_apply_previous, previousEditDisplayName(ctx, edit)),
            actionLabel = ctx.getString(R.string.editor_snack_apply_previous_action),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) applyPreviousEdit(edit)
    }
    val recipeEditEpoch = remember { RecipeEditEpoch() }
    LaunchedEffect(recipeKey, recipeReady) {
        runRecipeRestore(
            ctx = ctx,
            state = state,
            settings = settings,
            editHistory = editHistory,
            recipeKey = recipeKey,
            recipeRepo = recipeRepo,
            restoredRecipeKey = restoredRecipeKey,
            snackbarHost = snackbarHost,
            committedSnapshotState = committedSnapshotState,
            hasRecipeState = hasRecipeState,
            lastRestoredKeyState = lastRestoredKeyState,
            previewTickState = previewTickState,
            profilesState = profilesState,
            recipeAccessState = recipeAccessState,
            recipeReadyState = recipeReadyState,
            restoringState = restoringState,
            rotationState = rotationState,
            statusState = statusState,
            offerPreviousEdit = { offerPreviousEdit() },
        )
    }

    // One-shot snackbar when a compressed DNG fell back to the system decoder.
    LaunchedEffect(dngFallbackNotice) {
        if (dngFallbackNotice) {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(
                message = ctx.getString(R.string.editor_snack_dng_fallback),
                withDismissAction = true,
            )
            dngFallbackNotice = false
        }
    }

    // Media-location, notification and legacy-storage asks around an export
    // (EditorExportPermissions.kt).
    val exportPermissions = rememberEditorExportPermissions(
        ctx, exportVm, settings, { sourceUri }, scope, snackbarHost,
    ) { status = it }
    val legacyStoragePermission = exportPermissions.legacyStoragePermission
    fun mediaLocationPermissionMissing() = exportPermissions.mediaLocationPermissionMissing()
    fun requestMediaLocationIfMissing() = exportPermissions.requestMediaLocationIfMissing()
    fun exportAskingNotificationsOnce(export: () -> Unit) = exportPermissions.exportAskingNotificationsOnce(export)

    // Source adoption: picking, opening and failure reconciliation (EditorSourceAdoption.kt).
    // Built per composition, like the local functions it replaces, so it captures this
    // composition's values; editor state it mutates is passed as state holders.
    val sourceAdoption = EditorSourceAdoption(
        ctx = ctx,
        scope = scope,
        dispatchers = dispatchers,
        session = session,
        sourceRuntime = sourceRuntime,
        sourceState = sourceState,
        sourceUriState = sourceUriState,
        sourceKindState = sourceKindState,
        rotationState = rotationState,
        statusState = statusState,
        previewTickState = previewTickState,
        activeCategoryState = activeCategoryState,
        snackbarHost = snackbarHost,
        retireSourceResources = ::retireSourceResources,
        resetEditorCursorForSourceChange = ::resetEditorCursorForSourceChange,
        checkpointEditorSession = ::checkpointEditorSession,
    )
    fun reconcileVisibleSourceAfterFailure(durable: SourceRestoreResult?) =
        sourceAdoption.reconcileVisibleSourceAfterFailure(durable)
    fun adoptSource(
        uri: Uri,
        kind: SourceKind,
        displayName: String,
        readyStatus: String,
        probeLabel: String? = null,
        ownedCopy: Uri? = null,
    ) = sourceAdoption.adoptSource(uri, kind, displayName, readyStatus, probeLabel, ownedCopy)

    LaunchedEffect(Unit) {
        if (!editorProbe.isArmed()) return@LaunchedEffect
        editorProbe.sourceProbeRequests.collect { request ->
            adoptSource(
                uri = Uri.parse(editorProbe.sourceProbeUri(request.label)),
                kind = SourceKind.PHOTO,
                displayName = "ticket139-source-${request.label.lowercase()}.png",
                readyStatus = "source probe ${request.label} selected",
                probeLabel = request.label,
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!editorProbe.isArmed()) return@LaunchedEffect
        editorProbe.previewCompletionRequests.collect {
            if (
                editorProbe.sourceProbeLabel(sourceUri?.toString()) == "A" &&
                sourceRenderAllowed
            ) {
                // A published frame normally makes this a grade-cache hit. Clearing only the
                // optional retained result forces the next settle through the real native path.
                gradeCache.clear()
                previewTick++
            }
        }
    }

    // --- source pickers ---
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val displayName = uri.lastPathSegment?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.editor_source_picked_photo_name)
            adoptSource(uri, SourceKind.PHOTO, displayName, ctx.getString(R.string.editor_status_photo_selected))
        }
    }
    // Shared dispatch for the document picker AND #162 inbound VIEW/SEND: one routing
    // decision (detectSourceKind, SourceDetect.kt), one adoption path. The picker accepts
    // */*, so every kind below is genuinely reachable — including the motion kinds, which
    // used to fall through the still-image test and be handed to LibRaw as corrupt RAW.
    fun adoptIncomingImage(uri: Uri, nameHint: String? = null, mimeHint: String? = null, ownedCopy: Uri? = null) =
        sourceAdoption.adoptIncomingImage(uri, nameHint, mimeHint, ownedCopy)
    val rawPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) adoptIncomingImage(uri)
    }
    // Batch export: this edit and the export sheet's settings on more photos
    // (EditorBatchExport.kt, BatchExport.kt).
    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            startBatchExport(dispatchers, exportVm, ctx, scope, state, engine, uris, snackbarHost) { status = it }
        }
    }
    // Half-frame pair: this photo and one more on one picture (HalfFramePair.kt).
    val pairPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            startHalfFramePair(
                dispatchers, exportVm,
                ctx, scope, state, engine, sourceUri, sourceName, sourceKind, rotation, uri, snackbarHost,
            ) { status = it }
        }
    }
    BatchExportProgressEffect(ctx, scope, snackbarHost) { status = it }
    // Shared / opened-with images and the launcher's "Open photo" shortcut
    // (EditorIncomingImages.kt).
    EditorIncomingImageEffects(
        incomingImageIntent = incomingImageIntent,
        launcherShortcut = launcherShortcut,
        ctx = ctx,
        dispatchers = dispatchers,
        sourceMutationGate = sourceMutationGate,
        photoPicker = photoPicker,
        adoptIncomingImage = { uri, nameHint, mimeHint, ownedCopy ->
            adoptIncomingImage(uri, nameHint, mimeHint, ownedCopy)
        },
        setStatus = { status = it },
    )
    val pendingPresetExportState = remember { mutableStateOf<String?>(null) }
    var pendingPresetExport by pendingPresetExportState
    // Preset import/export and LUT save launchers (EditorPresetFiles.kt).
    val presetFiles = rememberEditorPresetFiles(
        ctx, scope, dispatchers, state, presetRepo, pendingPresetExportState, pendingLutTextState,
        onImported = { previewTick++ },
        setStatus = { status = it },
    )
    val presetImporter = presetFiles.presetImporter
    val presetExporter = presetFiles.presetExporter
    val savedPresetExporter = presetFiles.savedPresetExporter
    val lutExporter = presetFiles.lutExporter

    // A saved result stays "current" (the pill's Share sends that file again) until the
    // user's next edit -- a slider drag or any settled change -- or a move to another tool
    // panel, so Share never sends a file the photo has since moved on from.
    //
    // The baseline is what the run RENDERED, taken when it starts (the render mask blocks every
    // edit until it ends), never what the editor holds when the result lands: the result is
    // published only after the terminal checkpoint flushes, and an edit made during that
    // window must still count against it (Codex on #535). Falls back to the state at
    // publication after recreation, which loses the remembered baseline.
    // The live edit state (params + rotation) is compared, not the undo history's committed
    // snapshot, which settles on its own 500 ms debounce and would read as an "edit" when it
    // catches up after a fast Share (Codex on #535).
    var exportBaseline by remember { mutableStateOf<Pair<EditSnapshot, Category?>?>(null) }
    LaunchedEffect(exportInFlight) {
        if (exportInFlight) exportBaseline = snapshotNow() to activeCategory
    }
    if (exportDone && exportLastShare != null) {
        LaunchedEffect(exportLastShare) {
            val base = exportBaseline
            val renderedState = base?.first ?: snapshotNow()
            val categoryAtSave = if (base != null) base.second else activeCategory
            snapshotFlow {
                interacting.value ||
                    snapshotNow() != renderedState ||
                    activeCategory != categoryAtSave
            }.first { it }
            exportVm.onAction(ExportAction.DismissResult)
            checkpointEditorSession()
        }
    }

    LaunchedEffect(sourceAuthorizationRequired, sourceKind) {
        if (!sourceAuthorizationRequired) return@LaunchedEffect
        exportVm.onAction(ExportAction.CloseSheet)
        snackbarHost.currentSnackbarData?.dismiss()
        val result = snackbarHost.showSnackbar(
            message = ctx.getString(R.string.editor_snack_source_expired),
            actionLabel = ctx.getString(R.string.editor_snack_choose_again),
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            if (sourceKind == SourceKind.RAW) {
                rawPicker.launch(arrayOf("*/*"))
            } else {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
        }
    }

    // Decode the current source to a LinearImage capped to [maxEdge], applying the
    // EXIF orientation baseline THEN the user's manual rotate steps so imports appear
    // upright in both the preview and the export. The demo image has no EXIF.
    //
    // RAW/DNG: a JPEG-XL Samsung/Pixel Expert-RAW DNG, or a lossy-JPEG one outside the
    // qualified libjpeg-turbo route (corrupt or progressive tiles, Compression 6), makes
    // LibRaw throw RawDecodeException; we fall back to the platform ImageDecoder
    // (display-referred) and flag a one-shot snackbar. EXIF is then applied to the
    // fallback bitmap too.
    fun currentSourceDecodeRequest(): SourceDecodeRequest {
        check(sourceRenderAllowed) { "Source authorization required" }
        return SourceDecodeRequest(
            context = ctx.applicationContext,
            uri = sourceUri,
            kind = sourceKind,
            authorizationRequired = false,
            rawWhiteBalance = state.rawWhiteBalance,
            rawTemperature = state.rawTemperature,
            rawTint = state.rawTint,
            rotation = rotation,
            creativeTemp = state.creativeWbTemp,
            creativeTint = state.creativeWbTint,
            balanceToFilmStock = state.balanceToFilmStock,
            filmProfile = state.filmProfile,
            rawHotPixels = state.rawHotPixels,
            rawChromaNoise = state.rawChromaNoise,
            straightenDegrees = state.straightenDegrees,
        )
    }

    suspend fun cachedSourceDecodeRequest(
        cache: DecodedSourceCache,
        maxEdge: Int,
    ): CachedSourceDecodeRequest =
        withContext(dispatchers.mainImmediate) {
            val decode = currentSourceDecodeRequest()
            val filmBalance = if (
                decode.balanceToFilmStock &&
                FilmStockBalance.isMeaningful(decode.context, decode.filmProfile)
            ) {
                decode.filmProfile
            } else {
                ""
            }
            val cacheRequest = DecodedSourceCache.Request(
                uri = decode.uri?.toString(),
                kind = decode.kind.name,
                authorizationRequired = decode.authorizationRequired,
                whiteBalance = decode.rawWhiteBalance,
                temperature = decode.rawTemperature,
                tint = decode.rawTint,
                creativeTemp = decode.creativeTemp,
                creativeTint = decode.creativeTint,
                filmBalance = filmBalance,
                rotationDegrees = decode.rotation.degrees,
                maxEdge = maxEdge,
                hotPixels = decode.rawHotPixels,
                chromaNoise = decode.rawChromaNoise,
                straightenDegrees = decode.straightenDegrees,
            )
            // Begin publication authority in the same main-thread turn as the snapshot. An
            // older caller cannot resume later and reorder itself ahead of a newer UI state.
            CachedSourceDecodeRequest(decode, cache.beginRequest(cacheRequest))
        }

    // Stock and Look strip preview sessions (EditorStockPreviewHosts.kt).
    fun stockPreviewHost(vm: StockPreviewViewModel, forFilm: Boolean) = editorStockPreviewHost(
        vm, forFilm, ctx, state, sourceCache, dispatchers,
        canOpen = { sourceRenderAllowed && sourceUri != null },
        cachedSourceDecodeRequest = { cache, edge -> cachedSourceDecodeRequest(cache, edge) },
    )

    fun presetPreviewHost(vm: StockPreviewViewModel) = editorPresetPreviewHost(
        vm, ctx, state, sourceCache, dispatchers,
        canOpen = { sourceRenderAllowed && sourceUri != null },
        cachedSourceDecodeRequest = { cache, edge -> cachedSourceDecodeRequest(cache, edge) },
        builtInGroups = { builtInGroups },
        lastBuiltInOutput = { lastBuiltInOutput },
        presetBaselineJson = { presetBaselineJson },
    )

    suspend fun loadSourceCached(
        cache: DecodedSourceCache,
        flight: SingleFlight<Unit>,
        maxEdge: Int,
        label: String,
    ): DecodedSourceCache.Lease {
        // Capture both the decoder inputs and cache identity in one main-thread snapshot.
        // The detached lifecycle flight below must never re-read mutable Compose state.
        val snapshot = cachedSourceDecodeRequest(cache, maxEdge)
        val ticket = snapshot.ticket
        cache.acquire(ticket)?.let { return it }
        flight.run(ticket, scope) {
            val existing = cache.acquire(ticket)
            if (existing != null) {
                existing.close()
            } else {
                val decoded = decodeSourceRequestDiskCached(
                    snapshot.decode,
                    ticket.request,
                    maxEdge,
                    dispatchers.io,
                )
                // publish() consumes the image even when this ticket became stale. An old
                // detached decode therefore cannot evict the newest source generation.
                val accepted = cache.publish(ticket, decoded.image)
                if (accepted && decoded.usedPlatformFallback) {
                    withContext(dispatchers.mainImmediate) {
                        if (cache.isCurrent(ticket)) dngFallbackNotice = true
                    }
                }
            }
        }
        return cache.acquire(ticket)
            ?: throw CancellationException("decoded $label source was superseded")
    }

    // PERF: proxy-source loader used ONLY by the interactive preview path. Consults the
    // single-entry DecodedSourceCache keyed by the decode-affecting inputs (URI + kind +
    // RAW WB/temp/tint + manual rotation + target edge). On a hit we re-feed the SAME
    // cached LinearImage to the engine — proven safe because spk_simulate/_preview take
    // `const spk_image* in` and only read it (see DecodedSourceCache for the full proof),
    // so no defensive copy is required. On a miss we run the captured decode request and
    // store the result, dropping any previous cached source (one entry only). When ONLY
    // look/film params change (the common case while editing sliders) the key is unchanged
    // and we skip the expensive LibRaw/bitmap decode + linearization + rotation entirely.
    // EXPORT calls decodeSourceRequest(EXPORT_MAX_EDGE_PX) directly — full-resolution, never
    // cached. The magnifier uses the separate zoom cache, so neither path uses this cache.
    suspend fun loadSourceCachedForPreview(maxEdge: Int): DecodedSourceCache.Lease {
        return loadSourceCached(
            cache = sourceCache,
            flight = previewDecodeFlight,
            maxEdge = maxEdge,
            label = "preview",
        )
    }

    // PERF/OOM: cached counterpart of loadSourceCachedForPreview for the MAX_EDGE_PX zoom/
    // magnifier proxy, backed by the dedicated zoomSourceCache. The Lightroom zoom (renderRoi)
    // and the 100% magnifier both crop this whole-image proxy; without the cache each gesture
    // settle re-decoded it (LibRaw + 36MB managed alloc) and the pileup OOM'd the ART heap.
    // Same read-only-reuse proof as the preview cache (the engine treats `in` as const), so the
    // single cached LinearImage is safely re-fed to every crop.
    suspend fun loadSourceCachedForZoom(maxEdge: Int): DecodedSourceCache.Lease {
        return loadSourceCached(
            cache = zoomSourceCache,
            flight = zoomDecodeFlight,
            maxEdge = maxEdge,
            label = "zoom",
        )
    }

    zoom.loadSourceCachedForZoom = ::loadSourceCachedForZoom
    fun scheduleZoomPrewarm() = zoom.scheduleZoomPrewarm()
    fun openMagnifier(nx: Float, ny: Float) = zoom.openMagnifier(nx, ny)
    fun renderRoi(roi: RoiRect) = zoom.renderRoi(roi)
    fun clearRoi() = zoom.clearRoi()

    // Every preview render effect: draft, skin, settle, GPU feed, the render trigger and the
    // idle full-resolution pre-render (EditorPreviewEffects.kt). Same order as before.
    val snapshot by remember { derivedStateOf { state.toParams() } }
    EditorPreviewEffects(
        ctx = ctx,
        dispatchers = dispatchers,
        state = state,
        settings = settings,
        toolVm = toolVm,
        viewerVm = viewerVm,
        exportVm = exportVm,
        engineState = engineState,
        sourceState = sourceState,
        sourceUriState = sourceUriState,
        sourceKindState = sourceKindState,
        sourceRenderAllowed = sourceRenderAllowed,
        rotationState = rotationState,
        previewTickState = previewTickState,
        previewRevision = previewRevision,
        publicationGate = publicationGate,
        gradeCache = gradeCache,
        sourceCache = sourceCache,
        interacting = interacting,
        previewState = previewState,
        beforePreviewState = beforePreviewState,
        decodingState = decodingState,
        lastRenderMsState = lastRenderMsState,
        previewBusyState = previewBusyState,
        renderErrState = renderErrState,
        statusState = statusState,
        gpuEnabled = gpuEnabled,
        gpuGainState = gpuGainState,
        gpuLutState = gpuLutState,
        gpuProxyLeaseState = gpuProxyLeaseState,
        zoomPrewarmEligible = zoomPrewarmEligible,
        snapshot = snapshot,
        exportOptions = exportOptions,
        exportInFlight = exportInFlight,
        exportRuntimeStateState = exportRuntimeStateState,
        retireSourceResources = ::retireSourceResources,
        scheduleZoomPrewarm = ::scheduleZoomPrewarm,
        requireSourceAuthorization = session::requireSourceAuthorization,
        currentSourceDecodeRequest = ::currentSourceDecodeRequest,
        cachedSourceDecodeRequest = ::cachedSourceDecodeRequest,
        loadSourceCachedForPreview = ::loadSourceCachedForPreview,
    )

    // Recipe auto-save, undo/redo capture and the session-cursor checkpoints (EditorSettleEffects.kt).
    EditorSettleEffects(
        ctx = ctx,
        session = session,
        state = state,
        exportVm = exportVm,
        snapshot = snapshot,
        recipeKey = recipeKey,
        recipeReady = recipeReady,
        defaultsJson = defaultsJson,
        rotation = rotation,
        recipeAccess = recipeAccess,
        sourceUri = sourceUri,
        sourceKind = sourceKind,
        sourceName = sourceName,
        sourceAuthorizationRequired = sourceAuthorizationRequired,
        activeCategory = activeCategory,
        toolUi = toolUi,
        presetUi = presetUi,
        recipeEditEpoch = recipeEditEpoch,
        recipeRepo = recipeRepo,
        defaultsJsonState = defaultsJsonState,
        hasRecipeState = hasRecipeState,
        recipeAccessState = recipeAccessState,
        recipeReadyState = recipeReadyState,
        rotationState = rotationState,
        sourceState = sourceState,
        statusState = statusState,
        onLiveCursorMutated = { liveCursorMutated = true },
        checkpointEditorSession = ::checkpointEditorSession,
        currentSessionCheckpoint = { currentSessionCheckpoint() },
    )

    // catalog-grouped profile options for the Simulation pickers + Settings.
    val available = profiles.ifEmpty {
        listOf(state.filmProfile, state.printProfile).distinct()
    }
    val filmGroups = remember(available, catalogReady) {
        StockCatalog.optionsFor(ctx, available, forFilm = true).toGroups()
    }
    val printGroups = remember(available, catalogReady) {
        StockCatalog.optionsFor(ctx, available, forFilm = false).toGroups()
    }
    LaunchedEffect(filmGroups, printGroups) { onProfileGroups(filmGroups, printGroups) }

    // What the sheet's Export button (and the pill's Share) runs: the same checks and the
    // same run either way. [rememberPreset] off for Share, which uses the last preset without
    // re-marking it (the preset list may not have loaded yet).
    fun launchEditorExport(exportPresets: ExportPresetDocument, rememberPreset: Boolean = true) {
        startEditorExport(
            dispatchers = dispatchers,
            exportVm = exportVm,
            exportPresetVm = exportPresetVm,
            ctx = ctx,
            scope = scope,
            state = state,
            engine = engine,
            sourceRenderAllowed = sourceRenderAllowed,
            sourceUri = sourceUri,
            sourceName = sourceName,
            exportSourceIdentity = exportSourceIdentity,
            exportPresets = exportPresets,
            snackbarHost = snackbarHost,
            legacyStoragePermission = legacyStoragePermission,
            mediaLocationPermissionMissing = ::mediaLocationPermissionMissing,
            currentSourceDecodeRequest = ::currentSourceDecodeRequest,
            checkpointEditorSession = ::checkpointEditorSession,
            onSettingsAction = onSettingsAction,
            setStatus = { status = it },
            rememberPreset = rememberPreset,
        )
    }
    fun openExportSheetIfAllowed() {
        if (engine != null && sourceRenderAllowed && !previewBusy && !exportVm.current.inFlight) {
            exportVm.onAction(ExportAction.OpenSheet)
        }
    }
    // The Export FAB stays enabled while a preview render runs (a FAB is never disabled),
    // so a tap during a render is held and opens the sheet as soon as the render settles.
    var exportPending by remember { mutableStateOf(false) }
    fun requestExport() {
        if (previewBusy) exportPending = true else openExportSheetIfAllowed()
    }
    // The pill's Share half: send the file just saved if nothing changed since, otherwise
    // export with the last-used choices (no sheet) and share the result when it lands.
    var sharePending by remember { mutableStateOf(false) }
    fun shareExportIfAllowed() {
        val current = exportVm.current
        val last = current.lastShare
        if (current.done && last != null) {
            if (!shareExportedFile(ctx, last.first, last.second)) {
                status = ctx.getString(R.string.editor_status_no_share_target)
            }
            return
        }
        if (engine == null || !sourceRenderAllowed || previewBusy || current.inFlight || current.sheetOpen) return
        exportPresetVm.uiState.value.lastUsedPreset?.let { p ->
            exportVm.onAction(ExportAction.SetOptions(p.options))
            exportVm.onAction(ExportAction.SetKeepGps(p.keepGps))
        }
        exportVm.onAction(ExportAction.ShareWhenDone)
        launchEditorExport(exportPresetVm.uiState.value, rememberPreset = false)
        // Nothing started (a permission or settings check stopped it): drop the request so a
        // later plain export does not open the share sheet.
        if (!exportVm.current.inFlight) exportVm.onAction(ExportAction.ShareHandled)
    }
    fun requestShare() {
        if (previewBusy) sharePending = true else shareExportIfAllowed()
    }
    LaunchedEffect(exportPending, previewBusy) {
        if (exportPending && !previewBusy) {
            exportPending = false
            openExportSheetIfAllowed()
        }
    }
    LaunchedEffect(sharePending, previewBusy) {
        if (sharePending && !previewBusy) {
            sharePending = false
            shareExportIfAllowed()
        }
    }
    // Share reads the last-used preset without opening the sheet, so load the presets once.
    LaunchedEffect(exportPresetVm) { exportPresetVm.load() }
    LaunchedEffect(Unit) {
        if (!editorProbe.isArmed()) return@LaunchedEffect
        editorProbe.exportProbeRequests.collect { request ->
            openExportSheetIfAllowed()
            editorProbe.publishExportProbeResult(
                request.sequence,
                exportVm.current.sheetOpen,
            )
        }
    }

    // Hardware keyboard shortcuts + Back handling (EditorBackAndShortcuts.kt).
    EditorBackAndShortcuts(
        toolUi = toolUi,
        toolVm = toolVm,
        viewerUi = viewerUi,
        viewerVm = viewerVm,
        exportMaskVisible = exportMaskVisible,
        magnifierOpen = magnifierOpen,
        activeCategory = activeCategory,
        onCloseCategory = { activeCategory = null },
        onBackPressedDispatcher = onBackPressedDispatcher,
        setEditorShortcutHandler = setEditorShortcutHandler,
        editHistory = editHistory,
        doUndo = ::doUndo,
        doRedo = ::doRedo,
        openPhotoPicker = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        requestExport = ::requestExport,
        hasSource = sourceUri != null,
        openPhotoInfo = ::openPhotoInfo,
        previousEdit = previousEdit,
        applyPreviousEdit = ::applyPreviousEdit,
        onOverlayBackProgress = { overlayBackProgress = it },
        onPanelBackProgress = { panelBackProgress = it },
        closeMagnifier = ::closeMagnifier,
    )

    // ============================ LAYOUT ============================
    // Overlay predicates, shared with the overlay branches below the chrome.
    val cropBmp = preview
    val cropShown = toolUi.cropOpen && cropBmp != null
    val maskGeometryShown = toolUi.maskGeometryOpen && cropBmp != null &&
        toolUi.maskIndex in state.localAdjustments.indices
    val sampleMask = state.localAdjustments.getOrNull(toolUi.maskIndex)?.mask
    val sampleReady = sourceRenderAllowed && toolUi.sampleOpen && cropBmp != null && (
        toolUi.sampleWb ||
            (sampleMask != null &&
                (if (toolUi.sampleLuminance) sampleMask.luminanceRange != null else sampleMask.colorRange != null))
        )
    // #181: a full-screen tool overlay is modal. Compose overlays share the window, so the
    // chrome beneath would otherwise stay reachable by TalkBack explore-by-touch.
    val modalOverlayShown = magnifierOpen || cropShown || maskGeometryShown || sampleReady

    // --- drag and drop a photo in (ImageDrop.kt) ---
    // Same adoption path as a shared/opened photo. Refused while a modal tool overlay or
    // an export is up, exactly when the Open button is out of reach too.
    var dropHover by remember { mutableStateOf(false) }
    val acceptDrop by rememberUpdatedState(!modalOverlayShown && !exportMaskVisible && !exportInFlight)
    val onImageDrop by rememberUpdatedState<(DragAndDropEvent) -> Boolean> { event ->
        val dragEvent = event.toAndroidDragEvent()
        val uri = droppedContentUri(dragEvent.clipData)
        if (uri == null || !acceptDrop) {
            false
        } else {
            // Null when the drag came from this app; a cross-app drop needs the grant.
            requestDragAndDropPermissions(dragEvent)?.let { heldDropPermissions += it }
            adoptIncomingImage(uri)
            true
        }
    }
    val imageDropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropHover = false
                return onImageDrop(event)
            }
            override fun onEntered(event: DragAndDropEvent) { dropHover = true }
            override fun onExited(event: DragAndDropEvent) { dropHover = false }
            override fun onEnded(event: DragAndDropEvent) { dropHover = false }
        }
    }
    val dropHighlight = MaterialTheme.colorScheme.primary
    // Editor window origin, for placing the fold hinge in this box's coordinates.
    var editorRootOrigin by remember { mutableStateOf(Offset.Zero) }
    val foldingFeature by rememberFoldingFeature()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { editorRootOrigin = it.positionInWindow() }
            .background(MaterialTheme.colorScheme.surface)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> acceptDrop && isDroppableImage(event.mimeTypes()) },
                target = imageDropTarget,
            )
            .drawWithContent {
                drawContent()
                if (dropHover) {
                    val w = 4.dp.toPx()
                    drawRect(
                        dropHighlight,
                        topLeft = androidx.compose.ui.geometry.Offset(w / 2f, w / 2f),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w),
                    )
                }
            },
    ) {
        // Whether the editor lays out as an expanded window (stage rail + side pane).
        // Decided ONCE, here, from the whole editor window (the M3 window size class
        // plus fold posture), and passed down to both the top bar (Export sits in the
        // bar only when the rail is not carrying it) and the layout below. It used to
        // be decided in the box under the top bar and written back up through a
        // SideEffect, which cost a recomposition and a one-frame mismatch, and let
        // hiding the chrome flip the layout on windows near the compact-height line.
        val wide = editorPaneMode(
            maxWidth.value,
            maxHeight.value,
            foldPosture(
                foldingFeature?.toHinge(editorRootOrigin.x, editorRootOrigin.y, LocalDensity.current.density),
                maxWidth.value,
                maxHeight.value,
            ),
        ) == EditorPaneMode.SIDE_PANE
        Column(
            Modifier
                .fillMaxSize()
                .then(if (modalOverlayShown) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            // --- TOP BAR ---
            if (!viewerUi.chromeHidden) EditorTopBar(
                canUndo = editHistory.canUndo,
                canRedo = editHistory.canRedo,
                onUndo = { doUndo() },
                onRedo = { doRedo() },
                onShowHistory = { historySheet = historyTimeline(editHistory.snapshotState(), snapshotNow()) },
                onPeekOriginal = { viewerVm.onAction(EditorViewerAction.SetOriginalPeek(it)) },
                fading = interacting,
                onOpenSource = {
                    // Open the SAME photo picker the Source panel uses (no app exit).
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onOpenSettings = {
                    checkpointEditorSession()
                    onOpenSettings()
                },
                onOpenAbout = {
                    checkpointEditorSession()
                    onOpenAbout()
                },
                onOpenLibrary = {
                    checkpointEditorSession()
                    // Paste edit in the Library reads this photo's recipe.
                    flushRecipeBeforeLeaving(
                        state, recipeKey, recipeReady, recipeAccess, defaultsJson,
                        rotation, sourceName, hasRecipe, recipeRepo,
                    )
                    onOpenLibrary()
                },
                onPhotoInfo = sourceUri?.let { { openPhotoInfo() } },
                // The canvas tools that used to float on the photo (EditorPreviewRegion.kt).
                hasPhoto = preview != null || gpuProxy != null,
                onCrop = { toolVm.onAction(EditorToolAction.OpenCrop) },
                compare = viewerUi.compare,
                histogram = viewerUi.histogram,
                clipWarnings = viewerUi.clipWarnings,
                onToggleCompare = { viewerVm.onAction(EditorViewerAction.ToggleCompare) },
                onToggleHistogram = { viewerVm.onAction(EditorViewerAction.ToggleHistogram) },
                onToggleClipWarnings = { viewerVm.onAction(EditorViewerAction.ToggleClipWarnings) },
                onOpenLoupe = { openMagnifier(0.5f, 0.5f) },
                title = StockCatalog.displayName(ctx, state.filmProfile),
                subtitle = if (state.scanFilm) {
                    stringResource(R.string.editor_preset_scan_positive)
                } else {
                    StockCatalog.displayName(ctx, state.printProfile)
                },
                wide = wide,
                // Hidden, not disabled, when there is nothing to export; a render in flight
                // only defers the tap (see openExportSheetIfAllowed).
                exportVisible = engine != null && sourceRenderAllowed,
                exporting = exportInFlight,
                onExport = { requestExport() },
                exportSaved = exportDone,
                onShare = { requestShare() },
            )

            // Adaptive editor chrome. Narrow (phone portrait): the preview over a floating
            // bottom panel over the category bar, as before. Wide (tablets, landscape phones,
            // desktop windows, unfolded foldables): the active panel docks as a side column
            // beside the preview + category bar. Neither branch touches PreviewRegion or the
            // GPU/CPU render paths; only where the panel body is hosted differs.
            var editorBoxOrigin by remember { mutableStateOf(Offset.Zero) }
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { editorBoxOrigin = it.positionInWindow() },
            ) {
                // Window size classes (M3): side pane for expanded+ windows and landscape
                // phones, bottom sheet for compact and medium portrait. A half-folded device
                // overrides that by posture (FoldPosture.kt): tabletop docks the tools below
                // the hinge, book posture gives the tools exactly one screen.
                val posture = foldPosture(
                    foldingFeature?.toHinge(editorBoxOrigin.x, editorBoxOrigin.y, LocalDensity.current.density),
                    maxWidth.value,
                    maxHeight.value,
                )
                // `posture` here is in this box's coordinates (under the top bar) and only
                // drives pane geometry; the pane MODE (`wide`) was decided above.
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val sidePaneWidth = sidePaneWidthDp(maxWidth.value, posture, rtl).dp
                val bookHingeGap = bookHingeGapDp(posture, maxWidth.value, rtl).dp
                val bookAligned = isBookHingeAligned(posture, maxWidth.value, rtl)

                // The photo's EXIF capture date for the date-back imprint's default text,
                // read off the main thread once per source.
                val photoDateStamp by produceState<String?>(null, sourceUri) {
                    value = sourceUri?.let { uri ->
                        withContext(dispatchers.io) { DateStampText.readCaptureDate(ctx, uri) }
                    }
                }
                // Re-encoded only when a parameter actually changes, not per frame.
                // Shared by the bottom bar/rail, the section tabs and the panels: a tool's
                // reset is offered only when it actually holds edits, which is the same
                // question the dot asks.
                val dirty by remember(state) { derivedStateOf { dirtyCategories(state) } }
                val resetCategory: (Category) -> Unit = { target ->
                    // A partial recipe carrying only this tool's sections or keys.
                    // Presets.applyJson reads each section behind its own optJSONObject and
                    // keeps the current value of any key it leaves out, so nothing outside
                    // them can move.
                    categoryDefaults(target)?.let { defaults ->
                        runCatching { Presets.decode(defaults, state) }
                        previewTick++
                    }
                }
                // RAW cleanup acts inside the RAW decode, so it is only offered for a RAW.
                val isRawSource = sourceKind == SourceKind.RAW
                fun toolShown(c: Category) = c != Category.RAW_CLEANUP || isRawSource
                // The tool Edit reopens on: the last one shown there.
                var lastEdit by rememberSaveable { mutableStateOf<Category?>(null) }
                LaunchedEffect(activeCategory) {
                    activeCategory?.takeIf { it.mode == EditorMode.EDIT }?.let { lastEdit = it }
                }
                val editOpen = activeCategory?.mode == EditorMode.EDIT
                // One tool's controls, shared by every branch so the per-tool `when` below
                // is not duplicated.
                val toolContent: @Composable ColumnScope.(Category) -> Unit = { tool ->
                    val simPart: SimPart? = when (tool) {
                        Category.SIMULATION -> SimPart.FILM_STOCK
                        Category.PAPER -> SimPart.PAPER
                        Category.EXPOSURE -> SimPart.EXPOSURE
                        Category.SCENE_LATITUDE -> SimPart.SCENE_LATITUDE
                        Category.SAT_VIB -> SimPart.SAT_VIB
                        Category.PRINT_COLOR -> SimPart.PRINT_COLOR
                        Category.FILM_FORMAT -> SimPart.FILM_FORMAT
                        Category.CAMERA_DIFFUSION -> SimPart.CAMERA_DIFFUSION
                        Category.PRINT_EXPOSURE -> SimPart.PRINT_EXPOSURE
                        Category.ENLARGER -> SimPart.ENLARGER
                        Category.LENS_BLUR -> SimPart.LENS_BLUR
                        Category.DATE_STAMP -> SimPart.DATE_STAMP
                        Category.SCANNER -> SimPart.SCANNER
                        Category.OUTPUT -> SimPart.OUTPUT
                        else -> null
                    }
                    when (tool) {
                        Category.INPUT -> InputSection(state, onEditCrop = { toolVm.onAction(EditorToolAction.OpenCrop) })
                        Category.RAW_WB -> ImportRawSection(state, isRaw = isRawSource,
                            onPickNeutral = { toolVm.onAction(EditorToolAction.PickNeutral) })
                        Category.RAW_CLEANUP -> RawCleanupSection(state)
                        Category.SIMULATION, Category.PAPER, Category.EXPOSURE, Category.SCENE_LATITUDE,
                        Category.SAT_VIB, Category.PRINT_COLOR, Category.FILM_FORMAT,
                        Category.CAMERA_DIFFUSION, Category.PRINT_EXPOSURE, Category.ENLARGER,
                        Category.LENS_BLUR, Category.DATE_STAMP, Category.SCANNER,
                        Category.OUTPUT -> EditorSimulationPanel(
                            filmPreviewVm = filmPreviewVm,
                            printPreviewVm = printPreviewVm,
                            part = simPart ?: SimPart.FILM_STOCK,
                            ctx = ctx,
                            scope = scope,
                            state = state,
                            snackbarHost = snackbarHost,
                            photoDateStamp = photoDateStamp,
                            filmGroups = filmGroups,
                            printGroups = printGroups,
                            onOpenFilmCurves = onOpenFilmCurves,
                            onOpenPrintCurves = onOpenPrintCurves,
                            stockPreviewHost = ::stockPreviewHost,
                            checkpointEditorSession = ::checkpointEditorSession,
                            sourceKey = sourceUri,
                            // A slider drag runs draft renders without setting previewBusy,
                            // so the strip also waits for the drag to end.
                            previewBusy = previewBusy || interacting.value,
                        )
                        Category.GRAIN -> GrainSection(state)
                        Category.PREFLASH -> PreflashSection(state)
                        Category.CONTRAST_MASK -> ContrastMaskSection(state)
                        Category.HALATION -> HalationSection(state)
                        Category.COUPLERS -> CouplersSection(state)
                        Category.GLARE -> GlareSection(state)
                        Category.EXPERIMENTAL -> ExperimentalSection(state)
                        Category.TONE_CURVE -> ToneCurveSection(state, preview)
                        Category.MASKS -> MasksSection(
                            state,
                            selectedIndex = toolUi.maskIndex,
                            onSelectedIndexChange = { toolVm.onAction(EditorToolAction.SelectMask(it)) },
                            onEditOnPhoto = { idx ->
                                toolVm.onAction(EditorToolAction.EditMaskOnPhoto(idx))
                            },
                            onSampleColor = { idx ->
                                toolVm.onAction(EditorToolAction.SampleMask(idx, luminance = false))
                            },
                            onSampleLuminance = { idx ->
                                toolVm.onAction(EditorToolAction.SampleMask(idx, luminance = true))
                            },
                        )
                        Category.DISPLAY -> DisplaySection(state)
                        Category.PRESETS -> EditorPresetPanel(
                            dispatchers = dispatchers,
                            presetVm = presetVm,
                            versionsVm = versionsVm,
                            ctx = ctx,
                            scope = scope,
                            state = state,
                            presetRepo = presetRepo,
                            snackbarHost = snackbarHost,
                            recipeKey = recipeKey,
                            builtInGroups = builtInGroups,
                            presetUi = presetUi,
                            presetImporter = presetImporter,
                            presetExporter = presetExporter,
                            savedPresetExporter = savedPresetExporter,
                            lutExporter = lutExporter,
                            presetListState = presetListState,
                            presetBaselineJsonState = presetBaselineJsonState,
                            lastBuiltInOutputState = lastBuiltInOutputState,
                            lastBuiltInIdState = lastBuiltInIdState,
                            presetPreviewHost = presetPreviewHost(presetPreviewVm),
                            stripSourceKey = sourceUri,
                            stripPaused = previewBusy || interacting.value,
                            bakingLutState = bakingLutState,
                            pendingLutTextState = pendingLutTextState,
                            pendingPresetExportState = pendingPresetExportState,
                            rotationState = rotationState,
                            statusState = statusState,
                            previewTickState = previewTickState,
                            engineState = engineState,
                            applyWithAmountFn = ::applyWithAmount,
                            previousEdit = previousEdit,
                            onApplyPrevious = { previousEdit?.let { applyPreviousEdit(it) } },
                        )
                        Category.SOURCE -> EditorSourcePanel(
                            onToggleHistogram = { viewerVm.onAction(EditorViewerAction.ToggleHistogram) },
                            onClearPresetAmount = { presetVm.onAction(PresetAction.ClearAmount) },
                            ctx = ctx,
                            scope = scope,
                            state = state,
                            settings = settings,
                            snackbarHost = snackbarHost,
                            sourceRuntime = sourceRuntime,
                            recipeRepo = recipeRepo,
                            recipeKey = recipeKey,
                            recipeEditEpoch = recipeEditEpoch,
                            editHistory = editHistory,
                            photoPicker = photoPicker,
                            rawPicker = rawPicker,
                            showHistogram = viewerUi.histogram,
                            sourceState = sourceState,
                            showDemoSource = {
                                session.showDemo(ctx.getString(R.string.editor_source_demo_name))
                            },
                            statusState = statusState,
                            previewTickState = previewTickState,
                            rotationState = rotationState,
                            hasRecipeState = hasRecipeState,
                            recipeAccessState = recipeAccessState,
                            committedSnapshotState = committedSnapshotState,
                            restoringState = restoringState,
                            profilesState = profilesState,
                            checkpointEditorSessionFn = ::checkpointEditorSession,
                            retireSourceResourcesFn = ::retireSourceResources,
                            resetEditorCursorForSourceChangeFn = ::resetEditorCursorForSourceChange,
                            reconcileVisibleSourceAfterFailureFn = ::reconcileVisibleSourceAfterFailure,
                        )
                    }
                }
                // Edit's tool open in full (every control and its help), or null for the rail.
                var railDetail by rememberSaveable { mutableStateOf<Category?>(null) }
                // A tab tapped or the panel closed returns to the rail.
                LaunchedEffect(activeCategory?.stage) {
                    if (railDetail != null && railDetail?.stage != activeCategory?.stage) railDetail = null
                }
                // The open panel's body. Looks and Masks show the one picked tool; Edit shows
                // every tool on one sideways rail of instrument cards (ToolRail.kt), and a tool
                // opened from its chip in full, under its own name and reset.
                val panelContent: @Composable ColumnScope.() -> Unit = {
                    CompositionLocalProvider(LocalSliderInteraction provides sliderInteraction) {
                        val open = activeCategory
                        val detail = railDetail
                        if (open != null && open.mode == EditorMode.EDIT && detail != null) {
                            // Only while nothing sits over the panel: the magnifier, crop and the
                            // other full-screen tools own Back while they are open.
                            BackHandler(
                                enabled = !magnifierOpen && !viewerUi.chromeHidden &&
                                    !toolUi.anyOverlayOpen && !exportMaskVisible,
                            ) { railDetail = null }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { railDetail = null }) {
                                    Icon(
                                        MaterialSymbols.ArrowBack,
                                        contentDescription = stringResource(R.string.editor_rail_back_to_tools),
                                    )
                                }
                                ToolHeader(
                                    tool = detail,
                                    onReset = if (detail in dirty) ({ resetCategory(detail) }) else null,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            toolContent(detail)
                        } else if (open != null && open.mode == EditorMode.EDIT) {
                            ToolRail(
                                tools = stagesOf(EditorMode.EDIT).flatMap { categoriesOf(it) }.filter(::toolShown),
                                activeStage = open.stage,
                                startTool = open,
                                dirty = dirty,
                                switchOf = { toolSwitch(state, it) },
                                onScrolledTo = { stage ->
                                    if (activeCategory?.stage != stage) {
                                        activeCategory = categoriesOf(stage).first(::toolShown)
                                    }
                                },
                                onOpenTool = { tool ->
                                    activeCategory = tool
                                    railDetail = tool
                                },
                            ) { tool -> RailToolContent(state, tool, onOpen = { activeCategory = tool; railDetail = tool }) }
                        } else if (open != null) {
                            toolContent(open)
                        }
                    }
                }
                // Measured height of the floating adjustment panel (narrow layout only). The
                // preview's zoom control is inset by it so it never sits under the panel.
                var panelHeightPx by remember { mutableIntStateOf(0) }
                // Compact vs tall panel height (drag the handle). Kept across panel closes
                // and rotation so a user who prefers the tall panel is not reset each time.
                var panelTall by rememberSaveable { mutableStateOf(false) }
                // Preview-first: the photo fits into the space ABOVE the floating panel
                // instead of sitting underneath it, so opening a panel never hides the
                // part of the frame being judged. Animated so the photo eases up as the
                // panel rises and back down as it closes (the panel's own height lags a
                // closing panel, so the target drops to 0 straight away instead).
                // Tabletop reserves the photo's upper-half box outright (below), so the panel
                // never insets it there.
                val panelShown = !wide && activeCategory != null && !viewerUi.chromeHidden &&
                    posture !is FoldPosture.Tabletop
                val panelTarget = if (panelShown) {
                    with(LocalDensity.current) { panelHeightPx.toDp() }
                } else {
                    0.dp
                }
                val panelInset by animateDpAsState(panelTarget, label = "previewPanelInset")
                val previewRegion: @Composable () -> Unit = {
                    PreviewRegion(
                        zoomRequests = viewerVm.zoomRequests,
                        controlsBottomInset = panelInset,
                        peekOriginal = viewerUi.originalPeek,
                        preview = preview,
                        placeholder = openPlaceholder,
                        before = beforePreview,
                        busy = previewBusy,
                        decoding = decoding,
                        exporting = exportInFlight,
                        lastRenderMs = lastRenderMs,
                        renderErr = renderErr,
                        compareMode = viewerUi.compare,
                        showHistogram = viewerUi.histogram,
                        showClipping = viewerUi.showClipping,
                        gpuEnabled = gpuEnabled,
                        gpuProxy = gpuProxy,
                        gpuLut = gpuLut,
                        gpuGain = gpuGain,
                        // Single tap on the photo = show/hide the chrome. The point is
                        // ignored: this is a toggle, not a pick. The magnifier that used to
                        // own this gesture now has the button below, which is the affordance
                        // the in-app guide already claimed existed.
                        onPointPicked = { _, _ -> viewerVm.onAction(EditorViewerAction.ToggleChrome) },
                        onOpenMagnifier = { openMagnifier(0.5f, 0.5f) },
                        renderKey = previewTick,
                        roiOverlay = roiOverlay,
                        onRoiSettled = { renderRoi(it) },
                        onRoiCleared = { clearRoi() },
                        swipe = photoSwipe,
                        // Only right after a control was used (scrubArmed) and while it is still on
                        // screen: otherwise the sideways drag is the next/previous photo.
                        scrub = photoScrub.takeIf {
                            editOpen && scrubArmed.value && lastControlAlive.value?.value == true
                        },
                        strip = folderStrip,
                    )
                }
                // In Edit each stacked tool carries its own reset (ToolHeader); elsewhere the
                // panel offers one for the open tool.
                val resetActiveCategory: (() -> Unit)? =
                    activeCategory?.takeIf { !editOpen && it in dirty }?.let { target -> { resetCategory(target) } }
                val editSections = stagesOf(EditorMode.EDIT)
                val openSection: (Stage) -> Unit = { section ->
                    activeCategory = categoriesOf(section).first(::toolShown)
                }
                val openCrop: () -> Unit = { toolVm.onAction(EditorToolAction.OpenCrop) }
                if (wide) {
                    EditorWideLayout(
                        chromeHidden = viewerUi.chromeHidden,
                        activeCategory = activeCategory,
                        onActiveCategoryChange = { activeCategory = it },
                        dirty = dirty,
                        lastEdit = lastEdit,
                        openCrop = openCrop,
                        exportVisible = engine != null && sourceRenderAllowed,
                        exportInFlight = exportInFlight,
                        onExport = { requestExport() },
                        onShare = { requestShare() },
                        previewRegion = previewRegion,
                        bookAligned = bookAligned,
                        bookHingeGap = bookHingeGap,
                        sidePaneWidth = sidePaneWidth,
                        panelBackProgress = panelBackProgress,
                        resetActiveCategory = resetActiveCategory,
                        editOpen = editOpen,
                        editSections = editSections,
                        openSection = openSection,
                        panelContent = panelContent,
                    )
                } else {
                    EditorNarrowLayout(
                        chromeHidden = viewerUi.chromeHidden,
                        activeCategory = activeCategory,
                        onActiveCategoryChange = { activeCategory = it },
                        dirty = dirty,
                        lastEdit = lastEdit,
                        openCrop = openCrop,
                        posture = posture,
                        interacting = interacting,
                        sliderReadout = sliderReadout,
                        previewRegion = previewRegion,
                        panelBackProgress = panelBackProgress,
                        panelTall = panelTall,
                        onPanelTallChange = { panelTall = it },
                        onPanelHeightChange = { panelHeightPx = it },
                        resetActiveCategory = resetActiveCategory,
                        editOpen = editOpen,
                        editSections = editSections,
                        openSection = openSection,
                        panelContent = panelContent,
                    )
                }
            }
        }

        // --- 100% grain magnifier overlay ---
        if (magnifierOpen) {
            // MEMORY: the magnifier crop is a real full-resolution ARGB_8888 render
            // (~512px native here, but it is the only path that decodes at MAX_EDGE_PX
            // before cropping). Recycle it deterministically once the overlay leaves
            // composition (close) or the rendered crop is replaced by a re-render. This
            // DisposableEffect is scoped INSIDE `if (magnifierOpen)`, so onDispose runs
            // only after the overlay is no longer composed/drawn — never a use-after-
            // recycle of the bitmap still on screen. `crop` is the value captured for
            // THIS effect instance; when `magnifierBitmap` changes (e.g. -> null on a
            // re-open) or the overlay closes, the captured previous bitmap is freed.
            val cropToFree = magnifierBitmap
            DisposableEffect(magnifierBitmap) {
                onDispose {
                    if (cropToFree != null && !cropToFree.isRecycled) cropToFree.recycle()
                }
            }
            MagnifierOverlay(
                crop = magnifierBitmap,
                rendering = magnifierRendering,
                status = magnifierStatus,
                onClose = { closeMagnifier() },
            )
        }

        // The full-screen tools share one wrapper so a held back swipe shrinks whichever
        // is open (see the PredictiveBackHandler above).
        Box(Modifier.fillMaxSize().predictiveBackShrink(overlayBackProgress)) {
            EditorToolOverlays(
                dispatchers = dispatchers,
                toolVm = toolVm,
                ctx = ctx,
                scope = scope,
                state = state,
                toolUi = toolUi,
                cropBmp = cropBmp,
                cropShown = cropShown,
                maskGeometryShown = maskGeometryShown,
                sampleReady = sampleReady,
                loadSourceCachedForZoom = { loadSourceCachedForZoom(it) },
                previewTickState = previewTickState,
                rotationState = rotationState,
                statusState = statusState,
                snackbarHost = snackbarHost,
            )
        }

        // --- full-screen export mask, or the finished export's share / snackbar ---
        EditorExportResult(
            exportInFlight = exportInFlight,
            exportDone = exportDone,
            preview = preview,
            onCancelExport = {
                val running = exportRuntimeState as? ExportRuntimeState.Running
                if (running != null && ExportWorkRuntime.cancel(running.runId)) {
                    status = ctx.getString(R.string.editor_status_cancelling_export)
                }
            },
            exportLastShare = exportLastShare,
            exportVm = exportVm,
            ctx = ctx,
            scope = scope,
            snackbarHost = snackbarHost,
            setStatus = { status = it },
        )

        // Everything that reads the export sheet's state sits in its own recompose scope (the
        // composable below reads [exportUiState] itself), so opening the sheet or editing an
        // option recomposes that section and not the whole editor (#270).
        EditorExportSheetHost(
            exportUiState = exportUiState,
            exportVm = exportVm,
            exportPresetVm = exportPresetVm,
            ctx = ctx,
            state = state,
            preview = preview,
            engine = engine,
            sourceUri = sourceUri,
            sourceKind = sourceKind,
            legacyStoragePermission = legacyStoragePermission,
            batchPicker = batchPicker,
            pairPicker = pairPicker,
            requestMediaLocationIfMissing = { requestMediaLocationIfMissing() },
            exportAskingNotificationsOnce = { exportAskingNotificationsOnce(it) },
            launchEditorExport = { launchEditorExport(it) },
            setStatus = { status = it },
        )

        // Recipe restore / status snackbar (above the bottom bar / nav).
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
        )
    }
}

