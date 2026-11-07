/*
 * Spektrafilm for Android — the editor screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * EditorScreen and its top bar / preview region, moved out of MainActivity.kt (#273). They are
 * MainActivity extensions for now so the move changes no behaviour; later #273 slices split
 * them into stateless per-panel composables.
 */
package com.spectrafilm.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.RenderKind
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.app.masks.MaskCompositor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

private const val OPEN_PLACEHOLDER_FADE_MS = 220
private const val OPEN_PLACEHOLDER_HOLD_MS = 600L
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
internal fun MainActivity.authorizedRunningExportId(editorIdentity: ExportSourceIdentity): Long? =
    (ExportWorkRuntime.state.value as? ExportRuntimeState.Running)
        ?.takeIf { running ->
            exportRunOwnedByEditor(running.sourceIdentity, editorIdentity)
        }
        ?.runId

@Composable
internal fun MainActivity.EditorScreen(
    settings: AppSettings,
    restoration: EditorRestoration,
    sessionRestoreNotice: String?,
    onSessionRestoreNoticeConsumed: () -> Unit,
    onSessionCheckpoint: (EditorSessionDocument, liveMutation: Boolean) -> Boolean,
    activeCategoryState: MutableState<Category?>,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
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
    val editorInstance = remember { Ticket139EditorProbe.beginLiveEditorInstance() }
    // Parent checkpoints update while this composition is alive. Restoration is intentionally
    // one-shot; the newest checkpoint becomes the initializer only after navigation creates a
    // fresh EditorScreen composition.
    val initialRestoration = remember { restoration }
    val initialRestoredSession = initialRestoration.document
    val initialSessionRestoreNotice = remember { sessionRestoreNotice }
    SideEffect {
        if (sessionRestoreNotice != null) onSessionRestoreNoticeConsumed()
    }

    var engine by remember { mutableStateOf<SpektraEngine?>(null) }
    var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
    val state = remember { ParamsState() }

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
    var builtInGroups by remember { mutableStateOf<Map<String, List<BuiltInPreset>>>(emptyMap()) }
    var catalogReady by remember { mutableStateOf(false) }

    // SourceAccess and the complete session are reconciled before this Composable exists.
    // Keep the resulting identity as ordinary live state: destination SavedState is never a
    // second restoration authority and therefore cannot resurrect an older subject.
    val restoredSource = initialRestoration.source
    var sourceUri by remember { mutableStateOf(restoredSource.uri?.let(Uri::parse)) }
    var sourceKind by remember { mutableStateOf(restoredSource.kind) }
    var sourceName by remember { mutableStateOf(restoredSource.displayName) }
    val sourceRuntime = remember(ctx.applicationContext) { ctx.appContainer.sources }
    val sourceAccess = sourceRuntime.coordinator
    val sourceMutationGate = sourceRuntime.mutations
    var sourceAuthorizationRequired by remember {
        mutableStateOf(restoredSource.authorizationRequired)
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
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var beforePreview by remember { mutableStateOf<Bitmap?>(null) }
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
    var previewBusy by remember { mutableStateOf(false) }
    var decoding by remember { mutableStateOf(false) }
    var lastRenderMs by remember { mutableStateOf<Long?>(null) }
    var renderErr by remember { mutableStateOf<String?>(null) }
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
    var status by remember { mutableStateOf(initialEditorStatus) }
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
    val exportUi by exportVm.uiState.collectAsStateWithLifecycle()
    // #161: busy/done are DERIVED from the one export state machine, never parallel
    // flags — the pill can no longer claim "Exporting…" over a finished export, and no
    // path can forget to clear a boolean the phase transition already implies.
    val exportInFlight = exportUi.inFlight
    val exportDone = exportUi.done
    val exportMaskVisible = exportUi.maskVisible
    var previewTick by remember { mutableIntStateOf(0) }
    val publicationGate = remember { RenderPublicationGate() }
    val previewRevision = remember(previewTick) { publicationGate.nextRevision() }
    // Slider drag tracking (Lightroom's ICBSliderTrackingBegin/End): true while a slider is
    // being dragged, so the live DRAFT pass runs only during a drag and a discrete edit
    // (switch/dropdown) goes straight to the crisp settle render — no draft flicker, and the
    // continuous-draft cost is bounded to actual drags. Remembered so its identity is stable
    // across recompositions; provided to sliders via LocalSliderInteraction below.
    val interacting = remember { mutableStateOf(false) }
    // What the moving slider currently reads. Held as State and handed to the readout by
    // REFERENCE, never by value: a drag writes this every frame, and reading `.value` here
    // in EditorScreen would recompose this entire 6600-line composable at 60 Hz while the
    // engine is already running a draft render on the same cores.
    val sliderReadout = remember { mutableStateOf<SliderReadout?>(null) }
    val sliderInteraction = remember {
        SliderInteraction(
            onChange = { readout -> interacting.value = true; sliderReadout.value = readout },
            onFinished = { interacting.value = false; sliderReadout.value = null },
            // A second finger on the slider row peeks at clipping (the canvas button
            // latches the same overlay without a gesture).
            onMultiTouch = { down -> viewerVm.onAction(EditorViewerAction.SetClipPeek(down)) },
        )
    }

    // source rotation (applied to the decoded LinearImage -> preview AND export).
    // This is the user's MANUAL step only; the EXIF baseline is derived fresh per load
    // (see loadSource) and is NOT persisted in the recipe.
    var rotation by remember {
        mutableStateOf(SourceRotation.fromDegrees(initialRestoration.rotationDegrees))
    }

    // #286: a display-only stand-in (the file's embedded thumbnail, else the provider's) shown
    // from the moment a source is picked or restored until the first engine render lands.
    // It never feeds the engine, an export or a cache. Keyed on the source identity only:
    // a later rotate does not reload it, because by then the render is on screen.
    var openPlaceholder by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sourceUri, sourceKind, sourceAuthorizationRequired) {
        openPlaceholder = null
        val uri = sourceUri ?: return@LaunchedEffect
        if (sourceKind == SourceKind.DEMO || sourceAuthorizationRequired) return@LaunchedEffect
        OpenPlaceholder.load(ctx.applicationContext, uri, rotation, dispatchers.io) { bmp ->
            // A render that already landed wins; a late thumbnail never covers it.
            if (preview == null) openPlaceholder = bmp
        }
    }
    // Keep the stand-in under the fading-in render briefly, then drop it.
    LaunchedEffect(preview != null) {
        if (preview != null && openPlaceholder != null) {
            delay(OPEN_PLACEHOLDER_HOLD_MS)
            if (preview != null) openPlaceholder = null
        }
    }

    // Set by loadSource when a compressed (lossy/JPEG-XL) DNG fell back to the platform
    // ImageDecoder. Drives a one-shot snackbar (a render path can't show UI directly).
    var dngFallbackNotice by remember { mutableStateOf(false) }

    // LUT export
    var bakingLut by remember { mutableStateOf(false) }
    var pendingLutText by remember { mutableStateOf<String?>(null) }

    // Viewer modes (compare, histogram, hidden chrome) live in EditorViewerViewModel (#272).
    // Not journalled: every editor composition starts with them off, as before.
    remember { viewerVm.reset(); true }
    val viewerUi by viewerVm.uiState.collectAsStateWithLifecycle()

    // Experimental GPU LUT preview (Settings → Experimental, default OFF). When on we
    // keep the latest linear proxy + a baked 3D LUT of the current look; PreviewRegion
    // GPU-samples them instead of the CPU bitmap. Read at composition — toggling in
    // Settings applies on the next return to the editor.
    val gpuEnabled = settings.gpuPreview
    var gpuProxyLease by remember { mutableStateOf<DecodedSourceCache.Lease?>(null) }
    val gpuProxy = gpuProxyLease?.image
    var gpuLut by remember { mutableStateOf<CubeLut?>(null) }
    // Auto-exposure gain (2^ev) for the GPU path: the LUT is baked AE-off at unity
    // gain, so the shader multiplies this in before the lookup (see LutGpuPreview).
    var gpuGain by remember { mutableFloatStateOf(1f) }
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

    // 100% grain magnifier
    var magnifierOpen by remember { mutableStateOf(false) }
    // Lightroom's single tap on the photo: everything but the picture gets out of the way
    // (viewerUi.chromeHidden). Deliberately NOT animated. Hiding the top bar and the rail
    // changes the preview's height, and the preview is a weight(1f) sibling — animating
    // that would remeasure it every frame, which is the churn documented on PanelOverlay.
    // A one-shot layout change is a different thing and is what this is.
    var magnifierBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var magnifierRendering by remember { mutableStateOf(false) }
    var magnifierStatus by remember { mutableStateOf("") }
    // Tracks the in-flight magnifier render so a rapid re-tap can cancel the previous
    // request (last-tap-wins) instead of racing N full-res renders into magnifierBitmap.
    // Held in a remember box (not Compose state — we never read it during composition).
    val magnifierJobRef = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Zoom step 4: background decode of the zoom source after an idle settle (ZoomPrewarm).
    val zoomPrewarmJobRef = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val zoomPrewarmEligible = remember { ZoomPrewarm.eligible(ctx) }
    fun closeMagnifier() {
        // Cancel any in-flight crop render so it doesn't resurrect the overlay
        // state or leak its bitmap (a cancelled job recycles its own result).
        magnifierJobRef.value?.cancel()
        magnifierRendering = false
        magnifierOpen = false
        magnifierBitmap = null
    }
    val magnifierPublicationGate = remember { RoiRenderPublicationGate() }

    // Lightroom-style zoom: the sharp render of the currently-visible region (rendered at
    // ~screen resolution from a native-pixel crop), overlaid on the scaled proxy.
    var roiOverlay by remember { mutableStateOf<RoiOverlay?>(null) }
    val roiJobRef = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val roiPatchTracker = remember { RoiPatchTracker() }
    val roiPublicationGate = remember { RoiRenderPublicationGate() }
    val latestRoiRenderKey by rememberUpdatedState(previewTick)
    val latestMagnifierRenderKey by rememberUpdatedState(previewTick)

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

        zoomPrewarmJobRef.value?.cancel()
        roiPublicationGate.invalidate()
        roiJobRef.value?.cancel()
        roiOverlay = null
        magnifierPublicationGate.invalidate()
        magnifierJobRef.value?.cancel()
        magnifierBitmap = null
        magnifierRendering = false
        // Evidence is published only after every production gate/cache/lease owner has been
        // invalidated or retired; callers never observe a start-of-cleanup false positive.
        Ticket139EditorProbe.publishSourceRetirement()
    }

    // Authorization is part of the render identity. Revocation of the same URI must retire
    // cached/native work just as aggressively as replacing the URI, and no render effect is
    // allowed to restart until a successful SourceAccess mutation clears this gate.
    LaunchedEffect(sourceRenderAllowed) {
        if (!sourceRenderAllowed) retireSourceResources()
    }

    // Invalidate an old edit generation before its next main-thread publication, cancel its
    // remaining native work, and retire its overlay while the replacement preview is pending.
    LaunchedEffect(previewTick) {
        // An edit is under way: don't let the idle zoom-source decode compete with it.
        zoomPrewarmJobRef.value?.cancel()
        roiPublicationGate.invalidate()
        roiJobRef.value?.cancel()
        roiOverlay = null
        magnifierPublicationGate.invalidate()
        magnifierJobRef.value?.cancel()
        if (magnifierOpen) {
            magnifierBitmap = null
            magnifierRendering = false
            magnifierStatus = ctx.getString(R.string.editor_magnifier_edit_changed)
        }
    }
    // Cancel any in-flight ROI / magnifier render job when the editor leaves composition
    // (navigation), so a superseded job doesn't keep rendering into a gone Composable.
    DisposableEffect(Unit) {
        onDispose {
            magnifierJobRef.value?.cancel()
            roiJobRef.value?.cancel()
            zoomPrewarmJobRef.value?.cancel()
        }
    }
    // Recycle a superseded ROI bitmap once it has left composition (safe — not mid-draw).
    DisposableEffect(roiOverlay) {
        val current = roiOverlay
        onDispose { current?.bitmap?.let { if (!it.isRecycled) it.recycle() } }
    }

    // presets
    val restoredPreset = initialRestoredSession?.preset
    var presetList by remember { mutableStateOf<List<String>>(emptyList()) }
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
    // COMPOUNDS them. camera.diffusionFilter is authored by exactly one of the 27, which
    // means "Neutral — Clean Baseline" (whose own description promises grain, halation,
    // couplers and glare off) would silently keep a previously applied Black Pro-Mist
    // filter and its spectral blur.
    //
    // So remember the look a browsing run STARTED from and apply each built-in onto that
    // rather than onto the compounded result. There is no list of clear-sites to keep in
    // sync: a run ends by itself the moment the live state stops matching what the last
    // apply produced, which covers source changes, resets, pastes and any hand edit in
    // one condition. (A hand-maintained effect-key list in this file has drifted before.)
    var presetBaselineJson by remember { mutableStateOf<String?>(null) }
    var lastBuiltInOutput by remember { mutableStateOf<String?>(null) }

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
    var recipeReady by remember { mutableStateOf(false) }
    var hasRecipe by remember { mutableStateOf(false) }
    var defaultsJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recipeReady, state.localAdjustments.size) {
        if (recipeReady) {
            toolVm.onAction(EditorToolAction.ClampMask(state.localAdjustments.size))
        }
    }
    val snackbarHost = remember { SnackbarHostState() }
    val recipeRepo = remember(ctx) { ctx.appContainer.recipes }
    val recipeKey = remember(sourceUri) { Recipes.keyFor(sourceUri) }
    val exportRuntimeState by ExportWorkRuntime.state.collectAsState()
    var lastHandledExportRun by remember { mutableLongStateOf(0L) }
    var exportPublication by remember {
        mutableStateOf<Pair<Long, RenderPublicationTicket>?>(null)
    }
    val sessionCheckpointAction = remember { arrayOfNulls<() -> Boolean>(1) }

    // The process-owned export survives this Activity. A recreated UI observes exactly one
    // retained terminal outcome instead of turning a committed MediaStore row into CANCELLED.
    LaunchedEffect(
        exportRuntimeState,
        exportSourceIdentity,
        recipeReady,
        onSessionCheckpoint,
    ) {
        when (val runtime = exportRuntimeState) {
            ExportRuntimeState.Idle -> {
                if (exportVm.current.phase == EditorExportPhase.RECONCILING) {
                    exportVm.onAction(ExportAction.RuntimeIdle)
                    status = ctx.getString(R.string.editor_status_prev_export_reconciling)
                }
            }
            is ExportRuntimeState.Running -> {
                // Ownership (exact current source binding) tracks the durable cursor; full
                // publication authorization additionally requires an authorization-free
                // source and gates only the pixels. A revoked source still remembers its own
                // in-flight run so process-death recovery can reconcile it (ticket #139).
                if (!exportRunOwnedByEditor(runtime.sourceIdentity, exportSourceIdentity)) {
                    exportVm.onAction(ExportAction.RunDisowned(runtime.runId))
                    return@LaunchedEffect
                }
                if (
                    exportPublication?.first != runtime.runId &&
                    exportPublicationAuthorized(runtime.sourceIdentity, exportSourceIdentity)
                ) {
                    exportPublication = runtime.runId to publicationGate.begin(
                        publicationGate.nextRevision(),
                        RenderPublicationPriority.EXPORT,
                    )
                }
                exportVm.onAction(ExportAction.Running(runtime.runId))
                status = ctx.getString(R.string.editor_status_rendering_full)
                sessionCheckpointAction[0]?.invoke()
            }
            is ExportRuntimeState.Finished -> {
                if (!exportPublicationAuthorized(runtime.sourceIdentity, exportSourceIdentity)) {
                    val discarded = ExportWorkRuntime.claimFinished(runtime.runId)
                        ?: return@LaunchedEffect
                    recycleUnpublishedExport(discarded)
                    lastHandledExportRun = runtime.runId
                    exportPublication = null
                    exportVm.onAction(ExportAction.RunDisowned(runtime.runId))
                    return@LaunchedEffect
                }
                if (!recipeReady || sessionCheckpointAction[0] == null) return@LaunchedEffect
                if (runtime.runId == lastHandledExportRun) return@LaunchedEffect
                // Persist a sanitized terminal marker before atomically claiming the process
                // result. If recreation cancels this coroutine before/during the IO flush, the
                // process runtime still owns the unclaimed result; if it cancels after the
                // flush, the replacement UI can restore the terminal marker exactly once.
                when (val pending = runtime.outcome) {
                    is ExportTerminalOutcome.Success -> {
                        exportVm.onAction(ExportAction.Terminal(EditorExportPhase.SUCCESS))
                        status = ctx.getString(R.string.editor_status_saved_to_gallery)
                    }
                    is ExportTerminalOutcome.Failure -> {
                        exportVm.onAction(
                            ExportAction.Terminal(
                                if (pending.cause is ExportReconciliationPendingException) {
                                    EditorExportPhase.RECONCILING
                                } else {
                                    EditorExportPhase.FAILURE
                                },
                            ),
                        )
                    }
                    is ExportTerminalOutcome.Cancelled -> {
                        exportVm.onAction(ExportAction.Terminal(EditorExportPhase.CANCELLED))
                    }
                }
                val durableTerminal = awaitDurableEditorSessionCheckpoint(
                    checkpoint = { sessionCheckpointAction[0]?.invoke() == true },
                    flush = {
                        withContext(dispatchers.io) {
                            EditorSessionCheckpointRuntime.flush()
                        }
                    },
                    onRetryableFailure = { failure ->
                        // The process runtime keeps the terminal result unclaimed while the
                        // latest checkpoint remains pending. Retry without exposing session
                        // contents or allowing an older disk generation to count as success.
                        Diag.w(
                            "terminal editor session save retrying " +
                                "(${failure.javaClass.simpleName})",
                        )
                        status = ctx.getString(R.string.editor_status_export_complete_retrying_save)
                        delay(500)
                    },
                )
                if (!durableTerminal) {
                    // Startup storage may still be recovering, or a future-version session may
                    // be protected. In both cases the process outcome remains retained.
                    status = ctx.getString(R.string.editor_status_export_complete_save_waiting)
                    return@LaunchedEffect
                }
                val publicationTicket = exportPublication
                    ?.takeIf { it.first == runtime.runId }
                    ?.second
                    ?: publicationGate.begin(
                        publicationGate.nextRevision(),
                        RenderPublicationPriority.EXPORT,
                    )
                // Identity decides the durable outcome; the render gate only decides whether
                // this export's pixels may replace the preview. A newer interactive render
                // outranking the export ticket must suppress stale pixels, never rewrite a
                // committed MediaStore success into RECONCILING (ticket #139).
                val identityAuthorized =
                    exportPublicationAuthorized(runtime.sourceIdentity, exportSourceIdentity)
                val publicationAllowed =
                    identityAuthorized && publicationGate.tryClaim(publicationTicket)
                val claimedOutcome = ExportWorkRuntime.claimFinished(runtime.runId)
                    ?: return@LaunchedEffect
                lastHandledExportRun = runtime.runId
                exportPublication = null
                when (val outcome = claimedOutcome) {
                    is ExportTerminalOutcome.Success -> {
                        outcome.bitmap?.let { bitmap ->
                            if (publicationAllowed) {
                                preview = bitmap
                            } else if (!bitmap.isRecycled) {
                                bitmap.recycle()
                            }
                        }
                        if (!identityAuthorized) {
                            exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.RECONCILING))
                            status = ctx.getString(R.string.editor_status_export_stale_source)
                            sessionCheckpointAction[0]?.invoke()
                            return@LaunchedEffect
                        }
                        val phases = outcome.phases
                        val accounted = phases.setupMs + phases.decodeMs + phases.exifMs +
                            phases.simulateMs + phases.gradeMs + phases.encodeMs
                        Diag.i(
                            "export phases ms: setup=${phases.setupMs} decode=${phases.decodeMs} " +
                                "exif=${phases.exifMs} simulate=${phases.simulateMs} " +
                                "grade=${phases.gradeMs} encode=${phases.encodeMs} " +
                                "residual=${outcome.totalMs - accounted} total=${outcome.totalMs}",
                        )
                        Diag.i("export format=${outcome.format.name} ok in ${outcome.totalMs}ms")
                        exportVm.onAction(
                            ExportAction.SetShare(
                                outcome.publishedUri?.let { published ->
                                    published to (outcome.publishedMimeType ?: "image/*")
                                },
                            ),
                        )
                        exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.SUCCESS))
                        status = ctx.getString(R.string.editor_status_saved_to_gallery)
                    }
                    is ExportTerminalOutcome.Failure -> {
                        Diag.w(
                            "export format=${outcome.format.name} failed after " +
                                "${outcome.elapsedMs}ms: ${outcome.cause.message}",
                        )
                        if (outcome.cause is ExportReconciliationPendingException) {
                            exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.RECONCILING))
                            status = ctx.getString(R.string.editor_status_export_pending_reconciliation)
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.editor_toast_export_reconciling),
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.FAILURE))
                            status = ctx.getString(R.string.editor_status_export_failed, outcome.cause.message)
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.editor_toast_export_failed, outcome.cause.message),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    is ExportTerminalOutcome.Cancelled -> {
                        Diag.i(
                            "export format=${outcome.format.name} cancelled after " +
                                "${outcome.elapsedMs}ms",
                        )
                        exportVm.onAction(ExportAction.SetPhase(EditorExportPhase.CANCELLED))
                        status = ctx.getString(R.string.editor_status_export_cancelled)
                    }
                }
                sessionCheckpointAction[0]?.invoke()
            }
        }
    }

    // --- double-back-to-exit on the root editor ---
    var backArmed by remember { mutableStateOf(false) }

    // --- in-session undo / redo edit history ---
    // A snapshot = the full preset/recipe JSON (Presets.toJsonString — covers every
    // ParamsState field incl. raw WB/temp/tint) PLUS the editor-local manual rotation.
    val editHistory = remember {
        EditHistory().also { history ->
            initialRestoredSession?.history?.let(history::restoreState)
        }
    }
    // The last SETTLED snapshot we committed. Capture coalescing pushes THIS (the
    // pre-edit state) onto undo when a newer settled state differs (see the capture
    // effect below), so one slider drag (which settles once) == one undo step.
    var committedSnapshot by remember {
        mutableStateOf(initialRestoredSession?.committed)
    }
    // Set true while we are programmatically restoring a snapshot (undo/redo). The
    // capture effect skips one settle cycle so the restore is NOT recorded as a new
    // edit — without this the restored state would push itself and we'd never escape.
    var restoring by remember { mutableStateOf(false) }
    // Explicit conflict marker used only while AppRoot is recovering transient session IO.
    // Programmatic restore/default application keeps it false; a settled user edit sets it.
    var liveCursorMutated by remember { mutableStateOf(false) }

    // Build a snapshot of the CURRENT live editing state (+ manual rotation).
    fun snapshotNow(): EditSnapshot =
        // Session decode canonicalizes embedded Params JSON. Keep the live cursor in the same
        // compact canonical representation; comparing a pretty producer string with a compact
        // restored string would manufacture an undo entry during the first restore settle.
        EditSnapshot(Presets.encode(state).toString(), rotation.degrees)

    // Restore a snapshot into the live state: decode params (shared preset schema),
    // re-apply rotation, then bump previewTick — mirrors the recipe restore-on-open
    // path. `restoring` guards the capture effect against recording this mutation.
    fun applySnapshot(snap: EditSnapshot) {
        restoring = true
        runCatching { Presets.decode(org.json.JSONObject(snap.paramsJson), state) }
        rotation = SourceRotation.fromDegrees(snap.rotationDegrees)
        committedSnapshot = snap
        previewTick++
    }

    fun doUndo() {
        val target = editHistory.undo(snapshotNow()) ?: return
        liveCursorMutated = true
        applySnapshot(target)
        status = ctx.getString(R.string.editor_status_undo)
    }

    fun doRedo() {
        val target = editHistory.redo(snapshotNow()) ?: return
        liveCursorMutated = true
        applySnapshot(target)
        status = ctx.getString(R.string.editor_status_redo)
    }

    fun captureEditorSessionDocument(): EditorSessionDocument? {
        if (!recipeReady) return null
        val overlay = toolVm.current.overlay
        val selectedMask = clampSelectedMaskIndex(
            toolVm.current.maskIndex,
            state.localAdjustments.size,
        )
        // Crop/mask-geometry gesture drafts live only inside their overlay Composable. Persist
        // the selected tool; a recreated overlay seeds a fresh, no-op draft from the committed
        // crop/mask geometry passed below, never from the disposed in-progress gesture.
        val committedOverlay = restorableEditorOverlay(overlay)
        val safeOverlay = if (
            committedOverlay == EditorOverlayTool.MASK_SAMPLE_COLOR ||
            committedOverlay == EditorOverlayTool.MASK_SAMPLE_LUMINANCE
        ) {
            committedOverlay.takeIf { selectedMask in state.localAdjustments.indices }
                ?: EditorOverlayTool.NONE
        } else {
            committedOverlay
        }
        return EditorSessionDocument(
            source = EditorSourceState(
                    uri = sourceUri?.toString(),
                    kind = sourceKind,
                    displayName = AtomicJsonStore.truncateUtf16Safely(sourceName, 512),
                    authorizationRequired = sourceAuthorizationRequired,
                ),
                current = snapshotNow(),
                committed = committedSnapshot,
                history = editHistory.snapshotState(),
                tool = EditorToolState(
                    category = activeCategory,
                    overlay = safeOverlay,
                    maskIndex = selectedMask,
                ),
                preset = presetVm.current.toSessionState(),
                export = exportVm.current.toSessionState(),
        )
    }

    fun checkpointEditorSession(): Boolean = runCatching {
        captureEditorSessionDocument()?.let { document ->
            Ticket139EditorProbe.publishLiveEditorSnapshot(editorInstance, document)
            onSessionCheckpoint(document, liveCursorMutated)
        } ?: false
    }.getOrElse {
        // Never echo session/source values into diagnostics; the exception class is enough
        // to distinguish schema/limit/programming failures for this app-generated file.
        Diag.w("editor session checkpoint rejected (${it.javaClass.simpleName})")
        false
    }
    sessionCheckpointAction[0] = ::checkpointEditorSession

    LaunchedEffect(recipeReady) {
        if (!recipeReady) return@LaunchedEffect
        runCatching { captureEditorSessionDocument() }
            .onSuccess { document ->
                if (document != null) {
                    Ticket139EditorProbe.publishLiveEditorSnapshot(editorInstance, document)
                    onSessionCheckpoint(document, liveCursorMutated)
                    Ticket139EditorProbe.publishLiveEditorReady(editorInstance, document)
                }
            }
            .onFailure {
                Diag.w("live editor readiness rejected (${it.javaClass.simpleName})")
            }
    }

    // Lifecycle recreation is the only time a non-navigation teardown can happen after the
    // last debounced settle. Capture synchronously on the main thread; the latest-only runtime
    // owns the bounded IO after this Activity is gone.
    val currentSessionCheckpoint by rememberUpdatedState<() -> Boolean>(
        newValue = { checkpointEditorSession() },
    )
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) currentSessionCheckpoint()
        }
        lifecycle.addObserver(observer)
        onDispose {
            currentSessionCheckpoint()
            lifecycle.removeObserver(observer)
        }
    }

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

    // One-time engine init. The engine is a process-scoped singleton (see
    // EngineHolder): immutable + thread-safe after construction, so it is reused
    // across Activity recreations instead of being re-created and leaked on every
    // configuration change. EngineHolder also handles the AAssetManager-then-extract
    // fallback. Created off the main thread (asset wiring is heavy on first call).
    LaunchedEffect(Unit) {
        withContext(dispatchers.io) {
            val e = EngineHolder.get(ctx)
            val list = runCatching { e.listProfiles() }.getOrDefault(emptyList())
            StockCatalog.stocks(ctx) // warm the catalog cache
            val presetGroups = runCatching { BuiltInPresets.grouped(ctx) }.getOrDefault(emptyMap())
            withContext(dispatchers.main) {
                engine = e
                profiles = list
                settings.applyDefaultsTo(state, list)
                if (list.isNotEmpty()) {
                    if (state.filmProfile !in list) state.filmProfile = list.first()
                    if (state.printProfile !in list) state.printProfile = list.first()
                }
                val freshDefaults = Presets.toJsonString(state)
                initialRestoredSession?.takeIf { restored ->
                    sourceUri?.toString() == restored.source.uri &&
                        sourceKind == restored.source.kind
                }?.current?.let { restored ->
                    val removedProfileReference = initialRestoredSession
                        ?.referencesUnavailableProfiles(list.toSet()) == true
                    Presets.decode(AtomicJsonStore.parseObject(restored.paramsJson), state)
                    // A catalog update can remove a profile named by an older session. Keep the
                    // source and valid edits, but use a real installed profile and re-baseline
                    // history instead of leaving an undo branch that cannot render.
                    var migratedProfile = false
                    if (list.isNotEmpty() && state.filmProfile !in list) {
                        state.filmProfile = list.first()
                        migratedProfile = true
                    }
                    if (list.isNotEmpty() && state.printProfile !in list) {
                        state.printProfile = list.first()
                        migratedProfile = true
                    }
                    toolVm.onAction(EditorToolAction.ClampMask(state.localAdjustments.size))
                    if (migratedProfile || removedProfileReference) {
                        editHistory.clear()
                        committedSnapshot = null
                        presetVm.onAction(PresetAction.ClearAmount)
                        presetVm.onAction(PresetAction.SetClipboard(null))
                        status = ctx.getString(R.string.editor_status_session_migrated)
                    }
                }
                builtInGroups = presetGroups
                catalogReady = true
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
        }
    }

    // --- Non-destructive recipe: restore-on-open ---
    val restoredRecipeKey = initialRestoredSession?.source?.uri
        ?.let(Uri::parse)
        ?.let(Recipes::keyFor)
    var lastRestoredKey by remember { mutableStateOf(restoredRecipeKey) }
    // A restored source identity proves nothing about its sidecar contents. Every composition
    // starts PENDING and only the exact generation classified by readResult may become writable.
    var recipeAccess by remember(recipeKey) {
        mutableStateOf<EditorRecipeAccess>(
            recipeKey?.let { EditorRecipeAccess.Pending(it, recipeRepo.generation(it)) }
                ?: EditorRecipeAccess.None,
        )
    }
    val recipeEditEpoch = remember { RecipeEditEpoch() }
    LaunchedEffect(recipeKey, recipeReady) {
        if (!recipeReady) return@LaunchedEffect
        if (recipeKey == null) {
            hasRecipe = false
            recipeAccess = EditorRecipeAccess.None
            // Switched to a keyless source (the demo image): still drop cross-image
            // history and re-baseline once for the demo's current state.
            if (lastRestoredKey != null) {
                lastRestoredKey = null
                editHistory.clear()
                committedSnapshot = null
                restoring = true
            }
            return@LaunchedEffect
        }
        val preserveSessionCursor = recipeKey == restoredRecipeKey
        val sourceChanged = recipeKey != lastRestoredKey
        lastRestoredKey = recipeKey
        // New source: undo must never cross images. Drop the history and re-baseline so
        // the just-restored/default state for THIS image is the empty-history baseline
        // (canUndo=false). `restoring` makes the next capture settle adopt the new
        // baseline without recording it as an edit.
        if (sourceChanged) {
            editHistory.clear()
            committedSnapshot = null
            restoring = true
        }
        // Submission itself happens on Main and is process-owned. If an old Activity already
        // submitted an autosave, FIFO guarantees this recreated restore reads after its commit.
        var restored: RecipeReadResult
        while (true) {
            val pendingRestore = RecipeWorkRuntime.submit {
                val before = recipeRepo.generation(recipeKey)
                val result = recipeRepo.readResult(recipeKey)
                Triple(before, result, recipeRepo.generation(recipeKey))
            }
            val (before, result, after) = pendingRestore.await()
            val classified = classifyEditorRecipeAccess(recipeKey, before, after, result)
            recipeAccess = classified
            if (classified is EditorRecipeAccess.Pending) continue
            restored = result
            break
        }
        when (restored) {
            is RecipeReadResult.Loaded -> {
                hasRecipe = true
                if (!preserveSessionCursor) {
                    Presets.decode(restored.document.params, state)
                    rotation = SourceRotation.fromDegrees(restored.document.manualRotationDeg)
                    previewTick++
                    snackbarHost.currentSnackbarData?.dismiss()
                    snackbarHost.showSnackbar(
                        message = ctx.getString(R.string.editor_snack_recipe_restored),
                        withDismissAction = true,
                    )
                }
            }
            is RecipeReadResult.CorruptQuarantined -> {
                hasRecipe = false
                if (!preserveSessionCursor) {
                    Recipes.resetToDefaults(state, settings, profiles)
                    rotation = SourceRotation.NONE
                    previewTick++
                }
                Diag.w("recipe quarantined: ${restored.reason}")
                snackbarHost.currentSnackbarData?.dismiss()
                snackbarHost.showSnackbar(
                    message = ctx.getString(R.string.editor_snack_recipe_quarantined),
                    withDismissAction = true,
                )
            }
            RecipeReadResult.Missing -> {
                hasRecipe = false
                if (!preserveSessionCursor) {
                    Recipes.resetToDefaults(state, settings, profiles)
                    rotation = SourceRotation.NONE
                    previewTick++
                }
            }
            is RecipeReadResult.Unsupported -> {
                hasRecipe = true
                if (!preserveSessionCursor) {
                    Recipes.resetToDefaults(state, settings, profiles)
                    rotation = SourceRotation.NONE
                    previewTick++
                }
                status = ctx.getString(R.string.editor_status_recipe_newer_version, restored.version)
                snackbarHost.currentSnackbarData?.dismiss()
                snackbarHost.showSnackbar(
                    message = ctx.getString(R.string.editor_snack_recipe_newer_version),
                    withDismissAction = true,
                )
            }
            is RecipeReadResult.CorruptQuarantineFailed,
            is RecipeReadResult.IoFailure -> {
                hasRecipe = true
                if (!preserveSessionCursor) {
                    Recipes.resetToDefaults(state, settings, profiles)
                    rotation = SourceRotation.NONE
                    previewTick++
                }
                val reason = when (restored) {
                    is RecipeReadResult.CorruptQuarantineFailed -> restored.reason
                    is RecipeReadResult.IoFailure -> restored.reason
                    else -> error("unreachable")
                }
                Diag.w("recipe unavailable and writes paused: $reason")
                status = ctx.getString(R.string.editor_status_recipe_unavailable)
                snackbarHost.currentSnackbarData?.dismiss()
                snackbarHost.showSnackbar(
                    message = ctx.getString(R.string.editor_snack_recipe_unavailable),
                    withDismissAction = true,
                )
            }
        }
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

    // ACCESS_MEDIA_LOCATION (API 29+): only asked for when "Include location (GPS)" is on
    // and the source is a MediaStore photo, because that is the one case where Android
    // redacts the GPS bytes from the stream (#261). Denial is not a block: the export
    // proceeds and simply carries no GPS, exactly as it did before the permission existed.
    val mediaLocationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            status = ctx.getString(R.string.editor_status_media_location_denied)
        }
    }
    fun mediaLocationPermissionMissing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            exportVm.current.keepGps &&
            requiresMediaLocationPermissionForGpsExport(sourceUri) &&
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
    fun requestMediaLocationIfMissing() {
        if (mediaLocationPermissionMissing()) {
            runCatching {
                mediaLocationPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }
    }
    // The export runs under a foreground service, and on API 33+ that service's
    // ongoing notification is silently suppressed unless POST_NOTIFICATIONS is
    // granted. The manifest has declared the permission since the service landed,
    // but nothing ever requested it — so on a real install the export notification
    // never appeared at all. Asked in context at the first export, and never
    // blocking: the service's kill-resistance (oom_score_adj 50 vs 700) works
    // whether or not the notification can be drawn.
    fun notificationPermissionMissing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Granted or denied, the export proceeds either way. The media-location request
        // is chained here rather than launched alongside, so the two system dialogs are
        // never requested back-to-back on API 33+.
        requestMediaLocationIfMissing()
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            status = ctx.getString(R.string.editor_status_storage_permission_required)
            Toast.makeText(
                ctx,
                ctx.getString(R.string.editor_toast_storage_permission),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun visibleSourceState() = EditorSourceState(
        uri = sourceUri?.toString(),
        kind = sourceKind,
        displayName = sourceName,
        authorizationRequired = sourceAuthorizationRequired,
    )

    fun reconcileVisibleSourceAfterFailure(durable: SourceRestoreResult?) {
        when (durable) {
            SourceRestoreResult.Demo -> {
                if (sourceUri != null || sourceKind != SourceKind.DEMO) {
                    retireSourceResources()
                    resetEditorCursorForSourceChange()
                }
                sourceUri = null
                sourceKind = SourceKind.DEMO
                sourceName = ctx.getString(R.string.editor_source_demo_name)
                sourceAuthorizationRequired = false
                rotation = SourceRotation.NONE
                status = ctx.getString(R.string.editor_status_demo_image)
            }
            is SourceRestoreResult.Ready -> {
                val switched = !shouldRetainEditorCursor(visibleSourceState(), durable)
                if (switched) {
                    retireSourceResources()
                    resetEditorCursorForSourceChange()
                }
                sourceUri = Uri.parse(durable.ref.uri)
                sourceKind = SourceKind.valueOf(durable.ref.kind)
                sourceName = durable.ref.displayName
                sourceAuthorizationRequired = false
                if (switched) rotation = SourceRotation.NONE
                status = ctx.getString(R.string.editor_status_selection_failed_restored)
            }
            is SourceRestoreResult.NeedsAuthorization -> {
                val switched = !shouldRetainEditorCursor(visibleSourceState(), durable)
                retireSourceResources()
                if (switched) {
                    resetEditorCursorForSourceChange()
                }
                sourceUri = Uri.parse(durable.ref.uri)
                sourceKind = SourceKind.valueOf(durable.ref.kind)
                sourceName = durable.ref.displayName
                sourceAuthorizationRequired = true
                if (switched) rotation = SourceRotation.NONE
                status = ctx.getString(R.string.editor_status_selection_failed_auth)
            }
            SourceRestoreResult.None -> {
                if (sourceUri != null && sourceKind != SourceKind.DEMO) {
                    retireSourceResources()
                    sourceAuthorizationRequired = true
                    status = ctx.getString(R.string.editor_status_selection_failed_auth)
                } else {
                    sourceUri = null
                    sourceKind = SourceKind.DEMO
                    sourceName = ctx.getString(R.string.editor_source_demo_name)
                    sourceAuthorizationRequired = false
                    rotation = SourceRotation.NONE
                    status = ctx.getString(R.string.editor_status_source_unavailable_demo)
                }
            }
            is SourceRestoreResult.Invalid, null -> {
                sourceAuthorizationRequired = sourceUri != null && sourceKind != SourceKind.DEMO
                if (sourceAuthorizationRequired) retireSourceResources()
                status = ctx.getString(R.string.editor_status_source_state_unavailable)
            }
        }
        previewTick++
    }

    fun adoptSource(
        uri: Uri,
        kind: SourceKind,
        displayName: String,
        readyStatus: String,
        probeLabel: String? = null,
    ) {
        val selectionGeneration = sourceMutationGate.begin()
        // The mutation starts in the process-owned runtime before this Activity awaits it.
        // Activity recreation may cancel the waiter, but it cannot drop the grant/store write.
        val pendingAcquire = sourceRuntime.submitReconciled(selectionGeneration) {
            sourceAccess.acquire(
                uri.toString(),
                kind.name,
                AtomicJsonStore.truncateUtf16Safely(displayName, 512),
            )
        }
        scope.launch {
            val outcome = pendingAcquire.await() ?: return@launch
            if (!sourceMutationGate.isCurrent(selectionGeneration)) return@launch
            when (outcome) {
                is ReconciledSourceMutation.Applied -> {
                    val ref = outcome.value
                    val switched = sourceUri?.toString() != uri.toString() || sourceKind != kind
                    if (switched) {
                        retireSourceResources()
                        resetEditorCursorForSourceChange()
                    }
                    sourceUri = uri
                    sourceKind = kind
                    sourceName = displayName
                    sourceAuthorizationRequired = false
                    if (switched) rotation = SourceRotation.NONE
                    status = if (ref.accessMode == SourceAccessMode.PERSISTED) {
                        readyStatus
                    } else {
                        ctx.getString(R.string.editor_status_access_temporary, readyStatus)
                    }
                    previewTick++
                    probeLabel?.let(Ticket139EditorProbe::publishProbeSource)
                }
                is ReconciledSourceMutation.Rejected -> {
                    Diag.w("source adoption failed: ${outcome.failure.message}")
                    reconcileVisibleSourceAfterFailure(outcome.durableState)
                    snackbarHost.currentSnackbarData?.dismiss()
                    snackbarHost.showSnackbar(
                        ctx.getString(R.string.editor_snack_source_open_failed),
                        withDismissAction = true,
                    )
                }
            }
            checkpointEditorSession()
        }
    }

    LaunchedEffect(Unit) {
        if (!Ticket139EditorProbe.isArmed()) return@LaunchedEffect
        Ticket139EditorProbe.sourceProbeRequests.collect { request ->
            adoptSource(
                uri = Uri.parse(Ticket139EditorProbe.sourceProbeUri(request.label)),
                kind = SourceKind.PHOTO,
                displayName = "ticket139-source-${request.label.lowercase()}.png",
                readyStatus = "source probe ${request.label} selected",
                probeLabel = request.label,
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!Ticket139EditorProbe.isArmed()) return@LaunchedEffect
        Ticket139EditorProbe.previewCompletionRequests.collect {
            if (
                Ticket139EditorProbe.sourceProbeLabel(sourceUri?.toString()) == "A" &&
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
    fun refuseUnsupported(statusRes: Int, snackRes: Int) {
        status = ctx.getString(statusRes)
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(ctx.getString(snackRes))
        }
    }
    fun adoptIncomingImage(uri: Uri) {
        val name = uri.lastPathSegment ?: "raw"
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        when (detectSourceKind(name, mime)) {
            // MotionCam RAW-video container: recognized (see McrawContainer /
            // docs/RESEARCH_MCRAW.md) but native frame decode isn't wired yet, so don't set
            // a RAW source that would fail — tell the user instead.
            SourceFileKind.MCRAW -> refuseUnsupported(
                R.string.editor_status_mcraw_unsupported,
                R.string.editor_snack_mcraw_coming,
            )
            // A motion clip. The engine simulates stills today, so refuse it the same honest
            // way as .mcraw rather than failing deep inside the RAW decoder.
            SourceFileKind.VIDEO -> refuseUnsupported(
                R.string.editor_status_video_unsupported,
                R.string.editor_snack_video_coming,
            )
            // A JPEG/HEIC chosen via the RAW document picker: process it on the normal photo
            // path rather than forcing it through LibRaw (which would fail, then fall back to
            // a lossy display-referred decode).
            SourceFileKind.PHOTO -> adoptSource(
                uri,
                SourceKind.PHOTO,
                name.substringAfterLast('/'),
                ctx.getString(R.string.editor_status_photo_selected),
            )
            // RAW/DNG, and every ambiguous content URI (e.g. MIUI document IDs with no
            // extension), so a genuine DNG is never misrouted.
            SourceFileKind.RAW -> adoptSource(
                uri,
                SourceKind.RAW,
                ctx.getString(R.string.editor_source_raw_name, name.substringAfterLast('/')),
                ctx.getString(R.string.editor_status_raw_selected),
            )
        }
    }
    val rawPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) adoptIncomingImage(uri)
    }
    // #162 inbound import: consume the Activity's pending VIEW/SEND exactly once and
    // route it through the same dispatch as a picked document. A rejected shape
    // (non-content scheme, missing stream) surfaces a status instead of adopting.
    val pendingIncoming by incomingImageIntent
    LaunchedEffect(pendingIncoming) {
        val intent = pendingIncoming ?: return@LaunchedEffect
        incomingImageIntent.value = null
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return@LaunchedEffect
        val incoming = incomingImageUri(intent)
        if (incoming == null) {
            status = ctx.getString(R.string.editor_status_shared_image_unsupported)
            return@LaunchedEffect
        }
        adoptIncomingImage(incoming)
    }
    val presetImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Read the SAF stream off-main; decode (Compose-state write) on main.
            scope.launch {
                val text = withContext(dispatchers.io) {
                    runCatching { presetRepo.readUri(uri) }.getOrNull()
                }
                if (text == null) { status = ctx.getString(R.string.editor_status_preset_import_failed); return@launch }
                runCatching {
                    // Decode into a detached clone first. The live Compose state changes only
                    // after the entire bounded/versioned import has validated successfully.
                    val candidate = ParamsState()
                    Presets.decode(Presets.encode(state), candidate)
                    Presets.decode(org.json.JSONObject(text), candidate)
                    Presets.decode(Presets.encode(candidate), state)
                }
                    .onSuccess { status = ctx.getString(R.string.editor_status_preset_imported); previewTick++ }
                    .onFailure { status = ctx.getString(R.string.editor_status_preset_import_failed_reason, it.message) }
            }
        }
    }
    val presetExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // Serialize on main (live Compose-state read); write off-main.
                val json = runCatching { Presets.toJsonString(state) }.getOrNull()
                if (json == null) { status = ctx.getString(R.string.editor_status_preset_export_failed); return@launch }
                val r = withContext(dispatchers.io) { runCatching { presetRepo.exportJson(uri, json) } }
                r.onSuccess { status = ctx.getString(R.string.editor_status_preset_exported) }
                    .onFailure { status = ctx.getString(R.string.editor_status_preset_export_failed_reason, it.message) }
            }
        }
    }
    // Exports the SELECTED SAVED preset, byte-for-byte as stored. Distinct from
    // [presetExporter] above, which serialises live editor state — that one sat under
    // the saved-preset dropdown and looked like it exported the selection.
    var pendingPresetExport by remember { mutableStateOf<String?>(null) }
    val savedPresetExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val name = pendingPresetExport
        pendingPresetExport = null
        if (uri != null && name != null) {
            scope.launch {
                val r = withContext(dispatchers.io) {
                    runCatching { presetRepo.exportJson(uri, presetRepo.read(name)) }
                }
                r.onSuccess { status = ctx.getString(R.string.editor_status_preset_exported) }
                    .onFailure {
                        status = ctx.getString(
                            R.string.editor_status_preset_export_failed_reason, it.message,
                        )
                    }
            }
        }
    }
    val lutExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val text = pendingLutText
        if (uri != null && text != null) {
            scope.launch {
                // SAF write off-main; status (Compose state) lands back on main.
                val r = withContext(dispatchers.io) { runCatching { saveTextToUri(ctx, uri, text) } }
                r.onSuccess { status = ctx.getString(R.string.editor_status_lut_saved) }
                    .onFailure { status = ctx.getString(R.string.editor_status_lut_save_failed, it.message) }
            }
        }
        pendingLutText = null
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
    // RAW/DNG: a compressed (lossy-JPEG / JPEG-XL) Samsung/Pixel Expert-RAW DNG makes
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
            )
            // Begin publication authority in the same main-thread turn as the snapshot. An
            // older caller cannot resume later and reorder itself ahead of a newer UI state.
            CachedSourceDecodeRequest(decode, cache.beginRequest(cacheRequest))
        }

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

    // Zoom step 4: once the fit preview has settled and the editor stays idle, decode the
    // zoom source into zoomSourceCache so the first pinch starts from a cache hit (see
    // ZoomPrewarm). Cancelled by the next edit; a zoom during the decode joins its flight.
    fun scheduleZoomPrewarm() {
        if (!sourceRenderAllowed) return
        zoomPrewarmJobRef.value?.cancel()
        zoomPrewarmJobRef.value = scope.launch {
            delay(ZoomPrewarm.IDLE_DELAY_MS)
            val start = System.nanoTime()
            runCatching { loadSourceCachedForZoom(MAX_EDGE_PX).close() }
                .onSuccess {
                    Diag.i("zoom prewarm ready ms=${(System.nanoTime() - start) / 1_000_000L}")
                }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    Diag.w("zoom prewarm failed: ${t.message}")
                }
        }
    }

    // 100% grain magnifier: render a real FULL-RESOLUTION crop around a tapped point.
    //
    // PERF/RACE: each tap previously launched an independent coroutine with no
    // cancellation, so rapid taps ran several full-res simulate()s in parallel and
    // raced their results into magnifierBitmap (last-write-wins, wasteful, and an
    // orphaned bitmap per superseded run). We now cancel the prior in-flight render
    // before starting a new one (last-tap-wins), and the result is only published if
    // the coroutine is still active — a cancelled run recycles its own bitmap instead
    // of writing it, so a superseded render can never leak or land on screen.
    fun openMagnifier(nx: Float, ny: Float) {
        if (!sourceRenderAllowed) return
        val e = engine ?: return
        magnifierJobRef.value?.cancel()
        val renderSnapshot = state.captureInteractiveRenderSnapshot(previewTick)
        val publicationTicket = magnifierPublicationGate.begin(renderSnapshot.renderKey)
        magnifierOpen = true
        magnifierBitmap = null
        magnifierRendering = true
        magnifierStatus = ctx.getString(R.string.editor_magnifier_rendering)
        magnifierJobRef.value = scope.launch {
            var renderId = 0L
            val result = runCatching {
                withOwnedContext(
                    context = dispatchers.default,
                    dispose = { bitmap: Bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    },
                ) {
                    loadSourceCachedForZoom(MAX_EDGE_PX).use { fullLease ->
                        val full = fullLease.image
                        cropLinearImage(full, nx, ny, MAGNIFIER_CROP_PX).use { crop ->
                            // Effective film format so the crop's pixel_size_um matches the proxy it was
                            // cut from (the crop spans only cropFrac of the frame). Without it the engine
                            // treats the crop as a whole 35mm frame and grain/halation (µm-based) render
                            // too weak even at 1:1. See toParams(filmFormatMmOverride).
                            val cropFrac = maxOf(crop.width, crop.height).toFloat() /
                                maxOf(full.width, full.height).coerceAtLeast(1)
                            // SimResult holds a native off-heap buffer; close() frees it once the
                            // bitmap copy is made.
                            runCancellableNative(
                                onLateResult = { late ->
                                    late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                                    late.close()
                                },
                            ) { cancellation ->
                                e.simulate(
                                    crop,
                                    renderSnapshot.paramsForCrop(cropFrac),
                                    RenderKind.MAGNIFIER,
                                    cancellation,
                                )
                            }.use { res ->
                                renderId = res.renderId
                                simResultToBitmapGraded(
                                    res,
                                    renderSnapshot.cctfEncoded,
                                    renderSnapshot.saturation,
                                    renderSnapshot.vibrance,
                                    renderSnapshot.gamutCompress,
                                    renderSnapshot.localAdjustments,
                                )
                            }
                        }
                    }
                }
            }
            if (
                !isActive || !magnifierPublicationGate.canPublish(
                    publicationTicket,
                    latestMagnifierRenderKey,
                )
            ) {
                SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
                // Superseded by a newer tap (this job was cancelled): drop our render so
                // it neither overwrites the latest request nor leaks an orphaned bitmap.
                result.getOrNull()?.let { if (!it.isRecycled) it.recycle() }
                return@launch
            }
            result.onSuccess {
                magnifierBitmap = it
                SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
                magnifierStatus = ctx.getString(R.string.editor_magnifier_done, it.width, it.height)
            }.onFailure {
                SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
                magnifierStatus = ctx.getString(R.string.editor_magnifier_failed, it.message)
            }
            magnifierRendering = false
        }
    }

    // Lightroom-style zoom: render the currently-visible region (a native-pixel crop of the
    // source) at ~screen resolution via the FAST preview path, then overlay it sharply on the
    // graphicsLayer-scaled proxy. Modeled on openMagnifier's last-wins cancellation. Memory is
    // bounded by ROI_RENDER_MAX_PX (a few MB), independent of source megapixels — this is the
    // Lightroom "smart preview" strategy, so it sidesteps the full-res OOM entirely. Suppressed
    // while cropping or comparing (those branches own the gestures / have no zoom).
    fun renderRoi(roi: RoiRect) {
        if (!sourceRenderAllowed) return
        val e = engine ?: return
        if (toolVm.current.anyOverlayOpen || viewerVm.current.compare) return
        // A settle inside the patch already on screen (or already rendering) at the same edit
        // and sharpness needs no new render: keep it rather than cancel and redo it.
        roiPatchTracker.reuseReason(
            roi, previewTick, roiOverlay, roiJobRef.value, ROI_RENDER_MAX_PX,
        )?.let { reason ->
            if (reason == RoiPatchTracker.REUSED_SHOWN) {
                // The view came back inside the shown patch: any render still running for
                // an intermediate viewport must not land over it (it would not cover this
                // view, and no further settle would come to fix that).
                roiPublicationGate.invalidate()
                roiJobRef.value?.cancel()
            }
            Diag.i("zoom roi reused $reason")
            return
        }
        roiJobRef.value?.cancel()
        val renderSnapshot = state.captureInteractiveRenderSnapshot(previewTick)
        val roiPublicationTicket = roiPublicationGate.begin(renderSnapshot.renderKey)
        roiJobRef.value = scope.launch {
            val pendingOutcomes = PendingRenderOutcomes()
            // Zoom timing (rawr study step 1): settle -> source -> draft -> final, all in ms
            // from the settle. One line per completed or superseded ROI render.
            val timing = RoiTiming()
            val result = runCatching {
                loadSourceCachedForZoom(MAX_EDGE_PX).use { fullLease ->
                    timing.sourceMs = timing.sinceStartMs()
                    val full = fullLease.image
                    // Render a grid-snapped patch around the viewport, not the viewport
                    // itself (RoiPatch): the margin keeps halation/blur light from just off
                    // screen, and a later pan inside the patch reuses this render.
                    val box = RoiPatch.box(roi, full.width, full.height)
                    val patchRect = RoiPatch.rectOf(box, full.width, full.height)
                    val scale = RoiPatch.neededScale(
                        roi, full.width, full.height, ROI_RENDER_MAX_PX,
                    )
                    val draftScale = RoiPatch.neededScale(
                        roi, full.width, full.height, ROI_DRAFT_MAX_PX,
                    )
                    val patchRecord = RoiPatchRecord(
                        renderKey = renderSnapshot.renderKey,
                        srcW = full.width,
                        srcH = full.height,
                        box = box,
                        scale = scale,
                    )
                    currentCoroutineContext()[Job]?.let { job ->
                        roiPatchTracker.inFlightJob = job
                        roiPatchTracker.inFlight = patchRecord
                    }
                    timing.cropW = box.width
                    timing.cropH = box.height
                    withOwnedContext(
                        context = dispatchers.default,
                        dispose = LinearImage::close,
                    ) {
                        cropLinearImageBox(full, box)
                    }.use { crop ->
                        // Effective film format so the crop's pixel_size_um matches the proxy (the crop
                        // spans only cropFrac of the frame); keeps grain/halation at the right strength
                        // when zoomed instead of treating the crop as a whole frame.
                        val cropFrac = maxOf(crop.width, crop.height).toFloat() /
                            maxOf(full.width, full.height).coerceAtLeast(1)
                        suspend fun renderAt(edge: Int): Pair<Bitmap, Long> =
                            withOwnedContext(
                                context = dispatchers.default,
                                dispose = { rendered: Pair<Bitmap, Long> ->
                                    if (!rendered.first.isRecycled) rendered.first.recycle()
                                },
                            ) {
                                runCancellableNative(
                                    onLateResult = { late ->
                                        late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                                        late.close()
                                    },
                                ) { cancellation ->
                                    e.simulatePreview(
                                        crop,
                                        renderSnapshot.paramsForCrop(
                                            cropFraction = cropFrac,
                                            previewMaxSize = edge,
                                        ),
                                        RenderKind.ROI,
                                        cancellation,
                                    )
                                }.use { res ->
                                    pendingOutcomes.add(res.renderId)
                                    simResultToBitmapGraded(
                                        res,
                                        renderSnapshot.cctfEncoded,
                                        renderSnapshot.saturation,
                                        renderSnapshot.vibrance,
                                        renderSnapshot.gamutCompress,
                                        renderSnapshot.localAdjustments,
                                    ) to res.renderId
                                }
                            }
                        // DRAFT pass: a fast low-res sharp crop so the zoomed region resolves ~5x sooner,
                        // then refine to ROI_RENDER_MAX_PX. Every bitmap is published (then owned by the
                        // DisposableEffect(roiOverlay), which recycles the prior one) OR recycled here —
                        // so a cancel mid-flight never leaks one.
                        val (draft, draftRenderId) =
                            renderAt(RoiPatch.renderEdge(box, draftScale))
                        timing.draftMs = timing.sinceStartMs()
                        if (
                            isActive && roiPublicationGate.canPublish(
                                roiPublicationTicket,
                                latestRoiRenderKey,
                            )
                        ) {
                            roiOverlay = RoiOverlay(
                                draft, patchRect.cxN, patchRect.cyN, patchRect.wN, patchRect.hN,
                            )
                            pendingOutcomes.resolve(draftRenderId, AppRenderOutcome.CONSUMED)
                        } else {
                            pendingOutcomes.resolveAll(AppRenderOutcome.SUPERSEDED)
                            if (!draft.isRecycled) draft.recycle()
                            return@runCatching null
                        }
                        renderAt(RoiPatch.renderEdge(box, scale)).also {
                            timing.finalMs = timing.sinceStartMs()
                        }.let { (bmp, id) -> Triple(bmp, id, patchRecord to patchRect) }
                    }
                }
            }
            if (
                !isActive || !roiPublicationGate.canPublish(
                    roiPublicationTicket,
                    latestRoiRenderKey,
                )
            ) {
                pendingOutcomes.resolveAll(AppRenderOutcome.SUPERSEDED)
                // Superseded (cancelled): drop the final render (the draft, if published, stays
                // and is recycled by the DisposableEffect when the next overlay replaces it).
                result.getOrNull()?.first?.let { if (!it.isRecycled) it.recycle() }
                Diag.i(timing.line("superseded"))
                return@launch
            }
            // On success publish the sharp final; the prior overlay (the draft) is recycled by
            // the DisposableEffect(roiOverlay) below. On failure keep the scaled proxy.
            result.onSuccess { rendered ->
                rendered?.let { (bmp, renderId, patch) ->
                    val (record, rect) = patch
                    val overlay = RoiOverlay(bmp, rect.cxN, rect.cyN, rect.wN, rect.hN)
                    roiOverlay = overlay
                    roiPatchTracker.shownOverlay = overlay
                    roiPatchTracker.shown = record
                    pendingOutcomes.resolve(renderId, AppRenderOutcome.CONSUMED)
                }
                Diag.i(timing.line("done"))
            }.onFailure {
                pendingOutcomes.resolveAll(AppRenderOutcome.FAILED)
                Diag.i(timing.line("failed"))
            }
        }
    }

    fun clearRoi() {
        roiPublicationGate.invalidate()
        roiJobRef.value?.cancel()
        roiOverlay = null  // bitmap recycled by DisposableEffect(roiOverlay)
    }

    // Everything that feeds the NATIVE render for a fit preview at [fullEdge]:
    // the engine-param snapshot + the decode key. Reads Compose state — call on
    // the main thread. Grade inputs are deliberately absent (see GradeCache).
    fun gradeCacheKey(fullEdge: Int): GradeCache.Key {
        val filmBalance =
            if (state.balanceToFilmStock && FilmStockBalance.isMeaningful(ctx, state.filmProfile)) state.filmProfile else ""
        return GradeCache.Key(
            engineParams = state.toParams(skipGrainHalation = true),
            decode = GradeCache.DecodeKey(
                uri = sourceUri?.toString(), kind = sourceKind.name,
                whiteBalance = state.rawWhiteBalance, temperature = state.rawTemperature,
                tint = state.rawTint, creativeTemp = state.creativeWbTemp,
                creativeTint = state.creativeWbTint, filmBalance = filmBalance,
                rotationDegrees = rotation.degrees, maxEdge = fullEdge,
            ),
        )
    }

    // Live DRAFT pass — the Lightroom-style render-system "port": on EVERY edit (a slider drag,
    // a preset/dropdown/toggle, a rotation), paint a small fast proxy so the image tracks the
    // change in ~hundreds of ms instead of sitting on the stale frame until the full settle
    // render lands ~1s later. A new revision cancels the prior coroutine; native cancellation
    // cooperates where supported, and the publication ticket rejects any late return.
    // Cheap by construction: it renders ONLY when the full-edge proxy is already decoded (a
    // cache peek — never decodes here, so it cannot race the settle pass's first decode) and
    // just asks the engine for a smaller DRAFT_RENDER_MAX_PX pass. Quiet: it touches only
    // `preview`, never status/previewBusy; the crisp full pass still lands on settle below.
    // `engine` is a KEY, not just a guard. It is assigned asynchronously once the native
    // engine finishes loading, which can land AFTER this effect first composes — and the
    // `engine ?: return` below then retires the effect for good, because nothing re-runs it
    // until previewTick or sourceRenderAllowed changes. Today the recipe restore's
    // previewTick++ rescues that by accident; the key makes it deliberate.
    // Honest scope: this was written while chasing a cold-start hang and did NOT fix it.
    // It closes a real race that is simply not the one observed there.
    LaunchedEffect(previewTick, sourceRenderAllowed, engine) {
            if (!sourceRenderAllowed) return@LaunchedEffect
            val publicationTicket = publicationGate.begin(
                previewRevision,
                RenderPublicationPriority.DRAFT,
            )
            val e = engine ?: return@LaunchedEffect
            if (toolVm.current.anyOverlayOpen || viewerVm.current.compare) return@LaunchedEffect
            // Lightroom's ICBSliderTrackingBegin/End gate. `interacting` has been
            // written by SliderInteraction since the widget shipped but was never
            // read, so the draft pass fired on EVERY edit — including discrete ones
            // (a switch, a dropdown, applying a preset), where it buys nothing: the
            // crisp settle pass is already only 500 ms away, so the draft just burns
            // a render and flashes a soft frame before the sharp one lands.
            //
            // Reading it here is what the widget's own doc comment always described:
            // draft while a slider is actually moving, straight to crisp otherwise.
            if (!interacting.value) return@LaunchedEffect
            val fullEdge = state.previewMaxSize.coerceAtLeast(256)
            // Grade-only edit? Re-grade the retained full-quality settle result in
            // pure Kotlin — zero native work, and it beats a low-res draft on quality.
            gradeCache.lookup(gradeCacheKey(fullEdge))?.let { pristine ->
                val gradeResult = try {
                    runCatching {
                        withOwnedContext(
                            context = dispatchers.default,
                            dispose = { bitmap: Bitmap ->
                                if (!bitmap.isRecycled) bitmap.recycle()
                            },
                        ) {
                            pristine.withScratch { scratch ->
                                gradeBufferToBitmap(scratch, pristine.width,
                                    pristine.height, pristine.colorSpace, state.savingCctfEncoding,
                                    state.saturation, state.vibrance, state.gamutCompress,
                                    state.localAdjustments)
                            }
                        }
                    }
                } finally {
                    pristine.close()
                }
                gradeResult.onSuccess { bmp ->
                    withContext(dispatchers.main) {
                        if (publicationGate.tryClaim(publicationTicket)) {
                            preview = bmp
                        } else if (!bmp.isRecycled) {
                            bmp.recycle()
                        }
                    }
                }
                    .onFailure { Diag.w("render mode=draft failed: ${it.message}") }
                return@LaunchedEffect
            }
            // Progressive ladder rung. The edge is a setting rather than a constant
            // so the coarse/fine trade can be swept on a real device: lower = the
            // image tracks the finger more closely, higher = the live frame is closer
            // to what the settle pass will show. Defaults to DRAFT_RENDER_MAX_PX, so
            // an untouched install renders exactly as before.
            val draftEdge = minOf(settings.effectiveDraftEdge(), fullEdge)
            if (draftEdge >= fullEdge) return@LaunchedEffect // no meaningful step-down to draft
            val proxyRequest = cachedSourceDecodeRequest(sourceCache, fullEdge)
            val proxyLease = sourceCache.acquire(
                proxyRequest.ticket,
            ) ?: return@LaunchedEffect                      // proxy not cached yet — settle owns the decode
            var renderId = 0L
            val draftResult = proxyLease.use { lease ->
                runCatching {
                    withOwnedContext(
                        context = dispatchers.default,
                        dispose = { bitmap: Bitmap ->
                            if (!bitmap.isRecycled) bitmap.recycle()
                        },
                    ) {
                        runCancellableNative(
                            onLateResult = { late ->
                                late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                                late.close()
                            },
                        ) { cancellation ->
                            val draftStartNs = System.nanoTime()
                            e.simulatePreview(
                                lease.image,
                                state.toParams(previewMaxSizeOverride = draftEdge, skipGrainHalation = true),
                                cancellation = cancellation,
                            ).also {
                                // Only a completed native render reaches here (cancel/failure
                                // throws), so the calibrator never learns from a cut-short run.
                                settings.recordDraftTiming(
                                    draftEdge,
                                    (System.nanoTime() - draftStartNs) / 1_000_000L,
                                )
                            }
                        }.use { res ->
                            renderId = res.renderId
                            simResultToBitmapGraded(res, state.savingCctfEncoding, state.saturation, state.vibrance, state.gamutCompress, state.localAdjustments)
                        }
                    }
                }
            }
            if (!isActive || !publicationGate.tryClaim(publicationTicket)) {
                SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
                draftResult.getOrNull()?.let { if (!it.isRecycled) it.recycle() }
                return@LaunchedEffect
            }
            draftResult.onSuccess { bmp ->
                try {
                    withContext(dispatchers.main) { preview = bmp }
                    SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
                } catch (cancelled: CancellationException) {
                    SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
                    if (!bmp.isRecycled) bmp.recycle()
                    throw cancelled
                }
            }.onFailure {
                SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
                Diag.w("render mode=draft failed: ${it.message}")
            }
    }

    // Crisp FINAL preview render on settle: re-runs whenever params, source or rotation
    // change, after a debounce so it lands once the user pauses. The live DRAFT effect above
    // keeps the image moving during the edit (Lightroom's loupe), so this pass goes straight
    // to the full-resolution result instead of a separate coarse pass.
    // Keyed on engine for the same reason as the draft effect above: without it a cold
    // start that composes before the native engine is ready never renders at all.
    LaunchedEffect(previewTick, sourceRenderAllowed, engine) {
        if (!sourceRenderAllowed) return@LaunchedEffect
        val e = engine ?: return@LaunchedEffect
        val previewProbeLabel = Ticket139EditorProbe.sourceProbeLabel(sourceUri?.toString())
        // Settle debounce, raised from 350ms. Each preview render maxes all CPU cores for
        // ~1s, so waiting a bit longer for the user to pause means fewer renders that start
        // then get cancelled mid-flight on the next edit ("coroutine scope left the
        // composition" in the logcat) — wasted all-core CPU that drains battery.
        delay(500)
        val publicationTicket = publicationGate.begin(
            previewRevision,
            RenderPublicationPriority.SETTLE,
        )
        previewBusy = true
        renderErr = null
        status = ctx.getString(R.string.editor_status_rendering_preview)
        // Every reset below used to be a plain statement. Cancellation skips every
        // statement after the suspension point it unwinds from, so a render cancelled
        // mid-flight left decoding/previewBusy set and the status pinned to
        // "rendering preview…" — with no work running, all engine threads idle,
        // and nothing to restart the effect (its keys are previewTick and
        // sourceRenderAllowed). The editor sat there permanently. Observed on a
        // 4080x3060 DNG after a speculative export pre-render exhausted the memory
        // budget. A finally runs on cancellation, which is the whole point.
        var settled = false
        try {
            val renderStart = System.currentTimeMillis()
            val fullEdge = state.previewMaxSize.coerceAtLeast(256)
            // Key built on MAIN (reads Compose state); the same snapshot renders below,
            // so the key and the render can never disagree.
            val cacheKey = gradeCacheKey(fullEdge)
            val cacheStoreTicket = gradeCache.beginStore(cacheKey)
            val cached = gradeCache.lookup(cacheKey)
            val prevBefore = beforePreview
            var renderId = 0L
            var completionProbeHandled = false
            val result = try {
                runCatching {
                    withOwnedContext(
                        context = dispatchers.default,
                        dispose = { submission: Triple<Bitmap, Bitmap, Boolean> ->
                            if (!submission.second.isRecycled) submission.second.recycle()
                            if (submission.third && !submission.first.isRecycled) {
                                submission.first.recycle()
                            }
                        },
                    ) {
                        if (cached != null && prevBefore != null) {
                            // Grade-only edit (identical engine inputs): re-grade the retained
                            // pristine engine result — ZERO native work. The ungraded `before`
                            // is unchanged by construction.
                            var after: Bitmap? = null
                            var transferred = false
                            try {
                                val rendered = cached.withScratch { scratch ->
                                    gradeBufferToBitmap(scratch, cached.width,
                                        cached.height, cached.colorSpace, state.savingCctfEncoding,
                                        state.saturation, state.vibrance, state.gamutCompress,
                                        state.localAdjustments)
                                }
                                after = rendered
                                val submission = Triple(prevBefore, rendered, false)
                                transferred = true
                                submission
                            } finally {
                                if (!transferred) after?.let { if (!it.isRecycled) it.recycle() }
                            }
                        } else {
                            decoding = true
                        // The live DRAFT effect above already paints a fast low-res proxy during the
                        // edit, so this settle pass goes straight to the crisp full render (no separate
                        // coarse pass / extra fresh decode). Cached proxy source — re-decodes only when
                        // a decode-affecting key (URI/kind/WB/temp/tint/rotation/edge) changed;
                        // look-param edits reuse it.
                            var before: Bitmap? = null
                            var after: Bitmap? = null
                            var transferred = false
                            var completionProbeHeld = false
                            try {
                                val submission = loadSourceCachedForPreview(fullEdge).use { lease ->
                                    decoding = false
                                    // Cut to the engine's crop box so compare/peek register (#254).
                                    val ownedBefore = beforeFrameBitmap(lease.image, cacheKey.engineParams)
                                    before = ownedBefore
                                    // Fit preview skips grain/halation (the user's "grain at 100%" choice): they
                                    // are rendered by the zoom ROI, the magnifier and export, never the fit settle.
                                    // Render with cacheKey.engineParams — the exact snapshot the key hashed.
                                    val ownedAfter = runCancellableNative(
                                        onLateResult = { late ->
                                            late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                                            late.close()
                                        },
                                    ) { cancellation ->
                                        e.simulatePreview(
                                            lease.image,
                                            cacheKey.engineParams,
                                            cancellation = cancellation,
                                        )
                                    }.use { res ->
                                        renderId = res.renderId
                                        // Retain the PRISTINE engine output BEFORE the grade mutates it.
                                        res.acquireDataLease().use { resultLease ->
                                            val cacheOutcome = storeGradeCacheBestEffort {
                                                gradeCache.store(
                                                    cacheStoreTicket,
                                                    cacheKey,
                                                    resultLease.data,
                                                    res.width,
                                                    res.height,
                                                    res.colorSpace,
                                                )
                                            }
                                            if (cacheOutcome == GradeCacheStoreOutcome.CAPACITY_DENIED) {
                                                Diag.w("grade cache skipped: memory admission denied")
                                            }
                                        }
                                        val rendered = simResultToBitmapGraded(
                                            res,
                                            state.savingCctfEncoding,
                                            state.saturation,
                                            state.vibrance,
                                            state.gamutCompress,
                                            state.localAdjustments,
                                        ).also { bitmap -> after = bitmap }
                                        // The test seam is exactly the completed native settle's
                                        // production completion-before-publication boundary. The
                                        // SimResult and decoded-source lease remain owned here.
                                        completionProbeHeld = Ticket139EditorProbe
                                            .awaitCompletedPreviewBeforePublication(previewProbeLabel)
                                        rendered
                                    }
                                    Triple(ownedBefore, ownedAfter, true)
                                }
                                if (completionProbeHeld) {
                                    // B cancels this LaunchedEffect while A is held. Complete the
                                    // actual gate decision and full resource cleanup synchronously
                                    // here, before withOwnedContext's prompt-cancellation return can
                                    // discard the owned Triple. This branch is test-armed only.
                                    val claimed = publicationGate.tryClaim(publicationTicket)
                                    Ticket139EditorProbe.publishPreviewDecision(
                                        previewProbeLabel,
                                        claimed,
                                    )
                                    SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
                                    val (probeBefore, probeAfter, ownsProbeBefore) = submission
                                    if (!probeAfter.isRecycled) probeAfter.recycle()
                                    if (ownsProbeBefore && !probeBefore.isRecycled) probeBefore.recycle()
                                    completionProbeHandled = true
                                    Ticket139EditorProbe.publishPreviewCleanup(previewProbeLabel)
                                }
                                transferred = true
                                submission
                            } finally {
                                if (!transferred) {
                                    after?.let { if (!it.isRecycled) it.recycle() }
                                    before?.let { if (!it.isRecycled) it.recycle() }
                                }
                            }
                        }
                    }
                }
            } finally {
                cached?.close()
            }
            decoding = false
            if (completionProbeHandled) return@LaunchedEffect
            val publicationClaimed = publicationGate.tryClaim(publicationTicket)
            Ticket139EditorProbe.publishPreviewDecision(
                previewProbeLabel,
                publicationClaimed && isActive,
            )
            if (!isActive || !publicationClaimed) {
                SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
                result.getOrNull()?.let { (before, after, ownsBefore) ->
                    if (!after.isRecycled) after.recycle()
                    if (ownsBefore && !before.isRecycled) before.recycle()
                }
                Ticket139EditorProbe.publishPreviewCleanup(previewProbeLabel)
                return@LaunchedEffect
            }
            result.onSuccess { (before, after, _) ->
                beforePreview = before; preview = after
                SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
                Ticket139EditorProbe.publishLivePreview(previewProbeLabel)
                lastRenderMs = System.currentTimeMillis() - renderStart
                Diag.i("render mode=preview ${after.width}x${after.height} ${after.width * after.height}px ${lastRenderMs}ms")
                if (zoomPrewarmEligible) scheduleZoomPrewarm()
                renderErr = null
                status = ctx.getString(R.string.editor_status_preview_ready)
            }.onFailure {
                SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
                Diag.w("render mode=preview failed after ${System.currentTimeMillis() - renderStart}ms: ${it.message}")
                if (sourceKind != SourceKind.DEMO && it.isSourceAuthorizationFailure()) {
                    retireSourceResources()
                    sourceAuthorizationRequired = true
                    renderErr = ctx.getString(R.string.editor_render_error_auth_required)
                    status = ctx.getString(R.string.editor_status_source_expired)
                } else {
                    renderErr = it.message?.take(60)
                    status = ctx.getString(R.string.editor_status_preview_error, it.message)
                }
            }
            previewBusy = false
            settled = true
        } finally {
            decoding = false
            previewBusy = false
            // Not settled means cancelled, not finished. When a NEWER render caused the
            // cancellation it overwrites this within a frame; when nothing did, this is
            // the difference between a recoverable editor and a dead one.
            if (!settled) status = ctx.getString(R.string.editor_status_preview_interrupted)
        }
    }

    // GPU LUT preview feed (experimental, default OFF): after the CPU preview settles,
    // bake a 33³ .cube of the current look and keep it + the linear proxy for the GPU
    // path. The proxy decode is a cache hit (the main effect already loaded it), so this
    // adds only the LUT bake. Cancelled/replaced cleanly when previewTick advances.
    LaunchedEffect(previewTick, gpuEnabled, sourceRenderAllowed) {
        if (!gpuEnabled || !sourceRenderAllowed) {
            gpuLut = null
            gpuProxyLease?.close()
            gpuProxyLease = null
            gpuGain = 1f
            return@LaunchedEffect
        }
        val e = engine ?: return@LaunchedEffect
        delay(380)
        val gpuMaxEdge = state.previewMaxSize.coerceAtLeast(256)
        val gpuParams = state.toParams()
        var lutRenderId = 0L
        val bakeResult = runCatching {
            withOwnedContext(
                context = dispatchers.default,
                dispose = { submission: Triple<DecodedSourceCache.Lease, CubeLut?, Float> ->
                    submission.first.close()
                },
            ) {
                val lease = loadSourceCachedForPreview(gpuMaxEdge)
                try {
                    // SHAPER_SRGB is REQUIRED here, not optional: LutGpuPreview's shader
                    // always indexes through the sRGB transfer, so a LUT baked on the linear
                    // lattice would be sampled at the wrong cell entirely — mid-grey 0.18
                    // looking up at 0.46, i.e. overexposed and flat. The only unshaped bake
                    // is the user-facing .cube export, which no shader consumes.
                    val bake = runCancellableNative(
                        onLateResult = { late ->
                            late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                        },
                    ) { cancellation ->
                        e.bakeCubeLut(
                            gpuParams,
                            33,
                            SpektraEngine.SHAPER_SRGB,
                            cancellation,
                        )
                    }
                    lutRenderId = bake.renderId
                    val lut = CubeLut.parse(bake.text)
                    // The bake emits the pointwise transform at UNITY gain (a 3D LUT
                    // cannot carry auto-exposure), so meter the SAME proxy through the
                    // engine's own metering and hand the gain to the shader. Without it
                    // the GPU preview renders dark with lifted shadows — the scene lands
                    // in the film curve's toe. Metering is cheap next to the bake.
                    val gain = runCancellableNative { cancellation ->
                        e.exposureGain(lease.image, gpuParams, cancellation)
                    }
                    Triple(lease, lut, gain)
                } catch (failure: Throwable) {
                    lease.close()
                    throw failure
                }
            }
        }
        if (!isActive) {
            SimResult.reportOutcome(lutRenderId, AppRenderOutcome.SUPERSEDED)
            bakeResult.getOrNull()?.first?.close()
            return@LaunchedEffect
        }
        bakeResult.onSuccess { (lease, lut, gain) ->
            if (lut != null) {
                val previous = gpuProxyLease
                gpuProxyLease = lease
                previous?.close()
                gpuLut = lut; gpuGain = gain
                SimResult.reportOutcome(lutRenderId, AppRenderOutcome.CONSUMED)
                Diag.i("gpu lut baked ${lut.size}^3 gain=%.3f — fit preview runs on GPU".format(gain))
            } else {
                lease.close()
                SimResult.reportOutcome(lutRenderId, AppRenderOutcome.FAILED)
                Diag.w("gpu lut parse failed -> CPU preview")
            }
        }.onFailure {
            SimResult.reportOutcome(lutRenderId, AppRenderOutcome.FAILED)
            Diag.w("gpu lut bake failed: ${it.message} -> CPU preview")
        }
    }

    // re-trigger preview on any change to the params snapshot / source / rotation.
    //
    // PERF: wrap toParams() in derivedStateOf so the full SpektraParams tree (+ nested
    // Grain/Halation/DirCouplers/Glare/Diffusion) is allocated ONLY when a param state
    // actually changes — not on every recomposition of EditorScreen. derivedStateOf reads
    // exactly the same param states toParams() does, so it invalidates (and re-allocates)
    // iff one of those states changes, and otherwise returns the cached instance. The
    // resulting SpektraParams is structurally compared by LaunchedEffect's key machinery
    // (it is a data class), so a relaunch fires for every field that can alter the render —
    // identical trigger behaviour to the old per-frame snapshot, with no per-frame alloc.
    val snapshot by remember { derivedStateOf { state.toParams() } }
    // NOTE the three grade fields at the end. They are POST-ENGINE (not part of
    // `snapshot`, i.e. state.toParams()), and they were absent from every effect
    // key in this file — so moving Saturation, Vibrance or Gamut compression
    // changed the slider and nothing else: previewTick never bumped, the draft
    // collector below never emitted, and the image sat unchanged until some
    // unrelated engine parameter was touched. Three shipped controls that looked
    // dead.
    //
    // Adding them here does NOT cost a native render. gradeCacheKey() is built
    // from the ENGINE params + decode key + edge, so a grade-only edit hits the
    // retained pristine result and both the draft and settle paths take their
    // zero-native-work re-grade branch. That machinery was already correct; it
    // was simply never reached.
    //
    // This is the drift the audit's immutable EditorRenderRequest (PERF-01) is
    // meant to make impossible: three hand-maintained key lists that must agree
    // with each other and with toParams(), and did not. Until that lands, if you
    // add a post-engine control you must add it to ALL THREE effects below.
    LaunchedEffect(snapshot, sourceUri, sourceKind, rotation,
        state.rawWhiteBalance, state.rawTemperature, state.rawTint,
        state.creativeWbTemp, state.creativeWbTint, state.balanceToFilmStock,
        state.localAdjustments,
        state.saturation, state.vibrance, state.gamutCompress) { previewTick++ }

    // --- #179: idle full-resolution pre-render ---
    // Decode plus the engine are ~86% of a first export (BASE: 0.98 s decode + 5.02 s engine
    // inside a 6.05 s JPEG export), and the editor is idle while the user looks at the
    // preview. So do that work then, keep the engine's float output, and let the export run
    // the real encoder on it — the published bytes come from the same encoder either way, so
    // a pre-render can never make the exported file differ.
    //
    // Keyed on previewTick, which the effect above bumps for EVERY render-affecting edit:
    // that avoids adding a fourth hand-maintained key list to the three this file already
    // warns about, and Compose's own cancellation is the latest-wins rule for free.
    var prerenderDigest by remember(sourceUri) { mutableStateOf<Pair<String, Long>?>(null) }
    // Stood down after a speculative render ran the source out of memory. Keyed on
    // sourceUri like the digest, so a different photo gets a fresh chance.
    var prerenderOutOfMemory by remember(sourceUri) { mutableStateOf(false) }
    LaunchedEffect(
        previewTick, sourceRenderAllowed, sourceUri, exportUi.options,
        state.outputColorSpace, state.savingCctfEncoding,
        // A real export must not compete with a speculative one for the same eight cores:
        // as a key, this cancels any pre-render in flight the moment an export starts.
        exportInFlight,
    ) {
        val activeEngine = engine ?: return@LaunchedEffect
        val activeUri = sourceUri ?: return@LaunchedEffect
        if (!sourceRenderAllowed || exportVm.current.inFlight) return@LaunchedEffect
        if (prerenderOutOfMemory) return@LaunchedEffect
        delay(RenderPayloads.IDLE_DEBOUNCE_MS)
        if (previewBusy || exportVm.current.inFlight) return@LaunchedEffect
        val app = ctx.applicationContext
        val descriptor = runCatching {
            exportVm.current.options.outputDescriptor(
                state.outputColorSpace, state.savingCctfEncoding, Build.VERSION.SDK_INT,
            )
        }.getOrNull() ?: return@LaunchedEffect
        // Scene-linear exports the DECODE, not the engine output: nothing to pre-render.
        if (descriptor.format == ExportFormat.SCENE_LINEAR_TIFF) return@LaunchedEffect
        val params = if (descriptor.engineColorSpace == null) {
            state.toParams()
        } else {
            descriptor.applyTo(state.toParams())
        }
        val request = runCatching { currentSourceDecodeRequest() }.getOrNull()
            ?: return@LaunchedEffect
        // The source cannot change under a stable Uri, so hash it once per source, not once
        // per idle: it is a full read of the file (~37 MB on the bench corpus).
        val digest = prerenderDigest ?: withContext(dispatchers.io) {
            ExportCacheKey.digestSource { app.contentResolver.openInputStream(activeUri) }
        }?.also { prerenderDigest = it } ?: return@LaunchedEffect
        val store = RenderPayloads.of(app)
        val key = RenderPayloads.key(
            ExportCacheKey.Source(
                digest.first, digest.second, EXPORT_MAX_EDGE_PX, decodeIdentityOf(request),
            ),
            params,
            descriptor.engineColorSpace,
            ExportCaches.contractVersionOf(app),
        )
        if (store.holds(key) || !RenderPayloads.shouldPrerender(app)) return@LaunchedEffect
        val startedMs = System.currentTimeMillis()
        runCatching {
            withContext(dispatchers.default) {
                val image = decodeSourceRequest(request, EXPORT_MAX_EDGE_PX, dispatchers.io).image
                val rendered = try {
                    runCancellableNative(
                        onLateResult = { late ->
                            late.reportOutcome(AppRenderOutcome.CANCELLED)
                            late.close()
                        },
                    ) { cancellation ->
                        activeEngine.simulate(image, params, RenderKind.EXPORT, cancellation)
                    }
                } finally {
                    image.close()
                    // Speculative full-res render: do not leave its GPU buffers
                    // holding budget the real export will need (#269).
                    releaseGpuAfterFullResRender("pre-render")
                }
                rendered.use {
                    val stored = store.put(key, it)
                    // A stored payload WAS consumed — its bytes outlive the result. One that
                    // could not be stored is a render nothing ever used.
                    it.reportOutcome(
                        if (stored) AppRenderOutcome.CONSUMED else AppRenderOutcome.FAILED,
                    )
                    stored
                }
            }
        }.onSuccess { stored ->
            if (stored) {
                Diag.i("pre-rendered export in ${System.currentTimeMillis() - startedMs} ms")
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            Diag.w("pre-render skipped: $failure")
            // This is SPECULATIVE work for an export the user has not asked for, and it
            // decodes at EXPORT_MAX_EDGE_PX. On the 4080x3060 DNG that exposed this it cost
            // a full decode plus most of an export — 13 s and ~650 MB — before failing, while
            // the preview the user was actually waiting for got nothing. The budget will not
            // have grown by the next previewTick, so retrying only repeats the damage.
            // shouldPrerender's own guards do not catch this: they read the SYSTEM low-memory
            // flag, not this process's own budget, which is what refused the allocation.
            if (generateSequence(failure) { it.cause }.take(8).any { it is OutOfMemoryError }) {
                prerenderOutOfMemory = true
                Diag.w("pre-render stood down for this source after OOM")
            }
        }
    }

    // --- Non-destructive recipe: debounced auto-save ---
    // Grade fields included for the same reason as the render trigger above:
    // Presets.toJsonString already SERIALIZES them, so the recipe content was
    // right — but a grade-only edit never fired this effect, so it was not
    // persisted until some other change happened to save it along the way.
    LaunchedEffect(snapshot, recipeKey, recipeReady, defaultsJson, rotation,
        recipeAccess,
        state.rawWhiteBalance, state.rawTemperature, state.rawTint,
        state.creativeWbTemp, state.creativeWbTint, state.balanceToFilmStock,
        state.localAdjustments,
        state.saturation, state.vibrance, state.gamutCompress) {
        if (!recipeReady || recipeKey == null) return@LaunchedEffect
        val writableGeneration = recipeAccess.writableGenerationFor(recipeKey)
            ?: return@LaunchedEffect
        val autosaveEpoch = recipeEditEpoch.snapshot()
        delay(700)
        if (!recipeEditEpoch.isCurrent(autosaveEpoch)) return@LaunchedEffect
        if (recipeAccess.writableGenerationFor(recipeKey) != writableGeneration) {
            return@LaunchedEffect
        }
        val current = runCatching { Presets.toJsonString(state) }.getOrNull()
        // A non-NONE manual rotation is itself an edit worth persisting, even when the
        // params are otherwise default — so only treat as "pristine" if rotation is NONE.
        if (current != null && current == defaultsJson && rotation == SourceRotation.NONE) {
            if (!hasRecipe) return@LaunchedEffect
            val pendingDelete = RecipeWorkRuntime.submit {
                if (recipeRepo.generation(recipeKey) != writableGeneration) return@submit null
                recipeRepo.delete(recipeKey)
                recipeRepo.generation(recipeKey)
            }
            val newGeneration = try {
                pendingDelete.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Diag.w("pristine recipe delete failed: ${failure.message}")
                status = ctx.getString(R.string.editor_status_recipe_clear_failed)
                return@LaunchedEffect
            }
            if (newGeneration != null) {
                recipeAccess = EditorRecipeAccess.Writable(recipeKey, newGeneration)
                hasRecipe = false
            }
            return@LaunchedEffect
        }
        val saved = try {
            // Reuse the main-thread serialization from above (torn-snapshot safety);
            // only the envelope build + file write cross to IO.
            val paramsJson = current ?: Presets.toJsonString(state)
            val savedSourceName = sourceName
            val savedRotationDegrees = rotation.degrees
            val pendingSave = RecipeWorkRuntime.submit {
                recipeRepo.saveJson(
                    recipeKey,
                    paramsJson,
                    savedSourceName,
                    savedRotationDegrees,
                    writableGeneration,
                )
            }
            pendingSave.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Diag.w("recipe auto-save failed: ${failure.message}")
            return@LaunchedEffect
        }
        if (saved) hasRecipe = true
    }

    // --- Undo/redo capture (debounced coalescing) ---
    // Keyed on the same settle inputs as the auto-save effect, so it re-arms on every
    // edit and only the LAST change in a burst survives the delay (a slider drag fires
    // many state changes but they all collapse into ONE surviving settle). After the
    // state settles we compare the new snapshot to the last COMMITTED one:
    //   • restoring == true  -> this settle is the programmatic undo/redo restore;
    //     don't record it, just clear the flag (applySnapshot already moved the
    //     committed pointer). This is what breaks the feedback loop.
    //   • committedSnapshot == null -> first settle for this source/baseline; adopt it
    //     as the baseline WITHOUT pushing (canUndo stays false until a real edit).
    //   • differs from committed -> the user made an edit: push the PREVIOUS committed
    //     snapshot onto the undo stack and advance the committed pointer. One settled
    //     drag => exactly one undo step that returns to the pre-drag state.
    // Uses a slightly shorter delay than auto-save so a quick undo right after an edit
    // still finds the entry recorded; both are debounced independently and key on the
    // same inputs, so this adds no extra previewTick churn or re-decodes.
    // Grade fields added here too. snapshotNow() already captures them (it goes
    // through Presets.toJsonString), so the CONTENT was complete and only the
    // TRIGGER was missing — which meant a grade-only edit pushed no undo step,
    // and a later unrelated edit then collapsed both into a single step that
    // undid more than the user did.
    LaunchedEffect(snapshot, recipeKey, recipeReady, rotation,
        state.rawWhiteBalance, state.rawTemperature, state.rawTint,
        state.creativeWbTemp, state.creativeWbTint, state.balanceToFilmStock,
        state.localAdjustments,
        state.saturation, state.vibrance, state.gamutCompress) {
        if (!recipeReady) return@LaunchedEffect
        delay(500)
        // settleDecision (unit-tested in EditHistoryTest) handles the subtle case where a
        // real edit lands within the restore settle window: it pushes the restored baseline
        // so the edit stays undoable instead of being silently adopted (one-undo-step loss).
        val now = snapshotNow()
        val action = settleDecision(restoring, committedSnapshot, now)
        action.push?.let {
            editHistory.push(it)
            liveCursorMutated = true
        }
        committedSnapshot = action.committed
        restoring = false
        checkpointEditorSession()
    }

    // UI-only editor cursor state does not participate in the render snapshot. Persist it on a
    // short debounce so category/tool/mask, copy/paste and export-sheet choices survive a
    // navigation round-trip without enqueueing one document per keystroke.
    LaunchedEffect(
        recipeReady,
        sourceUri,
        sourceKind,
        sourceName,
        sourceAuthorizationRequired,
        activeCategory,
        toolUi,
        presetUi,
        exportUi,
    ) {
        if (!recipeReady) return@LaunchedEffect
        delay(250)
        checkpointEditorSession()
    }

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
    LaunchedEffect(exportPending, previewBusy) {
        if (exportPending && !previewBusy) {
            exportPending = false
            openExportSheetIfAllowed()
        }
    }
    LaunchedEffect(Unit) {
        if (!Ticket139EditorProbe.isArmed()) return@LaunchedEffect
        Ticket139EditorProbe.exportProbeRequests.collect { request ->
            openExportSheetIfAllowed()
            Ticket139EditorProbe.publishExportProbeResult(
                request.sequence,
                exportVm.current.sheetOpen,
            )
        }
    }

    // --- hardware keyboard shortcuts (EditorShortcuts.kt) ---
    // Every shortcut calls the SAME action as its on-screen control. Esc goes through the
    // back dispatcher, so it closes exactly what Back would (and in the same order), but
    // only when something is open: at the root it must not arm double-back-to-exit.
    val shortcutHandler by rememberUpdatedState<(EditorShortcut) -> Boolean> { shortcut ->
        // The magnifier is a full-screen overlay in this window (not a dialog), so it
        // has to block shortcuts too; an undo behind it would invalidate its crop.
        val overlayOpen = toolUi.anyOverlayOpen || exportMaskVisible || magnifierOpen
        when (shortcut) {
            EditorShortcut.CLOSE -> {
                val closable = viewerUi.chromeHidden || overlayOpen || activeCategory != null
                if (closable) onBackPressedDispatcher.onBackPressed()
                closable
            }
            EditorShortcut.UNDO -> !overlayOpen && editHistory.canUndo && run { doUndo(); true }
            EditorShortcut.REDO -> !overlayOpen && editHistory.canRedo && run { doRedo(); true }
            EditorShortcut.OPEN -> !overlayOpen && run {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                true
            }
            EditorShortcut.EXPORT -> !overlayOpen && run { requestExport(); true }
            EditorShortcut.TOGGLE_COMPARE -> !overlayOpen && run {
                viewerVm.onAction(EditorViewerAction.ToggleCompare)
                true
            }
        }
    }
    DisposableEffect(Unit) {
        editorShortcutHandler = { shortcutHandler(it) }
        onDispose { editorShortcutHandler = null }
    }

    // --- back handling on the root editor ---
    // 0) crop overlay open -> close it; 1) panel open -> close panel;
    // 2) else double-back-to-exit with one-time hint.
    // Above every other rung: with the chrome hidden there is no visible control at
    // all, so Back has to mean "give me the app back" before it means anything else.
    BackHandler(enabled = viewerUi.chromeHidden) { viewerVm.onAction(EditorViewerAction.ShowChrome) }
    BackHandler(enabled = toolUi.cropOpen) { toolVm.onAction(EditorToolAction.CloseCrop) }
    BackHandler(enabled = toolUi.maskGeometryOpen) { toolVm.onAction(EditorToolAction.CloseMaskGeometry) }
    BackHandler(enabled = toolUi.sampleOpen) { toolVm.onAction(EditorToolAction.CloseSample) }
    // Predictive back on the open panel: while the back swipe is in progress the panel
    // shrinks toward the bottom edge (M3 sheets), so the user sees what Back will do and
    // can still cancel. Committing closes it; cancelling springs it back.
    androidx.activity.compose.PredictiveBackHandler(
        enabled = !toolUi.anyOverlayOpen && !exportMaskVisible && activeCategory != null,
    ) { progress ->
        try {
            progress.collect { panelBackProgress = it.progress }
            activeCategory = null
        } catch (e: CancellationException) {
            // Gesture cancelled: leave the panel open.
        } finally {
            panelBackProgress = 0f
        }
    }
    BackHandler(enabled = !toolUi.anyOverlayOpen && !exportMaskVisible && activeCategory == null) {
        if (backArmed) {
            finish()
        } else {
            backArmed = true
            scope.launch {
                val firstTime = !BackHintStore.hasShown(ctx)
                if (firstTime) {
                    BackHintStore.markShown(ctx)
                    snackbarHost.currentSnackbarData?.dismiss()
                    snackbarHost.showSnackbar(ctx.getString(R.string.editor_snack_back_again))
                }
                delay(2000)
                backArmed = false
            }
        }
    }
    // Registered last so it wins: the full-screen grain magnifier sits over everything
    // above, and Back (or Esc) must close IT, not the panel or overlay hidden beneath.
    BackHandler(enabled = magnifierOpen) { closeMagnifier() }

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
    Box(
        Modifier
            .fillMaxSize()
            .background(SpectraIcons.nearBlackCanvas)
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
            )

            // Adaptive editor chrome. Narrow (phone portrait): the preview over a floating
            // bottom panel over the category bar, as before. Wide (tablets, landscape phones,
            // desktop windows, unfolded foldables): the active panel docks as a side column
            // beside the preview + category bar. Neither branch touches PreviewRegion or the
            // GPU/CPU render paths; only where the panel body is hosted differs.
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Window size classes (M3): side pane for expanded+ windows and landscape
                // phones, bottom sheet for compact and medium portrait. See EditorLayout.kt.
                val wide = editorPaneMode(maxWidth.value, maxHeight.value) == EditorPaneMode.SIDE_PANE
                val sidePaneWidth = sidePaneWidthDp(maxWidth.value).dp

                // The photo's EXIF capture date for the date-back imprint's default text,
                // read off the main thread once per source.
                val photoDateStamp by produceState<String?>(null, sourceUri) {
                    value = sourceUri?.let { uri ->
                        withContext(dispatchers.io) { DateStampText.readCaptureDate(ctx, uri) }
                    }
                }
                // The active panel body, shared by both branches so the per-category
                // `when` below is not duplicated.
                val panelContent: @Composable ColumnScope.() -> Unit = {
                    CompositionLocalProvider(LocalSliderInteraction provides sliderInteraction) {
                    when (activeCategory) {
                        Category.INPUT -> InputSection(state, onEditCrop = { toolVm.onAction(EditorToolAction.OpenCrop) },
                            onPickNeutral = { toolVm.onAction(EditorToolAction.PickNeutral) })
                        Category.RAW_WB -> ImportRawSection(state, isRaw = sourceKind == SourceKind.RAW,
                            onPickNeutral = { toolVm.onAction(EditorToolAction.PickNeutral) })
                        Category.SIMULATION -> SimulationSection(
                            s = state,
                            photoDateStamp = photoDateStamp,
                            filmGroups = filmGroups,
                            printGroups = printGroups,
                            onOpenFilmCurves = {
                                checkpointEditorSession()
                                onOpenFilmCurves(
                                    state.filmProfile,
                                    StockCatalog.displayName(ctx, state.filmProfile),
                                )
                            },
                            onOpenPrintCurves = {
                                checkpointEditorSession()
                                onOpenPrintCurves(
                                    state.printProfile,
                                    StockCatalog.displayName(ctx, state.printProfile),
                                )
                            },
                            onFilmProfileChange = { id ->
                                val changed = id != state.filmProfile
                                state.filmProfile = id
                                if (changed) {
                                    val name = StockCatalog.displayName(ctx, id)
                                    // A reversal (slide) film is most naturally viewed as a
                                    // positive — nudge Slide mode if the print is still showing;
                                    // otherwise offer to drop the previous stock's tweaks.
                                    if (StockCatalog.isReversalFilm(ctx, id) && !state.scanFilm) {
                                        offerSnackbarSuggestion(scope, snackbarHost,
                                            ctx.getString(R.string.editor_snack_slide_film, name),
                                            ctx.getString(R.string.editor_snack_slide_mode)) { state.scanFilm = true }
                                    } else {
                                        offerSnackbarSuggestion(scope, snackbarHost,
                                            ctx.getString(R.string.editor_snack_switched_to, name),
                                            ctx.getString(R.string.editor_snack_use_defaults)) { state.resetStockCharacter() }
                                    }
                                }
                            },
                            onPrintProfileChange = { id ->
                                val changed = id != state.printProfile
                                state.printProfile = id
                                if (changed) {
                                    offerSnackbarSuggestion(scope, snackbarHost,
                                        ctx.getString(R.string.editor_snack_switched_to, StockCatalog.displayName(ctx, id)),
                                        ctx.getString(R.string.editor_snack_use_defaults)) { state.resetStockCharacter() }
                                }
                            },
                        )
                        Category.GRAIN -> GrainSection(state)
                        Category.PREFLASH -> PreflashSection(state)
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
                        Category.PRESETS -> PresetPanel(
                            builtInGroups = builtInGroups,
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
                                            .onFailure { status = ctx.getString(R.string.editor_status_save_dialog_failed, it.message) }
                                    }.onFailure {
                                        SimResult.reportOutcome(lutRenderId, AppRenderOutcome.FAILED)
                                        status = ctx.getString(R.string.editor_status_lut_bake_failed, it.message)
                                        Toast.makeText(ctx, status, Toast.LENGTH_LONG).show()
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
                        Category.SOURCE -> SourcePanel(
                            sourceName = sourceName,
                            status = status,
                            showHistogram = viewerUi.histogram,
                            onToggleHistogram = { viewerVm.onAction(EditorViewerAction.ToggleHistogram) },
                            hasRecipe = hasRecipe,
                            needsAuthorization = sourceAuthorizationRequired,
                            onReauthorize = {
                                if (sourceKind == SourceKind.RAW) {
                                    rawPicker.launch(arrayOf("*/*"))
                                } else {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                }
                            },
                            onPickPhoto = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onOpenRaw = { rawPicker.launch(arrayOf("*/*")) },
                            onUseDemo = {
                                val selectionGeneration = sourceMutationGate.begin()
                                // Commit an explicit demo tombstone before changing either the
                                // visible source or its session checkpoint. A process kill at
                                // any later instruction restores demo, never the older URI.
                                val pendingDemo = sourceRuntime.submitReconciled(selectionGeneration) {
                                    sourceAccess.selectDemo()
                                }
                                scope.launch {
                                    val outcome = pendingDemo.await() ?: return@launch
                                    if (!sourceMutationGate.isCurrent(selectionGeneration)) {
                                        return@launch
                                    }
                                    when (outcome) {
                                        is ReconciledSourceMutation.Applied -> {
                                            if (sourceUri != null || sourceKind != SourceKind.DEMO) {
                                                retireSourceResources()
                                                resetEditorCursorForSourceChange()
                                            }
                                            sourceUri = null
                                            sourceKind = SourceKind.DEMO
                                            sourceName = ctx.getString(R.string.editor_source_demo_name)
                                            rotation = SourceRotation.NONE
                                            sourceAuthorizationRequired = false
                                            checkpointEditorSession()
                                            previewTick++
                                            status = ctx.getString(R.string.editor_status_demo_image)
                                        }
                                        is ReconciledSourceMutation.Rejected -> {
                                            Diag.w("demo selection failed: ${outcome.failure.message}")
                                            reconcileVisibleSourceAfterFailure(outcome.durableState)
                                            snackbarHost.currentSnackbarData?.dismiss()
                                            snackbarHost.showSnackbar(
                                                ctx.getString(R.string.editor_snack_demo_failed),
                                                withDismissAction = true,
                                            )
                                        }
                                    }
                                }
                            },
                            onResetEdits = {
                                // Invalidate every pre-reset debounce synchronously. A save that
                                // was already submitted is FIFO-before delete; one not submitted
                                // yet sees this epoch change and cannot queue behind delete.
                                recipeEditEpoch.invalidate()
                                // Submit before attaching a UI waiter: recreation can cancel the
                                // waiter, never the durable delete or its position before restore.
                                val pendingDelete = RecipeWorkRuntime.submit {
                                    recipeRepo.delete(recipeKey)
                                }
                                scope.launch {
                                    try {
                                        pendingDelete.await()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Throwable) {
                                        Diag.w("explicit recipe reset delete failed: ${failure.message}")
                                        status = ctx.getString(R.string.editor_status_recipe_clear_failed)
                                        snackbarHost.currentSnackbarData?.dismiss()
                                        snackbarHost.showSnackbar(
                                            ctx.getString(R.string.editor_snack_recipe_clear_failed),
                                        )
                                        return@launch
                                    }
                                    recipeAccess = recipeKey?.let {
                                        EditorRecipeAccess.Writable(
                                            it,
                                            recipeRepo.generation(it),
                                        )
                                    } ?: EditorRecipeAccess.None
                                    Recipes.resetToDefaults(state, settings, profiles)
                                    presetVm.onAction(PresetAction.ClearAmount)
                                    hasRecipe = false; rotation = SourceRotation.NONE
                                    // Reset clears history: the defaults become the new
                                    // empty-history baseline (you can't undo back across a
                                    // reset). `restoring` makes the next settle adopt it.
                                    editHistory.clear(); committedSnapshot = null; restoring = true
                                    status = ctx.getString(R.string.editor_status_edits_reset); previewTick++
                                    snackbarHost.currentSnackbarData?.dismiss()
                                    snackbarHost.showSnackbar(ctx.getString(R.string.editor_snack_edits_reset))
                                }
                            },
                        )
                        null -> {}
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
                val panelShown = !wide && activeCategory != null && !viewerUi.chromeHidden
                val panelTarget = if (panelShown) {
                    with(LocalDensity.current) { panelHeightPx.toDp() }
                } else {
                    0.dp
                }
                val panelInset by animateDpAsState(panelTarget, label = "previewPanelInset")
                val previewRegion: @Composable () -> Unit = {
                    PreviewRegion(
                        controlsBottomInset = panelInset,
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
                        clipWarnings = viewerUi.clipWarnings,
                        gpuEnabled = gpuEnabled,
                        gpuProxy = gpuProxy,
                        gpuLut = gpuLut,
                        gpuGain = gpuGain,
                        onToggleCompare = { viewerVm.onAction(EditorViewerAction.ToggleCompare) },
                        onToggleHistogram = { viewerVm.onAction(EditorViewerAction.ToggleHistogram) },
                        onToggleClipWarnings = { viewerVm.onAction(EditorViewerAction.ToggleClipWarnings) },
                        onRotate = { rotation = rotation.next() },
                        onEditCrop = { toolVm.onAction(EditorToolAction.OpenCrop) },
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
                    )
                }
                // Re-encoded only when a parameter actually changes, not per frame.
                // Hoisted out of the categoryBar lambda below because the panels need it too:
                // the per-category reset is offered only for a category that actually holds
                // edits, which is the same question the rail's dot asks.
                val dirty by remember(state) { derivedStateOf { dirtyCategories(state) } }
                val resetActiveCategory: (() -> Unit)? =
                    activeCategory?.takeIf { it in dirty }?.let { target ->
                        {
                            // A partial recipe carrying only this category's sections.
                            // Presets.applyJson reads each section behind its own
                            // optJSONObject, so nothing outside them can move.
                            categoryDefaults(target)?.let { defaults ->
                                runCatching { Presets.decode(defaults, state) }
                                previewTick++
                            }
                            Unit
                        }
                    }
                val categoryBar: @Composable () -> Unit = {
                    CategoryBar(
                        active = activeCategory,
                        dirty = dirty,
                        // The FAB is hidden, not disabled, when there is nothing to export;
                        // a render in flight only defers the tap (see openExportSheetIfAllowed).
                        exportVisible = engine != null && sourceRenderAllowed,
                        exporting = exportInFlight,
                        onExport = { requestExport() },
                        onSelect = { cat ->
                            activeCategory = if (activeCategory == cat) null else cat
                        },
                    )
                }

                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            // --- PREVIEW (pinned, weight) ---
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .heightIn(min = 220.dp),
                            ) { previewRegion() }
                            // --- CATEGORY BAR (under the preview column) ---
                            if (!viewerUi.chromeHidden) categoryBar()
                        }
                        // --- ADJUSTMENT PANEL (docked side column) ---
                        // A Row sibling, deliberately not animated: the preview keeps its
                        // height, and the panel's one-shot width change never becomes the
                        // per-frame GLSurfaceView resize the narrow branch guards against.
                        val sideCategory = activeCategory
                        if (sideCategory != null) {
                            SidePanel(
                                modifier = Modifier.predictiveBackShrink(panelBackProgress),
                                category = sideCategory,
                                onDismiss = { activeCategory = null },
                                onResetCategory = resetActiveCategory,
                                width = sidePaneWidth,
                                content = panelContent,
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        // --- PREVIEW (pinned, weight) ---
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .heightIn(min = 220.dp),
                        ) {
                            previewRegion()

                            // --- ADJUSTMENT PANEL (floating bottom overlay) ---
                            // Floated INSIDE the preview Box (aligned to its bottom) so
                            // opening/closing a panel no longer resizes the weight(1f) preview
                            // every frame. That per-frame resize churned the GLSurfaceView (it
                            // forced GPU preview off) and jolted the CPU preview too; a
                            // constant-height preview fixes both and unblocks GPU.
                            PanelOverlay(
                                visible = activeCategory != null && !viewerUi.chromeHidden,
                                dimming = interacting,
                            ) {
                                AdjustmentPanel(
                                    modifier = Modifier
                                        // M3 bottom sheet max width; centred in wider windows.
                                        .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                                        .onSizeChanged { panelHeightPx = it.height }
                                        .predictiveBackShrink(panelBackProgress),
                                    category = activeCategory,
                                    onDismiss = { activeCategory = null },
                                    onResetCategory = resetActiveCategory,
                                    tall = panelTall,
                                    onTallChange = { panelTall = it },
                                    content = panelContent,
                                )
                            }

                            // --- FLOATING VALUE READOUT ---
                            // Drawn after the panel so it sits above the faded one.
                            //
                            // Top-END, not top-centre. The top of the preview is already
                            // spoken for: StatusPill is TopStart but runs wide enough to
                            // reach the middle ("Rendered in 162 ms" did, on device), and
                            // PreviewHistogramOverlay is TopCenter outright. The right
                            // corner is the only part of that band nothing else claims.
                            //
                            // The wrapper is fillMaxWidth so the pill's text changing width
                            // as digits come and go cannot propagate a remeasure outwards to
                            // the preview it is sitting on.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter),
                                contentAlignment = Alignment.TopEnd,
                            ) { SliderReadoutPill(sliderReadout) }
                        }

                        // --- BOTTOM CATEGORY BAR ---
                        if (!viewerUi.chromeHidden) categoryBar()
                    }
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

        // --- interactive crop overlay (Lightroom-style) ---
        // Only shown once a preview bitmap exists (the crop tool needs the image
        // to draw and to read its aspect ratio).
        if (cropShown && cropBmp != null) {
            CropOverlay(
                bitmap = cropBmp,
                initialCrop = state.crop,
                initialCenter = state.cropCenter,
                initialSize = state.cropSize,
                onRotate = { rotation = rotation.next() },
                onConfirm = { crop, center, size ->
                    state.crop = crop
                    state.cropCenter = center
                    state.cropSize = size
                    toolVm.onAction(EditorToolAction.CloseCrop)
                    previewTick++
                },
                onCancel = { toolVm.onAction(EditorToolAction.CloseCrop) },
            )
        }

        // --- draw-on-the-preview mask geometry editor ---
        if (maskGeometryShown && cropBmp != null) {
            MaskGeometryOverlay(
                bitmap = cropBmp,
                mask = state.localAdjustments[toolUi.maskIndex].mask,
                onConfirm = { updated ->
                    val maskIndex = toolVm.current.maskIndex
                    val list = state.localAdjustments.toMutableList()
                    list[maskIndex] = list[maskIndex].copy(mask = updated)
                    state.localAdjustments = list
                    toolVm.onAction(EditorToolAction.CloseMaskGeometry)
                    previewTick++
                },
                onCancel = { toolVm.onAction(EditorToolAction.CloseMaskGeometry) },
            )
        }

        // --- eyedropper: WB gray-point, or a color-/luminance-range mask target ---
        if (sampleReady) {
            PixelSampleOverlay(
                bitmap = requireNotNull(cropBmp),
                title = when {
                    toolUi.sampleWb -> stringResource(R.string.editor_sample_title_wb)
                    toolUi.sampleLuminance -> stringResource(R.string.editor_sample_title_tone)
                    else -> stringResource(R.string.editor_sample_title_color)
                },
                hint = when {
                    toolUi.sampleWb -> stringResource(R.string.editor_sample_hint_wb)
                    toolUi.sampleLuminance -> stringResource(R.string.editor_sample_hint_tone)
                    else -> stringResource(R.string.editor_sample_hint_color)
                },
                onPick = { r, g, b, nx, ny ->
                    val tool = toolVm.current
                    if (tool.sampleWb) {
                        toolVm.onAction(EditorToolAction.CloseSample)
                        // Sample the engine INPUT (linear ProPhoto — creative WB's space) at the tap
                        // and solve the WB that neutralizes it. Off the main thread (decode + crop).
                        scope.launch {
                            runCatching {
                                val avg = loadSourceCachedForZoom(MAX_EDGE_PX).use { fullLease ->
                                    withContext(dispatchers.default) {
                                        val crop = cropLinearImageRect(fullLease.image, nx, ny, 5, 5)
                                        try { avgRgb(crop) } finally { crop.close() }
                                    }
                                }
                                CreativeWhiteBalance.solveNeutral(
                                    avg.first, avg.second, avg.third,
                                    state.creativeWbTemp, state.creativeWbTint,
                                )
                            }.onSuccess { (t, ti) ->
                                state.creativeWbTemp = t; state.creativeWbTint = ti
                                status = ctx.getString(R.string.editor_status_wb_set)
                            }.onFailure {
                                Toast.makeText(
                                    ctx,
                                    ctx.getString(R.string.editor_toast_wb_sample_failed, it.message),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    } else {
                        val list = state.localAdjustments.toMutableList()
                        val m = list[tool.maskIndex].mask
                        val nm = if (tool.sampleLuminance) {
                            m.copy(luminanceRange = com.spectrafilm.app.masks.LuminanceRange.fromSample(r, g, b))
                        } else {
                            val cr = m.colorRange ?: com.spectrafilm.app.masks.ColorRange()
                            m.copy(colorRange = cr.copy(targetR = r, targetG = g, targetB = b))
                        }
                        list[tool.maskIndex] = list[tool.maskIndex].copy(mask = nm)
                        state.localAdjustments = list
                        toolVm.onAction(EditorToolAction.MaskSampleApplied)
                        previewTick++
                    }
                },
                onCancel = { toolVm.onAction(EditorToolAction.CloseSample) },
            )
        }

        // --- full-screen export mask ---
        if (exportMaskVisible) {
            ExportMask(
                done = exportDone,
                onDismiss = {
                    exportVm.onAction(ExportAction.DismissResult)
                    checkpointEditorSession()
                },
                onShare = exportUi.lastShare?.let { (published, mime) ->
                    {
                        // Content URI + minimum read grant; the exported row's exact
                        // OutputDescriptor MIME — never a filesystem path (#162).
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(published))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.editor_share_export_title)))
                        } catch (_: ActivityNotFoundException) {
                            status = ctx.getString(R.string.editor_status_no_share_target)
                        }
                    }
                },
                onCancel = {
                    val running = exportRuntimeState as? ExportRuntimeState.Running
                    if (running != null && ExportWorkRuntime.cancel(running.runId)) {
                        status = ctx.getString(R.string.editor_status_cancelling_export)
                    }
                },
            )
        }

        // Ask for POST_NOTIFICATIONS when the export SHEET opens — NOT when the
        // export starts. A system permission dialog takes focus, and on the
        // measured device losing /top-app costs 3.90x (§15.1): asking at the
        // moment the render begins would contaminate the very first export with
        // the exact effect a whole session was spent characterising. Here the
        // dialog resolves while the user is still choosing options and nothing
        // is rendering, and it is still in context — they are about to export.
        LaunchedEffect(exportUi.sheetOpen) {
            val showExportSheet = exportUi.sheetOpen
            if (showExportSheet && notificationPermissionMissing()) {
                runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            }
            if (showExportSheet &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    legacyStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        // ACCESS_MEDIA_LOCATION is asked for at the same moment and for the same reason,
        // and again if "Include location (GPS)" is switched on inside the open sheet. When
        // a notification request is about to run, its result callback asks instead.
        LaunchedEffect(exportUi.sheetOpen, exportUi.keepGps) {
            if (exportVm.current.sheetOpen && !notificationPermissionMissing()) {
                requestMediaLocationIfMissing()
            }
        }

        // --- Lightroom-style export options sheet ---
        if (exportUi.sheetOpen) {
            ExportSheet(
                options = exportUi.options,
                onOptionsChange = { exportVm.onAction(ExportAction.SetOptions(it)) },
                colorSpace = state.outputColorSpace,
                onColorSpaceChange = { state.outputColorSpace = it },
                cctf = state.savingCctfEncoding,
                onCctfChange = { state.savingCctfEncoding = it },
                keepGps = exportUi.keepGps,
                onKeepGpsChange = { exportVm.onAction(ExportAction.SetKeepGps(it)) },
                onDismiss = { exportVm.onAction(ExportAction.CloseSheet) },
                previewBitmap = preview,
                onExport = export@{
                    val exportOptions = exportVm.current.options
                    val exportKeepGps = exportVm.current.keepGps
                    if (!sourceRenderAllowed) {
                        exportVm.onAction(ExportAction.CloseSheet)
                        status = ctx.getString(R.string.editor_status_source_access_required)
                        return@export
                    }
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(
                            ctx,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        runCatching {
                            legacyStoragePermission.launch(
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            )
                        }
                        status = ctx.getString(R.string.editor_status_grant_storage)
                        return@export
                    }
                    if (mediaLocationPermissionMissing()) {
                        // Not a block: the file can still be written, it just cannot
                        // carry GPS. Say so once and proceed; readSourceExif falls back
                        // to the redacted stream. No permission dialog here either — a
                        // system dialog over the render is the focus-loss cost the
                        // notification comment above measured (§15.1).
                        scope.launch {
                            snackbarHost.currentSnackbarData?.dismiss()
                            snackbarHost.showSnackbar(
                                ctx.getString(R.string.editor_snack_export_without_gps),
                            )
                        }
                    }
                    val e = engine
                    if (e != null) {
                        val outputDescriptor = runCatching {
                            exportOptions.outputDescriptor(
                                state.outputColorSpace,
                                state.savingCctfEncoding,
                                Build.VERSION.SDK_INT,
                            ).also { ColorManagement.requireIccBytes(ctx, it) }
                        }.getOrElse { failure ->
                            status = failure.message ?: ctx.getString(R.string.editor_status_unsupported_export)
                            Toast.makeText(ctx, status, Toast.LENGTH_LONG).show()
                            return@export
                        }
                        exportVm.onAction(ExportAction.CloseSheet)
                        // Remember the choices as the new Settings defaults, through the
                        // settings ViewModel so an open Settings screen shows them at once.
                        onSettingsAction(SettingsAction.SetExportFormat(exportOptions.format))
                        onSettingsAction(SettingsAction.SetExportQuality(exportOptions.jpegQuality))
                        onSettingsAction(SettingsAction.SetKeepGps(exportKeepGps))
                        onSettingsAction(SettingsAction.SetExportBorder(exportOptions.border))
                        val fmt = outputDescriptor.format
                        val exportFmt = fmt
                        val baseName = exportBaseName(exportOptions.customName, System.currentTimeMillis())
                        val longEdge = exportOptions.targetLongEdge()
                        val exportBorder = exportOptions.effectiveBorder()
                        val keepGps = exportKeepGps
                        // Wall-clock breadcrumbs for the on-device baseline capture
                        // (issue #119): logcat-visible start marker + duration on ok/fail.
                        val exportStartMs = System.currentTimeMillis()
                        Diag.i("export start format=${exportFmt.name} contract=${outputDescriptor.existingExportClass.id}")
                        status = ctx.getString(R.string.editor_status_rendering_full)
                        val exportContext = ctx.applicationContext
                        val exportParams = if (outputDescriptor.engineColorSpace == null) {
                            state.toParams()
                        } else {
                            outputDescriptor.applyTo(state.toParams())
                        }
                        val exportCctf = outputDescriptor.engineCctfEncoding ?: false
                        val exportSaturation = state.saturation
                        val exportVibrance = state.vibrance
                        val exportGamutCompress = state.gamutCompress
                        val exportAdjustments = state.localAdjustments.toList()
                        val exportJpegQuality = exportOptions.jpegQuality
                        val exportSourceUri = sourceUri
                        val exportDecodeRequest = currentSourceDecodeRequest()
                        // #179 cache identity. The decode identity is spelled out rather
                        // than taken from exportDecodeRequest.toString(), which embeds a
                        // Context whose toString differs between two identical exports.
                        val exportDecodeIdentity = decodeIdentityOf(exportDecodeRequest)
                        val exportGradeKey = ExportCacheKey.Grade(
                            exportCctf, exportSaturation, exportVibrance,
                            exportGamutCompress, exportAdjustments,
                        )
                        val launched = ExportWorkRuntime.launch(
                            context = exportContext,
                            format = exportFmt,
                            startedAtMillis = exportStartMs,
                            sourceIdentity = exportSourceIdentity,
                        ) {
                            var phSetup = 0L
                            var phDecode = 0L
                            var phExif = 0L
                            var phSim = 0L
                            var phGrade = 0L
                            var phEnc = 0L
                            var exportCacheKey: String? = null
                            var exportPrerenderKey: String? = null
                            var servedFromCache = false
                            var renderId = 0L
                            val destinationCommit = ExportCommitLinearization()
                            var previewCandidate: Bitmap? = null
                            // #162: the committed MediaStore row, threaded into the terminal
                            // outcome so the result UI can open/share it by content URI.
                            var publishedUri: Uri? = null
                            try {
                                val bitmap =
                                withContext(dispatchers.default) {
                                    // Full-res off-heap buffers (the OOM fix) — close input/result promptly.
                                    // Phase breadcrumbs: the engine's own `stage timings` line
                                    // accounts for only ~61% of an export (8819 ms of 14476 on a
                                    // 12.5 MP Ultra HDR run). The other ~39% is decode, the
                                    // full-res bitmap grade and the encode — none of which any
                                    // measurement has ever separated, which makes it impossible
                                    // to say what a GPU pipeline could and could not reach.
                                    // setup = the permission check, the startForegroundService
                                    // IPC round trip and the coroutine dispatch hops.
                                    val tDecode0 = System.currentTimeMillis()
                                    phSetup = tDecode0 - exportStartMs

                                    // #179: a complete-key hit publishes the exact bytes a
                                    // previous export produced, skipping decode, engine,
                                    // grade and encode. Scene-linear TIFF bypasses the engine
                                    // on a separate path and is not cached.
                                    val exportSource =
                                        if (exportFmt == ExportFormat.SCENE_LINEAR_TIFF) {
                                            null
                                        } else {
                                            ExportCacheKey.digestSource {
                                                exportSourceUri?.let {
                                                    exportContext.contentResolver.openInputStream(it)
                                                }
                                            }?.let { (sha, bytes) ->
                                                ExportCacheKey.Source(
                                                    sha, bytes, EXPORT_MAX_EDGE_PX,
                                                    exportDecodeIdentity,
                                                )
                                            }
                                        }
                                    val exportContract = ExportCaches.contractVersionOf(exportContext)
                                    exportCacheKey = exportSource?.let {
                                        ExportCacheKey.compute(
                                            it,
                                            exportParams,
                                            exportGradeKey,
                                            outputDescriptor,
                                            longEdge,
                                            exportJpegQuality,
                                            keepGps,
                                            exportBorder,
                                            exportContract,
                                        )
                                    }
                                    // Pre-encode identity: the same payload serves every
                                    // container, quality and resize of this edit, so it is keyed
                                    // on the engine inputs alone (#179).
                                    exportPrerenderKey = exportSource?.let {
                                        RenderPayloads.key(
                                            it,
                                            exportParams,
                                            outputDescriptor.engineColorSpace,
                                            exportContract,
                                        )
                                    }
                                    val cached = exportCacheKey?.let {
                                        ExportCaches.of(exportContext).get(it)
                                    }
                                    if (cached != null) {
                                        publishedUri = withContext(dispatchers.io) {
                                            publishCachedExport(
                                                exportContext,
                                                cached,
                                                "$baseName.${exportFmt.ext}",
                                                outputDescriptor.format.mime,
                                                onCommitted = destinationCommit::markPublished,
                                            )
                                        }
                                        servedFromCache = true
                                        phEnc = System.currentTimeMillis() - tDecode0
                                        Diag.i("export served from cache in ${phEnc} ms")
                                        return@withContext null
                                    }

                                    // #179: the idle pre-render stores the ENGINE output, so a
                                    // hit skips decode and simulate (~6 s on BASE, ~14.5 s on
                                    // HEAVY) while the export still runs the real encoder — the
                                    // published bytes come from the same code either way.
                                    val restored = exportPrerenderKey?.let {
                                        RenderPayloads.of(exportContext).get(it)
                                    }
                                    val image = if (restored != null) null else {
                                        decodeSourceRequest(
                                            exportDecodeRequest,
                                            EXPORT_MAX_EDGE_PX,
                                            dispatchers.io,
                                        ).image
                                    }
                                    phDecode = System.currentTimeMillis() - tDecode0
                                    if (exportFmt == ExportFormat.SCENE_LINEAR_TIFF) {
                                        // Export the decoded scene-linear INPUT (before the film
                                        // engine) as a 32-bit float TIFF; the engine is skipped.
                                        val tEnc0 = System.currentTimeMillis()
                                        try {
                                            publishedUri = withContext(dispatchers.io) {
                                                saveLinearInputAsTiff32f(
                                                    exportContext,
                                                    // Scene-linear exports the DECODE, never the
                                                    // engine output, so they are never pre-rendered
                                                    // and the image is always present here.
                                                    requireNotNull(image) { "scene-linear needs a decode" },
                                                    outputDescriptor,
                                                    baseName,
                                                    onCommitted = destinationCommit::markPublished,
                                                )
                                            }
                                        } finally {
                                            image?.close()
                                        }
                                        phEnc = System.currentTimeMillis() - tEnc0
                                        null  // no rendered bitmap to preview
                                    } else {
                                        // Copy source EXIF; GPS only when opted in.
                                        // A full EXIF read of a ~25 MB RAW plus two thread-pool
                                        // hops — its own phase, not silently between two others.
                                        val tExif0 = System.currentTimeMillis()
                                        val srcExif = withContext(dispatchers.io) {
                                            readSourceExif(exportContext, exportSourceUri, keepGps = keepGps)
                                        }
                                        phExif = System.currentTimeMillis() - tExif0
                                        // `simulate` is the engine plus JNI marshalling and the
                                        // ~140 MB result allocation/wrap. #163's outer native
                                        // timing now covers that whole boundary; this Kotlin phase
                                        // additionally includes the input close below and dispatch
                                        // bookkeeping, so a small positive gap remains expected.
                                        val tSim0 = System.currentTimeMillis()
                                        val res = restored ?: try {
                                            runCancellableNative(
                                                onLateResult = { late ->
                                                    late.reportOutcome(AppRenderOutcome.CANCELLED)
                                                    late.close()
                                                },
                                            ) { cancellation ->
                                                e.simulate(
                                                    requireNotNull(image) { "render needs a decode" },
                                                    exportParams,
                                                    RenderKind.EXPORT,
                                                    cancellation,
                                                )
                                            }.also { renderId = it.renderId }
                                        } finally {
                                            image?.close()
                                        }
                                        phSim = System.currentTimeMillis() - tSim0
                                        if (restored != null) {
                                            Diag.i("export used pre-rendered payload in $phSim ms")
                                        }
                                        try {
                                            check(res.colorSpace == outputDescriptor.engineColorSpace) {
                                                "Engine result color space disagrees with output contract"
                                            }
                                            val tGrade0 = System.currentTimeMillis()
                                            val bitmapEncoder = when (outputDescriptor.encoder) {
                                                OutputEncoder.ANDROID_BITMAP_PNG,
                                                OutputEncoder.ANDROID_BITMAP_JPEG,
                                                -> true
                                                OutputEncoder.NATIVE_TIFF_UINT16,
                                                OutputEncoder.NATIVE_TIFF_FLOAT32,
                                                OutputEncoder.NATIVE_PNG16,
                                                -> false
                                                else -> error("Scene-linear encoder reached rendered branch")
                                            }
                                            val bmp = if (bitmapEncoder) {
                                                val full = simResultToBitmapGraded(
                                                    res,
                                                    exportCctf,
                                                    exportSaturation,
                                                    exportVibrance,
                                                    exportGamutCompress,
                                                    exportAdjustments,
                                                )
                                                // Take ownership immediately. If downscaling or any
                                                // pre-commit step throws, the outer failure path recycles it.
                                                previewCandidate = full
                                                // Post-render downscale for Bitmap encoders only. Native
                                                // high-bit writers consume the float buffer without ever
                                                // materialising a full-resolution ARGB_8888 Bitmap.
                                                // With a border, the requested long edge is the
                                                // FINISHED image's, so a 2048 px export is still
                                                // 2048 px wide: shrink the photo to leave room.
                                                val photoEdge = longEdge?.let { edge ->
                                                    FilmBorderGeometry.photoLongEdgeFor(
                                                        exportBorder, full.width, full.height, edge,
                                                    )
                                                }
                                                val sized = photoEdge?.let { edge ->
                                                    scaleBitmapToLongEdge(full, edge).also { scaled ->
                                                        if (scaled !== full) {
                                                            previewCandidate = scaled
                                                            runCatching { full.recycle() }
                                                        }
                                                    }
                                                } ?: full
                                                // Film border around the photo (output grows).
                                                // Frame number is stable per source, so the
                                                // cache key's source hash already covers it.
                                                val borderLayout = FilmBorderGeometry.layout(
                                                    exportBorder,
                                                    sized.width,
                                                    sized.height,
                                                    FilmBorderGeometry.frameNumberFor(
                                                        exportSource?.contentSha256,
                                                    ),
                                                )
                                                val framed = borderLayout?.let { layout ->
                                                    FilmBorderRenderer.render(sized, layout).also {
                                                        previewCandidate = it
                                                        runCatching { sized.recycle() }
                                                    }
                                                } ?: sized
                                                // #140: derive the Ultra HDR gain map from the
                                                // render itself, here, while the engine's float
                                                // buffer is still alive — it holds the values
                                                // above SDR white that the 8-bit bitmap clamped
                                                // away, and they are the only honest HDR signal
                                                // this pipeline has.
                                                if (outputDescriptor.metadata.hdrGainMap != null) {
                                                    runCatching {
                                                        attachRenderedGainmap(
                                                            res, framed, outputDescriptor, borderLayout,
                                                        )
                                                    }.onFailure {
                                                        Diag.w("gain map failed; exporting SDR base: $it")
                                                    }
                                                }
                                                framed
                                            } else {
                                                // Preserve WYSIWYG grading in the writer's float source while
                                                // avoiding the additional ~4 bytes/pixel full-res Bitmap.
                                                res.acquireDataLease().use { lease ->
                                                    ColorGrade.applyInPlace(
                                                        lease.data, res.width, res.height, res.colorSpace,
                                                        exportCctf, exportSaturation, exportVibrance,
                                                        exportGamutCompress,
                                                    )
                                                    MaskCompositor.applyInPlace(
                                                        lease.data, res.width, res.height, res.colorSpace,
                                                        exportCctf, exportAdjustments,
                                                    )
                                                }
                                                null
                                            }
                                            previewCandidate = bmp
                                            phGrade = System.currentTimeMillis() - tGrade0
                                            val tEnc0 = System.currentTimeMillis()
                                            publishedUri = withContext(dispatchers.io) {
                                                when (outputDescriptor.encoder) {
                                                    OutputEncoder.NATIVE_TIFF_UINT16,
                                                    OutputEncoder.NATIVE_TIFF_FLOAT32 -> saveSimResultAsTiff(
                                                        exportContext,
                                                        res,
                                                        outputDescriptor,
                                                        displayName = baseName,
                                                        onCommitted = destinationCommit::markPublished,
                                                        onStaged = { file, len, sha ->
                                                            exportCacheKey?.let { k ->
                                                                ExportCaches.of(exportContext)
                                                                    .adopt(k, file, len, sha)
                                                            }
                                                        },
                                                    )
                                                    OutputEncoder.NATIVE_PNG16 -> saveSimResultAsPng16(
                                                        exportContext,
                                                        res,
                                                        outputDescriptor,
                                                        displayName = baseName,
                                                        onCommitted = destinationCommit::markPublished,
                                                        onStaged = { file, len, sha ->
                                                            exportCacheKey?.let { k ->
                                                                ExportCaches.of(exportContext)
                                                                    .adopt(k, file, len, sha)
                                                            }
                                                        },
                                                    )
                                                    OutputEncoder.ANDROID_BITMAP_PNG,
                                                    OutputEncoder.ANDROID_BITMAP_JPEG -> saveToGallery(
                                                        exportContext,
                                                        requireNotNull(bmp) {
                                                            "Bitmap encoder missing graded bitmap"
                                                        },
                                                        outputDescriptor,
                                                        exportJpegQuality,
                                                        srcExif,
                                                        displayName = baseName,
                                                        onCommitted = destinationCommit::markPublished,
                                                        onStaged = { file, len, sha ->
                                                            exportCacheKey?.let { k ->
                                                                ExportCaches.of(exportContext)
                                                                    .adopt(k, file, len, sha)
                                                            }
                                                        },
                                                    )
                                                    else -> error("Scene-linear encoder reached rendered branch")
                                                }
                                            }
                                            phEnc = System.currentTimeMillis() - tEnc0
                                            // Native high-bit exports deliberately retain no preview; the
                                            // existing editor preview stays visible at bounded fit size.
                                            bmp
                                        } finally {
                                            res.close()
                                        }
                                    }
                                }
                                val retainedBitmap = runCatching {
                                    bitmap?.let { full ->
                                        scaleBitmapToLongEdge(full, 2_048).also { proxy ->
                                            if (proxy !== full) full.recycle()
                                        }
                                    }
                                }.getOrElse {
                                    runCatching { bitmap?.recycle() }
                                    null
                                }
                                // Keep cleanup authority pointed at the final proxy until the terminal
                                // outcome object is successfully constructed and owns it.
                                previewCandidate = retainedBitmap
                                // A cache hit never asked the engine to render, so there is
                                // no outcome to report and renderId is still its 0 sentinel.
                                if (!servedFromCache) {
                                    runCatching {
                                        SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
                                    }
                                }
                                val totalMs = System.currentTimeMillis() - exportStartMs
                                Diag.i(
                                    "export done in $totalMs ms " +
                                        (if (servedFromCache) "(cache hit)" else "(rendered)"),
                                )
                                val success = ExportTerminalOutcome.Success(
                                    format = exportFmt,
                                    renderId = renderId,
                                    bitmap = retainedBitmap,
                                    totalMs = totalMs,
                                    publishedUri = publishedUri?.toString(),
                                    publishedMimeType = exportFmt.mime,
                                    phases = ExportPhaseSnapshot(
                                        setupMs = phSetup,
                                        decodeMs = phDecode,
                                        exifMs = phExif,
                                        simulateMs = phSim,
                                        gradeMs = phGrade,
                                        encodeMs = phEnc,
                                    ),
                                )
                                previewCandidate = null
                                success
                            } catch (failure: Throwable) {
                                if (destinationCommit.isPublished) {
                                    // Publication is the transaction's linearization point.
                                    // Preview scaling, native telemetry, close/unwind, or outcome
                                    // construction failures after it can never turn success into a
                                    // retryable failure/duplicate export.
                                    runCatching { previewCandidate?.recycle() }
                                    runCatching {
                                        SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
                                    }
                                    ExportTerminalOutcome.Success(
                                        format = exportFmt,
                                        renderId = renderId,
                                        bitmap = null,
                                        totalMs = System.currentTimeMillis() - exportStartMs,
                                        publishedUri = publishedUri?.toString(),
                                        publishedMimeType = exportFmt.mime,
                                        phases = ExportPhaseSnapshot(
                                            setupMs = phSetup,
                                            decodeMs = phDecode,
                                            exifMs = phExif,
                                            simulateMs = phSim,
                                            gradeMs = phGrade,
                                            encodeMs = phEnc,
                                        ),
                                    )
                                 } else {
                                    runCatching { previewCandidate?.recycle() }
                                    if (failure is CancellationException) {
                                        SimResult.reportOutcome(renderId, AppRenderOutcome.CANCELLED)
                                        ExportTerminalOutcome.Cancelled(
                                            format = exportFmt,
                                            renderId = renderId,
                                            elapsedMs = System.currentTimeMillis() - exportStartMs,
                                        )
                                    } else {
                                        SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
                                        ExportTerminalOutcome.Failure(
                                            format = exportFmt,
                                            renderId = renderId,
                                            elapsedMs = System.currentTimeMillis() - exportStartMs,
                                            cause = failure,
                                        )
                                    }
                                 }
                            }
                        }
                        if (launched == null) {
                            status = ctx.getString(R.string.editor_status_export_running)
                        } else {
                            exportVm.onAction(ExportAction.Running(launched))
                            checkpointEditorSession()
                        }
                    }
                },
            )
        }

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

// ---------------------------------------------------------------------------
// New Lightroom-style chrome
// ---------------------------------------------------------------------------

@Composable
internal fun MainActivity.EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Open / pick a source photo (was an app-exit bug previously).
            TextTooltip(stringResource(R.string.editor_open_photo)) {
                PressIconButton(onClick = onOpenSource) {
                    Icon(
                        SpectraIcons.OpenPhoto, contentDescription = stringResource(R.string.editor_open_photo),
                        tint = Color.White,
                    )
                }
            }
            // weight(1f): the title yields to the 48dp actions, so at 200% font it
            // ellipsizes instead of pushing the menu off the right edge (#181).
            Text(
                stringResource(R.string.app_name),
                color = Color.White.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            // Undo / redo — in-session edit history. Disabled (dimmed) when the
            // respective stack is empty; one slider drag = one undo step.
            TextTooltip(stringResource(R.string.editor_action_undo)) {
                PressIconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        SpectraIcons.Undo, contentDescription = stringResource(R.string.editor_action_undo),
                        tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.4f),
                    )
                }
            }
            TextTooltip(stringResource(R.string.editor_action_redo)) {
                PressIconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        SpectraIcons.Redo, contentDescription = stringResource(R.string.editor_action_redo),
                        tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.4f),
                    )
                }
            }
            // M3 app bars carry one or two essential actions; Undo and Redo are this
            // screen's. Export moved to the FAB beside the stage toolbar, and Settings /
            // About live behind the overflow menu.
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                TextTooltip(stringResource(R.string.editor_action_more)) {
                    PressIconButton(onClick = { menuOpen = true }) {
                        Icon(
                            SpectraIcons.More,
                            contentDescription = stringResource(R.string.editor_action_more),
                            tint = Color.White,
                        )
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_action_settings)) },
                        leadingIcon = { Icon(SpectraIcons.Settings, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenSettings() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_action_about)) },
                        leadingIcon = { Icon(SpectraIcons.Help, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenAbout() },
                    )
                }
            }
        }
    }
}

