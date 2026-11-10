/*
 * Spektrafilm for Android — the editor's preview render effects. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273): the draft pass, AI skin activation, the settle
 * pass, the GPU LUT feed, the render trigger and the idle full-resolution pre-render, in the
 * same order. Non-restartable, so it composes inside EditorScreen's own scope exactly as the
 * inline code did. Editor values the effects read after launch are passed as state holders
 * and re-bound as delegates, so those reads stay live.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.spectrafilm.app.masks.hasSemantic
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Wait before finding skin again after its preview decode was superseded by a decode edit. */
private const val SKIN_RETRY_MS = 250L

@Composable
@NonRestartableComposable
internal fun EditorPreviewEffects(
    screen: EditorScreenState,
    session: EditorSession,
    ctx: Context,
    dispatchers: AppDispatchers,
    settings: AppSettings,
    toolVm: EditorToolViewModel,
    viewerVm: EditorViewerViewModel,
    exportVm: ExportViewModel,
    engineState: State<SpektraEngine?>,
    sourceUriState: State<Uri?>,
    sourceKindState: State<SourceKind>,
    sourceRenderAllowed: Boolean,
    previewRevision: Long,
    interacting: MutableState<Boolean>,
    statusState: MutableState<String>,
    gpuEnabled: Boolean,
    zoomPrewarmEligible: Boolean,
    snapshot: SpektraParams,
    exportOptions: ExportOptions,
    exportInFlight: Boolean,
    exportRuntimeStateState: State<ExportRuntimeState>,
    retireSourceResources: () -> Unit,
    scheduleZoomPrewarm: () -> Unit,
    requireSourceAuthorization: () -> Unit,
    currentSourceDecodeRequest: () -> SourceDecodeRequest,
    cachedSourceDecodeRequest: suspend (DecodedSourceCache, Int) -> CachedSourceDecodeRequest,
    loadSourceCachedForPreview: suspend (Int) -> DecodedSourceCache.Lease,
) {
    val previewTickState = screen.previewTickState
    val publicationGate = screen.publicationGate
    val gradeCache = screen.gradeCache
    val sourceCache = screen.sourceCache
    val previewState = screen.previewState
    val beforePreviewState = screen.beforePreviewState
    val decodingState = screen.decodingState
    val lastRenderMsState = screen.lastRenderMsState
    val previewBusyState = screen.previewBusyState
    val renderErrState = screen.renderErrState
    val gpuGainState = screen.gpuGainState
    val gpuLutState = screen.gpuLutState
    val gpuProxyLeaseState = screen.gpuProxyLeaseState
    val state = session.params
    val sourceState = session.source
    val rotationState = session.rotation
    val engine by engineState
    val sourceUri by sourceUriState
    val sourceKind by sourceKindState
    val rotation by rotationState
    var previewTick by previewTickState
    // Everything that feeds the NATIVE render for a fit preview at [fullEdge]:
    // the engine-param snapshot + the decode key. Reads Compose state — call on
    // the main thread. Grade inputs are deliberately absent (see GradeCache).
    fun gradeCacheKey(fullEdge: Int): GradeCache.Key {
        val filmBalance =
            if (state.balanceToFilmStock && FilmStockBalance.isMeaningful(ctx, state.filmProfile)) state.filmProfile else ""
        // The Crop tab renders the whole frame (CropTool.kt): its crop frame sits on the photo,
        // so the engine must not have cut it already. Part of the key, so the cropped and the
        // crop-free renders are cached apart.
        val params = state.toParams(skipGrainHalation = true)
        return GradeCache.Key(
            engineParams = if (toolVm.current.cropOpen) params.withoutCrop() else params,
            decode = GradeCache.DecodeKey(
                uri = sourceUri?.toString(), kind = sourceKind.name,
                whiteBalance = state.rawWhiteBalance, temperature = state.rawTemperature,
                tint = state.rawTint, creativeTemp = state.creativeWbTemp,
                creativeTint = state.creativeWbTint, filmBalance = filmBalance,
                rotationDegrees = rotation.degrees, maxEdge = fullEdge,
                hotPixels = state.rawHotPixels, chromaNoise = state.rawChromaNoise,
                straightenDegrees = state.straightenDegrees,
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
            dispatchers,
            draftSuppressed = { toolVm.current.anyOverlayOpen || viewerVm.current.compare },
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
            cachedSourceDecodeRequest = cachedSourceDecodeRequest,
            engineState = engineState,
            previewState = previewState,
        )
    }

    // AI skin masks (SkinFields.kt): bind renders to this photo's skin, and find it once a skin
    // mask exists. Activation runs in composition so no render of a new photo sees the old field.
    val skinKey = SkinSourceKey(sourceUri?.toString(), sourceKind.name, rotation.degrees, state.straightenDegrees)
    // Keyed side effect run once per key in composition; returns the key so remember
    // holds a value rather than Unit (lint RememberReturnType).
    remember(skinKey) { SkinFields.activate(skinKey); skinKey }
    val needsSkin = state.localAdjustments.any { it.mask.hasSemantic }
    LaunchedEffect(skinKey, needsSkin, sourceRenderAllowed, SkinFields.retryToken) {
        if (!needsSkin || !sourceRenderAllowed) return@LaunchedEffect
        // A decode edit (WB, creative temp, ...) supersedes the preview decode this borrows and
        // cancels the load without cancelling us: try again, still for the same photo.
        while (true) {
            val fresh = try {
                SkinFields.ensure(ctx, skinKey, dispatchers) {
                    loadSourceCachedForPreview(state.previewMaxSize.coerceAtLeast(256)).use { lease ->
                        withContext(dispatchers.default) {
                            Triple(linearToDisplayArgb(lease.image), lease.image.width, lease.image.height)
                        }
                    }
                }
            } catch (c: CancellationException) {
                if (!isActive) throw c
                delay(SKIN_RETRY_MS)
                continue
            }
            if (fresh) previewTick++
            break
        }
    }

    // Crisp FINAL preview render on settle: re-runs whenever params, source or rotation
    // change, after a debounce so it lands once the user pauses. The live DRAFT effect above
    // keeps the image moving during the edit (Lightroom's loupe), so this pass goes straight
    // to the full-resolution result instead of a separate coarse pass.
    // Keyed on engine for the same reason as the draft effect above: without it a cold
    // start that composes before the native engine is ready never renders at all.
    LaunchedEffect(previewTick, sourceRenderAllowed, engine) {
        runFinalPreviewPass(
            dispatchers,
            ctx = ctx,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            previewRevision = previewRevision,
            publicationGate = publicationGate,
            gradeCache = gradeCache,
            zoomPrewarmEligible = zoomPrewarmEligible,
            gradeCacheKey = ::gradeCacheKey,
            loadSourceCachedForPreview = loadSourceCachedForPreview,
            retireSourceResources = retireSourceResources,
            scheduleZoomPrewarm = scheduleZoomPrewarm,
            engineState = engineState,
            previewState = previewState,
            beforePreviewState = beforePreviewState,
            cropFreePreviewState = screen.cropFreePreviewState,
            decodingState = decodingState,
            lastRenderMsState = lastRenderMsState,
            previewBusyState = previewBusyState,
            renderErrState = renderErrState,
            statusState = statusState,
            sourceState = sourceState,
            requireSourceAuthorization = requireSourceAuthorization,
        )
    }

    // GPU LUT preview feed (experimental, default OFF): after the CPU preview settles,
    // bake a 33³ .cube of the current look and keep it + the linear proxy for the GPU
    // path. The proxy decode is a cache hit (the main effect already loaded it), so this
    // adds only the LUT bake. Cancelled/replaced cleanly when previewTick advances.
    LaunchedEffect(previewTick, gpuEnabled, sourceRenderAllowed) {
        runGpuPreviewFeed(
            dispatchers,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            gpuEnabled = gpuEnabled,
            loadSourceCachedForPreview = loadSourceCachedForPreview,
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
        state.rawHotPixels, state.rawChromaNoise, state.straightenDegrees,
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
        previewTick, sourceRenderAllowed, sourceUri, exportOptions,
        state.outputColorSpace, state.savingCctfEncoding,
        // A real export must not compete with a speculative one for the same eight cores:
        // as a key, this cancels any pre-render in flight the moment an export starts.
        exportInFlight,
        // Same for a run the editor does not own (a batch export): the runtime is busy.
        exportRuntimeStateState.value is ExportRuntimeState.Running,
    ) {
        runSpeculativePrerender(
            dispatchers,
            exportVm,
            ctx = ctx,
            state = state,
            sourceRenderAllowed = sourceRenderAllowed,
            currentSourceDecodeRequest = currentSourceDecodeRequest,
            engineState = engineState,
            previewBusyState = previewBusyState,
            sourceState = sourceState,
            prerenderDigestState = prerenderDigestState,
            prerenderOutOfMemoryState = prerenderOutOfMemoryState,
            previewState = previewState,
            decodingState = decodingState,
        )
    }
}

/** These params with the engine crop switched off: the whole frame, for the Crop tab. */
internal fun SpektraParams.withoutCrop(): SpektraParams =
    if (io.crop) copy(io = io.copy(crop = false)) else this
