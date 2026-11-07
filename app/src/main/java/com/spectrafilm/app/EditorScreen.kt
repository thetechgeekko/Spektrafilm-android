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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.animation.core.animateDpAsState

internal const val OPEN_PLACEHOLDER_FADE_MS = 220
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

    val engineState = remember { mutableStateOf<SpektraEngine?>(null) }
    var engine by engineState
    val profilesState = remember { mutableStateOf<List<String>>(emptyList()) }
    var profiles by profilesState
    // Owned by EditorViewModel; fresh per composition (see EditorViewModel for why).
    val state = remember { editorVm.beginSession() }

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
    val sourceUriState = remember { mutableStateOf(restoredSource.uri?.let(Uri::parse)) }
    var sourceUri by sourceUriState
    val sourceKindState = remember { mutableStateOf(restoredSource.kind) }
    var sourceKind by sourceKindState
    val sourceNameState = remember { mutableStateOf(restoredSource.displayName) }
    var sourceName by sourceNameState
    val sourceRuntime = remember(ctx.applicationContext) { ctx.appContainer.sources }
    val sourceAccess = sourceRuntime.coordinator
    val sourceMutationGate = sourceRuntime.mutations
    val sourceAuthorizationRequiredState = remember {
        mutableStateOf(restoredSource.authorizationRequired)
    }
    var sourceAuthorizationRequired by sourceAuthorizationRequiredState
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
    val exportUi by exportVm.uiState.collectAsStateWithLifecycle()
    // #161: busy/done are DERIVED from the one export state machine, never parallel
    // flags — the pill can no longer claim "Exporting…" over a finished export, and no
    // path can forget to clear a boolean the phase transition already implies.
    val exportInFlight = exportUi.inFlight
    val exportDone = exportUi.done
    val exportMaskVisible = exportUi.maskVisible
    val previewTickState = remember { mutableIntStateOf(0) }
    var previewTick by previewTickState
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
    val rotationState = remember {
        mutableStateOf(SourceRotation.fromDegrees(initialRestoration.rotationDegrees))
    }
    var rotation by rotationState

    // #286: a display-only stand-in (the file's embedded thumbnail, else the provider's) shown
    // from the moment a source is picked or restored until the first engine render lands.
    // It never feeds the engine, an export or a cache. Keyed on the source identity only:
    // a later rotate does not reload it, because by then the render is on screen.
    var openPlaceholder by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sourceUri, sourceKind, sourceAuthorizationRequired) {
        openPlaceholder = null
        val uri = sourceUri ?: return@LaunchedEffect
        if (sourceKind == SourceKind.DEMO || sourceAuthorizationRequired) return@LaunchedEffect
        OpenPlaceholder.load(
            ctx.applicationContext,
            uri,
            rotation,
            dispatchers.io,
            rawSource = sourceKind == SourceKind.RAW,
        ) { bmp ->
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

    // 100% grain magnifier
    val magnifierOpenState = remember { mutableStateOf(false) }
    var magnifierOpen by magnifierOpenState
    // Lightroom's single tap on the photo: everything but the picture gets out of the way
    // (viewerUi.chromeHidden). Deliberately NOT animated. Hiding the top bar and the rail
    // changes the preview's height, and the preview is a weight(1f) sibling — animating
    // that would remeasure it every frame, which is the churn documented on PanelOverlay.
    // A one-shot layout change is a different thing and is what this is.
    val magnifierBitmapState = remember { mutableStateOf<Bitmap?>(null) }
    var magnifierBitmap by magnifierBitmapState
    val magnifierRenderingState = remember { mutableStateOf(false) }
    var magnifierRendering by magnifierRenderingState
    val magnifierStatusState = remember { mutableStateOf("") }
    var magnifierStatus by magnifierStatusState
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
    val roiOverlayState = remember { mutableStateOf<RoiOverlay?>(null) }
    var roiOverlay by roiOverlayState
    val roiJobRef = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val roiPatchTracker = remember { RoiPatchTracker() }
    // Zoom step 3: recent finished patch renders for the current edit (RoiPatchCache).
    // 64 MiB like rawr's tile LRU on roomy devices, 16 MiB elsewhere.
    val roiPatchCache = remember {
        RoiPatchCache<RoiOverlay>(
            maxBytes = if (ZoomPrewarm.eligible(ctx)) 64L shl 20 else 16L shl 20,
            sizeOf = { it.bitmap.allocationByteCount.toLong() },
            // Through the lease registry: a patch the Canvas still draws this frame is
            // recycled only once its draw lease closes.
            recycle = { retirePreviewBitmap(it.bitmap) },
            isShown = { it === roiOverlay },
        )
    }
    val roiPublicationGate = remember { RoiRenderPublicationGate() }
    val latestRoiRenderKeyState = rememberUpdatedState(previewTick)
    val latestRoiRenderKey by latestRoiRenderKeyState
    val latestMagnifierRenderKeyState = rememberUpdatedState(previewTick)
    val latestMagnifierRenderKey by latestMagnifierRenderKeyState

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
        roiPatchCache.clear()
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
        // Before roiOverlay is cleared, so the shown patch is skipped here and retired by
        // DisposableEffect(roiOverlay) once it has left the screen.
        roiPatchCache.retain(previewTick)
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
            // The editor is leaving: retire every cached patch, the one on screen included
            // (its own disposal may have skipped it as still cached). Lease-safe and
            // idempotent, so a patch retired twice or still mid-draw is fine.
            roiPatchCache.clear(includeShown = true)
        }
    }
    // Recycle a superseded ROI bitmap once it has left composition (safe — not mid-draw).
    DisposableEffect(roiOverlay) {
        val current = roiOverlay
        onDispose {
            // A patch still held by roiPatchCache is the cache's to recycle.
            if (current != null && !roiPatchCache.holds(current)) {
                retirePreviewBitmap(current.bitmap)
            }
        }
    }

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
    val exportRuntimeStateState = ExportWorkRuntime.state.collectAsState()
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
            this@EditorScreen,
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
    val editHistory = remember {
        EditHistory().also { history ->
            initialRestoredSession?.history?.let(history::restoreState)
        }
    }
    // The last SETTLED snapshot we committed. Capture coalescing pushes THIS (the
    // pre-edit state) onto undo when a newer settled state differs (see the capture
    // effect below), so one slider drag (which settles once) == one undo step.
    val committedSnapshotState = remember {
        mutableStateOf(initialRestoredSession?.committed)
    }
    var committedSnapshot by committedSnapshotState
    // Set true while we are programmatically restoring a snapshot (undo/redo). The
    // capture effect skips one settle cycle so the restore is NOT recorded as a new
    // edit — without this the restored state would push itself and we'd never escape.
    val restoringState = remember { mutableStateOf(false) }
    var restoring by restoringState
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

    // One-time engine init. EditorViewModel loads the catalog (process-wide engine from
    // EngineHolder, profile ids, built-in looks) once, off the main thread. This effect
    // applies it to the composition: engine, profiles and the checkpoint restore land in the
    // same main-thread step, so no render can start with un-restored params.
    val editorCatalog by editorVm.catalog.collectAsStateWithLifecycle()
    LaunchedEffect(editorCatalog) {
        val loaded = editorCatalog ?: return@LaunchedEffect
        val list = loaded.profiles
        engine = loaded.engine
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
        builtInGroups = loaded.builtInGroups
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
            this@EditorScreen,
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
            // The export sheet may still be open on API <= 28; close it so the message
            // is not hidden behind it.
            exportVm.onAction(ExportAction.CloseSheet)
            scope.showEditorMessage(snackbarHost, ctx.getString(R.string.editor_toast_storage_permission))
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
                    // A new photo opens Film & look: picking a stock is the next step for
                    // almost everyone, so it should not cost a tap on the category bar.
                    if (switched) activeCategory = CATEGORY_AFTER_NEW_PHOTO
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
    // Batch export: this edit and the export sheet's settings on more photos
    // (EditorBatchExport.kt, BatchExport.kt).
    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            startBatchExport(ctx, scope, state, engine, uris, snackbarHost) { status = it }
        }
    }
    // Half-frame pair: this photo and one more on one picture (HalfFramePair.kt).
    val pairPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            startHalfFramePair(
                ctx, scope, state, engine, sourceUri, sourceName, sourceKind, rotation, uri, snackbarHost,
            ) { status = it }
        }
    }
    BatchExportProgressEffect(ctx, scope, snackbarHost) { status = it }
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
    val pendingPresetExportState = remember { mutableStateOf<String?>(null) }
    var pendingPresetExport by pendingPresetExportState
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
            )
            // Begin publication authority in the same main-thread turn as the snapshot. An
            // older caller cannot resume later and reorder itself ahead of a newer UI state.
            CachedSourceDecodeRequest(decode, cache.beginRequest(cacheRequest))
        }

    /**
     * The stock browser's preview session (StockPreviews.kt). Built when the sheet opens, from a
     * snapshot of the look with only the stock left free. It reads the proxy the settle render
     * already cached -- the same request, so it never moves the cache's generation -- and never
     * decodes: with no cached proxy the tiles simply stay dark.
     */
    /** The film-balance matrix the decode applies for [film] when [balance] is on, else null. */
    fun filmBalanceFor(balance: Boolean, film: String): FloatArray? =
        if (balance && FilmStockBalance.isMeaningful(ctx, film)) FilmStockBalance.matrix(ctx, film) else null

    fun stockPreviewHost(vm: StockPreviewViewModel, forFilm: Boolean) = StockPreviewHost(
        previews = vm.previews,
        request = vm::request,
        open = open@{ owner ->
            if (!sourceRenderAllowed || sourceUri == null) return@open
            val fullEdge = state.previewMaxSize.coerceAtLeast(256)
            val snapshot = runCatching { cachedSourceDecodeRequest(sourceCache, fullEdge) }
                .getOrNull() ?: return@open
            // Each tile renders its stock as a tap would apply it, so the paper-dependent
            // settings (a silver paper's grade, the colour-pack lookup) and the film-dependent
            // ones (grain sub-layers, the silver film's move onto silver paper) follow the
            // candidate. They depend only on whether the film and paper are silver and on which
            // paper a film tap would pick, so the few distinct variants are built here, from
            // this snapshot of the state, and each tile copies its own stock id in.
            val currentFilm = state.filmProfile
            val currentPrint = state.printProfile
            val lastColourPrint = state.lastColourPrint
            fun variant(film: String?, print: String?) = state.toParams(
                previewMaxSizeOverride = STOCK_PREVIEW_EDGE,
                skipGrainHalation = true,
                filmOverride = film,
                printOverride = print,
            ).copy(filmProfile = "", printProfile = "")
            val silverFilm = SilverPrint.FILMS.first()
            // A film tap re-pairs the paper (SilverPrint.printAfterFilmChange); a paper tap
            // keeps the film. Every print a film tap can land on is in this set.
            val pairable = listOfNotNull(currentPrint, SilverPrint.MULTIGRADE, lastColourPrint)
                .distinct()
            fun pairedPrint(film: String) = SilverPrint.printAfterFilmChange(
                oldFilm = currentFilm, newFilm = film, print = currentPrint,
                lastColourPrint = lastColourPrint, available = pairable,
            )
            val variants: Map<Pair<Boolean, String>, SpektraParams> = if (forFilm) {
                buildMap {
                    for (silver in listOf(false, true)) {
                        for (print in pairable) {
                            put(silver to print, variant(if (silver) silverFilm else "", print))
                        }
                    }
                }
            } else {
                // Only silver-or-not matters for a paper, so one representative of each.
                mapOf(
                    (true to SilverPrint.MULTIGRADE) to variant(null, SilverPrint.MULTIGRADE),
                    (false to "") to variant(null, ""),
                )
            }
            val free = variants.entries.sortedBy { it.key.toString() }.map { it.value }
            val balance = state.balanceToFilmStock
            val cctf = state.savingCctfEncoding
            val saturation = state.saturation
            val vibrance = state.vibrance
            val gamut = state.gamutCompress
            val local = state.localAdjustments.toList()
            vm.open(
                StockPreviewSession(
                    key = listOf(
                        snapshot.ticket.request, free, currentFilm, currentPrint, lastColourPrint,
                        cctf, saturation, vibrance, gamut, local,
                    ),
                    source = {
                        sourceCache.acquire(snapshot.ticket)?.use { lease ->
                            withContext(dispatchers.default) {
                                downsampleLinearBox(lease.image, STOCK_PREVIEW_SOURCE_EDGE)
                            }
                        }
                    },
                    paramsFor = { id ->
                        if (forFilm) {
                            val print = pairedPrint(id)
                            variants.getValue(SilverPrint.isSilverFilm(id) to print)
                                .copy(filmProfile = id, printProfile = print)
                        } else {
                            val silver = SilverPrint.isSilverPaper(id)
                            variants.getValue(silver to if (silver) SilverPrint.MULTIGRADE else "")
                                .copy(filmProfile = currentFilm, printProfile = id)
                        }
                    },
                    toBitmap = { res ->
                        simResultToBitmapGraded(res, cctf, saturation, vibrance, gamut, local)
                    },
                    // "Balance to film stock" adapts the decoded photo to the CURRENT film; a
                    // tile for another film must show that film's adaptation, as a tap would.
                    retarget = if (forFilm) {
                        { id -> filmBalanceRetarget(filmBalanceFor(balance, currentFilm), filmBalanceFor(balance, id)) }
                    } else {
                        null
                    },
                ),
                owner,
            )
        },
        close = { owner -> vm.close(owner) },
    )

    /**
     * The Look strip's preview session: the photo through each built-in look, rendered as a
     * tap would apply it -- the run's baseline (presetRunBaseline) with the look laid over,
     * so a tile never stacks on the look applied a moment ago. Same rules as the stock tiles:
     * the cached proxy only, grain and halation skipped, one tile at a time.
     */
    fun presetPreviewHost(vm: StockPreviewViewModel) = StockPreviewHost(
        previews = vm.previews,
        request = vm::request,
        open = open@{ owner ->
            if (!sourceRenderAllowed || sourceUri == null) return@open
            val fullEdge = state.previewMaxSize.coerceAtLeast(256)
            val snapshot = runCatching { cachedSourceDecodeRequest(sourceCache, fullEdge) }
                .getOrNull() ?: return@open
            val now = runCatching { Presets.toJsonString(state) }.getOrNull() ?: return@open
            val baseline = presetRunBaseline(now, lastBuiltInOutput, presetBaselineJson) ?: now
            val looks = builtInGroups.values.flatten().associateBy { it.id }
            val balanceNow = state.balanceToFilmStock
            val filmNow = state.filmProfile
            // Called on the main thread (StockPreviewViewModel), so a scratch ParamsState
            // is safe to build here.
            fun lookFor(id: String): ParamsState = ParamsState().also { look ->
                Presets.decode(JSONObject(baseline), look)
                looks[id]?.let { BuiltInPresets.apply(it, look) }
            }
            val cctf = state.savingCctfEncoding
            val saturation = state.saturation
            val vibrance = state.vibrance
            val gamut = state.gamutCompress
            val local = state.localAdjustments.toList()
            vm.open(
                StockPreviewSession(
                    key = listOf(snapshot.ticket.request, baseline, looks.keys, cctf, saturation, vibrance, gamut, local),
                    source = {
                        sourceCache.acquire(snapshot.ticket)?.use { lease ->
                            withContext(dispatchers.default) {
                                downsampleLinearBox(lease.image, STOCK_PREVIEW_SOURCE_EDGE)
                            }
                        }
                    },
                    paramsFor = { id ->
                        lookFor(id).toParams(
                            previewMaxSizeOverride = STOCK_PREVIEW_EDGE,
                            skipGrainHalation = true,
                        )
                    },
                    // The shared source carries the current film's "Balance to film stock"
                    // adaptation; a look on another film (or flipping the switch) gets that
                    // look's own, as the decode after a tap would (Codex review on #435).
                    retarget = { id ->
                        val look = lookFor(id)
                        filmBalanceRetarget(
                            filmBalanceFor(balanceNow, filmNow),
                            filmBalanceFor(look.balanceToFilmStock, look.filmProfile),
                        )
                    },
                    toBitmap = { res ->
                        simResultToBitmapGraded(res, cctf, saturation, vibrance, gamut, local)
                    },
                ),
                owner,
            )
        },
        close = { owner -> vm.close(owner) },
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
        startMagnifierRender(
            nx = nx,
            ny = ny,
            ctx = ctx,
            scope = scope,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            magnifierJobRef = magnifierJobRef,
            magnifierPublicationGate = magnifierPublicationGate,
            loadSourceCachedForZoom = ::loadSourceCachedForZoom,
            engineState = engineState,
            previewTickState = previewTickState,
            magnifierBitmapState = magnifierBitmapState,
            magnifierOpenState = magnifierOpenState,
            magnifierRenderingState = magnifierRenderingState,
            magnifierStatusState = magnifierStatusState,
            latestMagnifierRenderKeyState = latestMagnifierRenderKeyState,
        )
    }

    // Lightroom-style zoom: render the currently-visible region (a native-pixel crop of the
    // source) at ~screen resolution via the FAST preview path, then overlay it sharply on the
    // graphicsLayer-scaled proxy. Modeled on openMagnifier's last-wins cancellation. Memory is
    // bounded by ROI_RENDER_MAX_PX (a few MB), independent of source megapixels — this is the
    // Lightroom "smart preview" strategy, so it sidesteps the full-res OOM entirely. Suppressed
    // while cropping or comparing (those branches own the gestures / have no zoom).
    fun renderRoi(roi: RoiRect) {
        startRoiPatchRender(
            roi = roi,
            scope = scope,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            roiJobRef = roiJobRef,
            roiPatchCache = roiPatchCache,
            roiPatchTracker = roiPatchTracker,
            roiPublicationGate = roiPublicationGate,
            loadSourceCachedForZoom = ::loadSourceCachedForZoom,
            engineState = engineState,
            previewTickState = previewTickState,
            roiOverlayState = roiOverlayState,
            latestRoiRenderKeyState = latestRoiRenderKeyState,
        )
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
                hotPixels = state.rawHotPixels, chromaNoise = state.rawChromaNoise,
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
        runDraftPreviewPass(
            this@EditorScreen,
            ctx = ctx,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            previewRevision = previewRevision,
            publicationGate = publicationGate,
            gradeCache = gradeCache,
            interacting = interacting,
            settings = settings,
            sourceCache = sourceCache,
            gradeCacheKey = ::gradeCacheKey,
            cachedSourceDecodeRequest = ::cachedSourceDecodeRequest,
            engineState = engineState,
            previewState = previewState,
        )
    }

    // Crisp FINAL preview render on settle: re-runs whenever params, source or rotation
    // change, after a debounce so it lands once the user pauses. The live DRAFT effect above
    // keeps the image moving during the edit (Lightroom's loupe), so this pass goes straight
    // to the full-resolution result instead of a separate coarse pass.
    // Keyed on engine for the same reason as the draft effect above: without it a cold
    // start that composes before the native engine is ready never renders at all.
    LaunchedEffect(previewTick, sourceRenderAllowed, engine) {
        runFinalPreviewPass(
            this@EditorScreen,
            ctx = ctx,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            previewRevision = previewRevision,
            publicationGate = publicationGate,
            gradeCache = gradeCache,
            zoomPrewarmEligible = zoomPrewarmEligible,
            gradeCacheKey = ::gradeCacheKey,
            loadSourceCachedForPreview = ::loadSourceCachedForPreview,
            retireSourceResources = ::retireSourceResources,
            scheduleZoomPrewarm = ::scheduleZoomPrewarm,
            engineState = engineState,
            previewState = previewState,
            beforePreviewState = beforePreviewState,
            decodingState = decodingState,
            lastRenderMsState = lastRenderMsState,
            previewBusyState = previewBusyState,
            renderErrState = renderErrState,
            sourceAuthorizationRequiredState = sourceAuthorizationRequiredState,
            statusState = statusState,
            sourceKindState = sourceKindState,
            sourceUriState = sourceUriState,
        )
    }

    // GPU LUT preview feed (experimental, default OFF): after the CPU preview settles,
    // bake a 33³ .cube of the current look and keep it + the linear proxy for the GPU
    // path. The proxy decode is a cache hit (the main effect already loaded it), so this
    // adds only the LUT bake. Cancelled/replaced cleanly when previewTick advances.
    LaunchedEffect(previewTick, gpuEnabled, sourceRenderAllowed) {
        runGpuPreviewFeed(
            this@EditorScreen,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            gpuEnabled = gpuEnabled,
            loadSourceCachedForPreview = ::loadSourceCachedForPreview,
            engineState = engineState,
            gpuGainState = gpuGainState,
            gpuLutState = gpuLutState,
            gpuProxyLeaseState = gpuProxyLeaseState,
        )
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
        state.rawHotPixels, state.rawChromaNoise,
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
    val prerenderDigestState = remember(sourceUri) { mutableStateOf<Pair<String, Long>?>(null) }
    var prerenderDigest by prerenderDigestState
    // Stood down after a speculative render ran the source out of memory. Keyed on
    // sourceUri like the digest, so a different photo gets a fresh chance.
    val prerenderOutOfMemoryState = remember(sourceUri) { mutableStateOf(false) }
    var prerenderOutOfMemory by prerenderOutOfMemoryState
    LaunchedEffect(
        previewTick, sourceRenderAllowed, sourceUri, exportUi.options,
        state.outputColorSpace, state.savingCctfEncoding,
        // A real export must not compete with a speculative one for the same eight cores:
        // as a key, this cancels any pre-render in flight the moment an export starts.
        exportInFlight,
        // Same for a run the editor does not own (a batch export): the runtime is busy.
        exportRuntimeStateState.value is ExportRuntimeState.Running,
    ) {
        runSpeculativePrerender(
            this@EditorScreen,
            ctx = ctx,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            currentSourceDecodeRequest = ::currentSourceDecodeRequest,
            engineState = engineState,
            previewBusyState = previewBusyState,
            sourceUriState = sourceUriState,
            prerenderDigestState = prerenderDigestState,
            prerenderOutOfMemoryState = prerenderOutOfMemoryState,
        )
    }

    // --- Non-destructive recipe: debounced auto-save ---
    // Grade fields included for the same reason as the render trigger above:
    // Presets.toJsonString already SERIALIZES them, so the recipe content was
    // right — but a grade-only edit never fired this effect, so it was not
    // persisted until some other change happened to save it along the way.
    LaunchedEffect(snapshot, recipeKey, recipeReady, defaultsJson, rotation,
        recipeAccess,
        state.rawWhiteBalance, state.rawTemperature, state.rawTint,
        state.rawHotPixels, state.rawChromaNoise,
        state.creativeWbTemp, state.creativeWbTint, state.balanceToFilmStock,
        state.localAdjustments,
        state.saturation, state.vibrance, state.gamutCompress) {
        runRecipeAutosave(
            this@EditorScreen,
            ctx = ctx,
            state = state,
            recipeEditEpoch = recipeEditEpoch,
            recipeKey = recipeKey,
            recipeRepo = recipeRepo,
            defaultsJsonState = defaultsJsonState,
            hasRecipeState = hasRecipeState,
            recipeAccessState = recipeAccessState,
            recipeReadyState = recipeReadyState,
            rotationState = rotationState,
            sourceNameState = sourceNameState,
            statusState = statusState,
        )
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
        state.rawHotPixels, state.rawChromaNoise,
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
    // only when something is open: at the root Back leaves the app, and Esc must not.
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
            // Delivered to whichever preview is on screen (Viewer.kt), the same transform
            // as its wheel / + / - controls.
            EditorShortcut.ZOOM_IN -> !overlayOpen && run { viewerVm.requestZoom(ViewerZoom.IN); true }
            EditorShortcut.ZOOM_OUT -> !overlayOpen && run { viewerVm.requestZoom(ViewerZoom.OUT); true }
            EditorShortcut.ZOOM_FIT -> !overlayOpen && run { viewerVm.requestZoom(ViewerZoom.FIT); true }
            EditorShortcut.PHOTO_INFO -> !overlayOpen && sourceUri != null && run { openPhotoInfo(); true }
            EditorShortcut.APPLY_PREVIOUS -> !overlayOpen && previousEdit?.let { applyPreviousEdit(it); true } == true
        }
    }
    DisposableEffect(Unit) {
        editorShortcutHandler = { shortcutHandler(it) }
        onDispose { editorShortcutHandler = null }
    }

    // --- back handling on the root editor ---
    // 0) full-screen tool open -> close it; 1) panel open -> close panel;
    // 2) else the system takes Back (to home).
    // Above every other rung: with the chrome hidden there is no visible control at
    // all, so Back has to mean "give me the app back" before it means anything else.
    BackHandler(enabled = viewerUi.chromeHidden) { viewerVm.onAction(EditorViewerAction.ShowChrome) }
    // Predictive back on the full-screen tools (crop, mask geometry, eyedropper): while the
    // swipe is held the tool shrinks away so the user can see Back will close it, and can
    // still cancel. Committing cancels the tool exactly as its own Cancel button does.
    androidx.activity.compose.PredictiveBackHandler(
        enabled = toolUi.cropOpen || toolUi.maskGeometryOpen || toolUi.sampleOpen,
    ) { progress ->
        try {
            progress.collect { overlayBackProgress = it.progress }
            val tool = toolVm.current
            when {
                tool.cropOpen -> toolVm.onAction(EditorToolAction.CloseCrop)
                tool.maskGeometryOpen -> toolVm.onAction(EditorToolAction.CloseMaskGeometry)
                tool.sampleOpen -> toolVm.onAction(EditorToolAction.CloseSample)
            }
        } catch (e: CancellationException) {
            // Gesture cancelled: leave the tool open.
        } finally {
            overlayBackProgress = 0f
        }
    }
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
    // At the editor root Back is left to the system, so Android 13+ shows the predictive
    // back-to-home preview and the task simply goes to the background. Nothing is lost:
    // the session is checkpointed on ON_STOP and restored on the next launch. (This used
    // to be a "press Back again to exit" trap, which blocks that preview and which M3's
    // back model -- close the top surface, pop the stack, then exit -- does not have.)
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
                // The active panel body, shared by both branches so the per-category
                // `when` below is not duplicated.
                val panelContent: @Composable ColumnScope.() -> Unit = {
                    CompositionLocalProvider(LocalSliderInteraction provides sliderInteraction) {
                    when (activeCategory) {
                        Category.INPUT -> InputSection(state, onEditCrop = { toolVm.onAction(EditorToolAction.OpenCrop) },
                            onPickNeutral = { toolVm.onAction(EditorToolAction.PickNeutral) })
                        Category.RAW_WB -> ImportRawSection(state, isRaw = sourceKind == SourceKind.RAW,
                            onPickNeutral = { toolVm.onAction(EditorToolAction.PickNeutral) })
                        Category.SIMULATION, Category.PAPER, Category.SCANNER, Category.OUTPUT -> EditorSimulationPanel(
                            filmPreviewVm = filmPreviewVm,
                            printPreviewVm = printPreviewVm,
                            part = when (activeCategory) {
                                Category.PAPER -> SimPart.PRINT
                                Category.SCANNER -> SimPart.SCANNER
                                Category.OUTPUT -> SimPart.OUTPUT
                                else -> SimPart.FILM
                            },
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
                            sourceUriState = sourceUriState,
                            sourceKindState = sourceKindState,
                            sourceNameState = sourceNameState,
                            sourceAuthorizationRequiredState = sourceAuthorizationRequiredState,
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
                    )
                }
                // Re-encoded only when a parameter actually changes, not per frame.
                // Shared by the stage row/rail and the panels:
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
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        // --- STAGE RAIL (expanded windows), Export as its FAB ---
                        if (!viewerUi.chromeHidden) {
                            StageRail(
                                active = activeCategory,
                                dirty = dirty,
                                onOpen = { activeCategory = it },
                                exportVisible = engine != null && sourceRenderAllowed,
                                exporting = exportInFlight,
                                onExport = { requestExport() },
                            )
                        }
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
                        }
                        // --- ADJUSTMENT PANEL (docked side column) ---
                        // A Row sibling, deliberately not animated: the preview keeps its
                        // height, and the panel's one-shot width change never becomes the
                        // per-frame GLSurfaceView resize the narrow branch guards against.
                        val sideCategory = activeCategory
                        // Book posture on a device with a physical gap: keep the gap empty so
                        // neither the photo nor the tools sit under it.
                        if (bookAligned && bookHingeGap > 0.dp) Spacer(Modifier.width(bookHingeGap))
                        if (sideCategory == null && bookAligned) {
                            // Panel closed in book posture: the photo still keeps to its own
                            // screen rather than straddling the fold; the tools' screen stays
                            // empty until a category is opened again.
                            Spacer(Modifier.width(sidePaneWidth))
                        }
                        if (sideCategory != null) {
                            SidePanel(
                                modifier = Modifier.predictiveBackShrink(panelBackProgress),
                                category = sideCategory,
                                onDismiss = { activeCategory = null },
                                onResetCategory = resetActiveCategory,
                                width = sidePaneWidth,
                                tabs = categoriesOf(sideCategory.stage),
                                dirty = dirty,
                                onSelectTab = { activeCategory = it },
                                content = panelContent,
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        // --- PREVIEW (pinned, weight) ---
                        BoxWithConstraints(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .heightIn(min = 220.dp),
                        ) {
                            // Tabletop: the panel fills the lower half exactly, so the photo
                            // (inset by the panel's height) fills the upper one. This box
                            // starts where the editor box does, so the hinge offsets apply.
                            val tabletopPanelHeight = tabletopPanelHeightDp(posture, maxHeight.value)?.dp
                            val tabletopPhotoHeight = tabletopPhotoHeightDp(posture)?.dp
                            if (tabletopPhotoHeight != null) {
                                // The photo keeps the upper half whether or not a panel is
                                // open, so closing one never lays it back across the hinge.
                                // It ends at the hinge's TOP edge: on a device with a physical
                                // gap, the strip between top and bottom is not display.
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(tabletopPhotoHeight),
                                ) { previewRegion() }
                            } else {
                                previewRegion()
                            }

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
                                    fixedHeight = tabletopPanelHeight,
                                    tabs = activeCategory?.let { categoriesOf(it.stage) }.orEmpty(),
                                    dirty = dirty,
                                    onSelectTab = { activeCategory = it },
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

                        // --- STAGE ROW (compact and medium windows) ---
                        // Tapping the open stage folds the panel away: photo + this row.
                        if (!viewerUi.chromeHidden) {
                            StageRow(
                                active = activeCategory,
                                dirty = dirty,
                                onOpen = { activeCategory = it },
                            )
                        }
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

        // The full-screen tools share one wrapper so a held back swipe shrinks whichever
        // is open (see the PredictiveBackHandler above).
        Box(Modifier.fillMaxSize().predictiveBackShrink(overlayBackProgress)) {
            EditorToolOverlays(
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
            val exportPresets by exportPresetVm.uiState.collectAsStateWithLifecycle()
            // Opening the sheet: load the presets, then preselect the one used last so the
            // Export button is two taps for a repeat export.
            LaunchedEffect(Unit) {
                exportPresetVm.onError = { status = ctx.getString(R.string.editor_status_export_preset_failed, it) }
                exportPresetVm.load()
            }
            // Saveable, so a recreated Activity (theme or locale change) that restores the open
            // sheet with the user's own edits does not overwrite them with the preset again.
            var lastUsedApplied by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(exportPresets.lastUsed) {
                val last = exportPresets.lastUsedPreset
                if (!lastUsedApplied && last != null) {
                    lastUsedApplied = true
                    exportVm.onAction(ExportAction.SetOptions(last.options))
                    exportVm.onAction(ExportAction.SetKeepGps(last.keepGps))
                }
            }
            val matchingPreset = exportPresets.presets.firstOrNull {
                it.matches(exportUi.options, exportUi.keepGps)
            }?.name
            ExportSheet(
                presetNames = exportPresets.presets.map { it.name },
                selectedPreset = matchingPreset,
                onSelectPreset = { name ->
                    exportPresets.presets.firstOrNull { it.name == name }?.let { p ->
                        exportVm.onAction(ExportAction.SetOptions(p.options))
                        exportVm.onAction(ExportAction.SetKeepGps(p.keepGps))
                    }
                },
                onSavePreset = { name ->
                    exportPresetVm.save(name, exportVm.current.options, exportVm.current.keepGps)
                    status = ctx.getString(R.string.editor_status_export_preset_saved, name)
                },
                onDeletePreset = { name -> exportPresetVm.delete(name) },
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
                onBatchExport = if (engine != null && batchSupports(exportUi.options)) {
                    { requestBatchExport(ctx, legacyStoragePermission, batchPicker) { status = it } }
                } else {
                    null
                },
                onHalfFramePair = if (
                    engine != null && sourceUri != null && sourceKind != SourceKind.DEMO &&
                    halfFramePairSupports(exportUi.options)
                ) {
                    { requestHalfFramePair(ctx, legacyStoragePermission, pairPicker) { status = it } }
                } else {
                    null
                },
                onExport = {
                    startEditorExport(
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
                    )
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