/** The pinned preview region on the near-black canvas with a rotate control. */
@Composable
internal fun MainActivity.PreviewRegion(
    preview: Bitmap?,
    placeholder: Bitmap?,
    before: Bitmap?,
    busy: Boolean,
    decoding: Boolean,
    exporting: Boolean,
    lastRenderMs: Long?,
    renderErr: String?,
    compareMode: Boolean,
    showHistogram: Boolean,
    showClipping: Boolean,
    clipWarnings: Boolean,
    gpuEnabled: Boolean,
    gpuProxy: LinearImage?,
    gpuLut: CubeLut?,
    gpuGain: Float,
    onToggleCompare: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleClipWarnings: () -> Unit,
    onRotate: () -> Unit,
    onEditCrop: () -> Unit,
    onPointPicked: (Float, Float) -> Unit,
    onOpenMagnifier: () -> Unit,
    renderKey: Int,
    roiOverlay: RoiOverlay?,
    onRoiSettled: (RoiRect) -> Unit,
    onRoiCleared: () -> Unit,
    controlsBottomInset: Dp = 0.dp,
) {
    // Accessibility: the canvas gestures (pinch/double-tap zoom, drag to inspect a region,
    // tap to open the 100% grain magnifier) have no widget of their own, so the region is
    // described and the tap-to-pick gesture gets a custom action at the frame centre. The
    // ROI drag is bound to the live zoom state inside ZoomableImage and has no safe
    // one-shot equivalent, so it is described only. Semantics-only: no gesture changes.
    val previewDesc = when {
        preview == null -> stringResource(R.string.editor_preview_desc_empty)
        compareMode -> stringResource(R.string.editor_preview_desc_compare)
        else -> stringResource(R.string.editor_preview_desc_film)
    }
    val previewActions = if (preview != null && !compareMode) {
        listOf(
            // The magnifier moved off the tap and onto its own button, so this action
            // calls the button's handler rather than the (now unrelated) tap callback.
            CustomAccessibilityAction(stringResource(R.string.editor_preview_action_magnifier)) {
                onOpenMagnifier()
                true
            },
            // The tap gesture's own equivalent: hiding the chrome is worth reaching
            // without a gesture, since the gesture has nothing on screen to announce it.
            CustomAccessibilityAction(stringResource(R.string.editor_toggle_chrome)) {
                onPointPicked(0.5f, 0.5f)
                true
            },
        )
    } else {
        emptyList()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(SpectraIcons.nearBlackCanvas)
            // Edge to edge: no side gutter. A 16dp gutter on each side cost a landscape
            // photo (the common case on a portrait phone) 32dp of width and therefore
            // ~8% of its displayed size. The overlays below carry their own insets.
            .semantics {
                contentDescription = previewDesc
                customActions = previewActions
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = preview
        // GPU LUT surface is the FIT view's instant path (the baked look sampled on-GPU,
        // no per-edit CPU re-render); the CPU ZoomableImage owns ZOOM, where it renders the
        // region with grain/halation via the ROI path. gpuBroken latches if GL can't build
        // on this device (→ CPU fallback, never a black screen); gpuZoomHandoff flips when
        // the user pinches/double-taps the GPU surface and resets when zoom returns to fit.
        var gpuBroken by remember { mutableStateOf(false) }
        var gpuZoomHandoff by remember { mutableStateOf(false) }
        // Scale the CPU ZoomableImage starts at after a GPU-surface handoff: 1f for a pinch/
        // double-tap (continue from fit), 2f for the explicit "+" so it lands already zoomed.
        var gpuZoomInitial by remember { mutableFloatStateOf(1f) }
        val clipMask = rememberClipMask(bmp, showClipping && !compareMode)
        val gpuActive = gpuEnabled && gpuProxy != null && gpuLut != null &&
            !compareMode && !showClipping && !gpuBroken && !gpuZoomHandoff
        if (gpuActive) GpuPreviewSurface(
            proxy = gpuProxy!!,
            lut = gpuLut!!,
            exposureGain = gpuGain,
            modifier = Modifier.fillMaxSize(),
            controlsBottomInset = controlsBottomInset,
            onPointPicked = onPointPicked,
            onZoomStart = { gpuZoomHandoff = true; gpuZoomInitial = 1f },
            onZoomIn = { gpuZoomHandoff = true; gpuZoomInitial = 2f },
            onUnavailable = {
                gpuBroken = true
                Diag.w("gpu surface unavailable (GL program build failed) -> CPU preview")
            },
        )
        // #286: the render fades in over the open stand-in instead of popping in. Starts
        // transparent only while a stand-in is showing, so an ordinary re-render (no
        // stand-in) is never faded.
        val revealAlpha = remember { Animatable(1f) }
        LaunchedEffect(placeholder != null, bmp != null) {
            when {
                placeholder != null && bmp == null -> revealAlpha.snapTo(0f)
                placeholder != null -> revealAlpha.animateTo(1f, tween(OPEN_PLACEHOLDER_FADE_MS))
                else -> revealAlpha.snapTo(1f)
            }
        }
        // Everything except the GPU surface lives in the part of the canvas ABOVE the
        // floating panel ([controlsBottomInset]), so an open panel moves and fits the photo
        // instead of covering it, and the canvas buttons ride on top of the panel. The GPU
        // surface keeps the full box: resizing a GLSurfaceView every animation frame is
        // the churn documented on PanelOverlay.
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = controlsBottomInset),
            contentAlignment = Alignment.Center,
        ) {
            if (placeholder != null && !gpuActive) {
                Image(
                    bitmap = placeholder.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            when {
                gpuActive -> Unit
                bmp != null && compareMode && before != null ->
                    CompareSlider(before = before, after = bmp)
                bmp != null -> ZoomableImage(
                    bitmap = bmp,
                    // Clipping warnings: the GPU surface steps aside while they show
                    // (gpuActive above), as it does for compare, so the mask always has
                    // the CPU frame it was computed from underneath it.
                    overlay = clipMask,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = revealAlpha.value },
                    initialScale = gpuZoomInitial,
                    onPointPicked = onPointPicked,
                    // Press and hold the photo for the unedited frame. The bitmap already
                    // exists for the latched CompareSlider above, so the gesture costs no
                    // extra render.
                    //
                    // PreviewRegion.onPointPicked is NOT nullable -- it is always the
                    // magnifier opener -- so there is no sampler to yield to here, and a
                    // long press is otherwise unused on this surface. The colour/WB sampler
                    // lives behind the eyedropper (toolUi.sampleOpen), which replaces the preview outright.
                    peek = before,
                    // Lightroom-style zoom: render the visible region at native resolution
                    // (renderKey = previewTick so an edit while zoomed re-renders the crop).
                    renderKey = renderKey,
                    onRoiSettled = onRoiSettled,
                    // Returning to fit also re-arms the GPU fit surface.
                    onRoiCleared = { onRoiCleared(); gpuZoomHandoff = false },
                    roiOverlay = roiOverlay,
                    // No inset: this Box already stops at the panel's top edge.
                )
                placeholder != null -> Unit
                else -> Text(stringResource(R.string.editor_preview_none), color = Color.White.copy(alpha = 0.7f))
            }

            // Compact translucent histogram overlaid at the TOP EDGE of the preview
            // (Lightroom-style). Driven by the same `showHistogram` state as the
            // Source-panel toggle. PreviewHistogramOverlay acquires an explicit read lease and
            // samples off-main; preview retirement defers recycle until that lease closes.
            if (showHistogram && bmp != null) {
                PreviewHistogramOverlay(
                    bitmap = bmp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp),
                )
            }

            // Status pill — top-start of the preview, over the canvas.
            StatusPill(
                exporting = exporting,
                rendering = busy,
                decoding = decoding,
                lastRenderMs = lastRenderMs,
                renderErr = renderErr,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 6.dp),
            )

            // bottom-center controls on a scrim: histogram + compare + crop + rotate
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextTooltip(stringResource(R.string.editor_histogram)) {
                    CircleScrimButton(onClick = onToggleHistogram, active = showHistogram, toggle = true) {
                        Icon(
                            SpectraIcons.Histogram, contentDescription = stringResource(R.string.editor_toggle_histogram),
                            tint = Color.White,
                        )
                    }
                }
                TextTooltip(stringResource(R.string.editor_clipping_tooltip)) {
                    CircleScrimButton(onClick = onToggleClipWarnings, active = clipWarnings, toggle = true) {
                        Icon(
                            SpectraIcons.Clipping, contentDescription = stringResource(R.string.editor_toggle_clipping),
                            tint = Color.White,
                        )
                    }
                }
                TextTooltip(stringResource(R.string.editor_magnifier_tooltip)) {
                    CircleScrimButton(onClick = onOpenMagnifier) {
                        Icon(
                            SpectraIcons.Loupe, contentDescription = stringResource(R.string.editor_magnifier),
                            tint = Color.White,
                        )
                    }
                }
                TextTooltip(stringResource(R.string.editor_compare)) {
                    CircleScrimButton(onClick = onToggleCompare, active = compareMode, toggle = true) {
                        Icon(
                            SpectraIcons.Display, contentDescription = stringResource(R.string.editor_compare),
                            tint = Color.White,
                        )
                    }
                }
                TextTooltip(stringResource(R.string.editor_crop_transform_tooltip)) {
                    CircleScrimButton(onClick = onEditCrop) {
                        Icon(SpectraIcons.Crop, contentDescription = stringResource(R.string.editor_crop_transform), tint = Color.White)
                    }
                }
                TextTooltip(stringResource(R.string.editor_rotate_90)) {
                    CircleScrimButton(onClick = onRotate) {
                        Icon(SpectraIcons.Rotate, contentDescription = stringResource(R.string.editor_rotate_90), tint = Color.White)
                    }
                }
            }
        }
    }
}

private fun avgRgb(img: com.spectrafilm.engine.LinearImage): Triple<Float, Float, Float> {
    return img.acquireDataLease().use { lease ->
        val data = lease.data
        val f = data.asFloatBuffer()
        val n = (img.width * img.height).coerceAtLeast(1)
        var r = 0f; var g = 0f; var b = 0f
        for (i in 0 until n) {
            val k = i * 3
            r += f.get(k); g += f.get(k + 1); b += f.get(k + 2)
        }
        val inv = 1f / n
        Triple(r * inv, g * inv, b * inv)
    }
}
