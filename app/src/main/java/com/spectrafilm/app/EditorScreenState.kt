/*
 * Spektrafilm for Android — the editor composition's own state. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * #582: the plain state holders and per-composition caches EditorScreen used to declare one
 * `remember` at a time. One instance per editor composition ([rememberEditorScreenState]),
 * so lifetime is exactly what the separate remembers gave: fresh when the editor enters
 * composition, released when it leaves. EditorScreen keeps its local names as views of
 * these holders (`var preview by screen.previewState`), so reads stay snapshot reads.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

internal class EditorScreenState {
    // PERF: decoded proxy-source cache. The interactive preview path re-uses this decoded
    // LinearImage across look/film param edits instead of re-running source decode (LibRaw
    // RAW decode or bitmap decode + sRGB→ProPhoto linearization + EXIF/manual rotation) on
    // every previewTick. Keyed by the decode-affecting inputs only (URI + kind + RAW WB/
    // temp/tint + manual rotation + target edge); any change to one of those invalidates it
    // (the key mismatches → fresh decode). See DecodedSourceCache for the read-only proof
    // that the same buffer can be re-fed to the engine without a defensive copy.
    // EXPORT does NOT use this cache — it always decodes fresh at EXPORT_MAX_EDGE_PX.
    val sourceCache = DecodedSourceCache()

    // Retained-result grade cache: grade-only edits (saturation/vibrance/gamut/
    // masks) re-grade the last settle render's pristine engine output in pure
    // Kotlin — zero native work. Keyed by engine params + decode key + edge.
    val gradeCache = GradeCache()

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
    val zoomSourceCache = DecodedSourceCache()

    // Single-flight guards: a cancelled zoom/preview render's still-running native decode is
    // reused by the next gesture instead of triggering an overlapping re-decode (battery).
    val zoomDecodeFlight = SingleFlight<Unit>()
    val previewDecodeFlight = SingleFlight<Unit>()

    val publicationGate = RenderPublicationGate()

    // The rendered preview and the compare "before" frame.
    val previewState: MutableState<Bitmap?> = mutableStateOf(null)
    val beforePreviewState: MutableState<Bitmap?> = mutableStateOf(null)

    /**
     * The published preview when it was rendered WITHOUT the engine crop (the Crop tab's render),
     * else null. Compared by identity with [previewState]: any other publisher (a cropped
     * render, an export result) replaces the preview and so ends the match.
     */
    val cropFreePreviewState: MutableState<Bitmap?> = mutableStateOf(null)
    val previewBusyState: MutableState<Boolean> = mutableStateOf(false)
    val decodingState: MutableState<Boolean> = mutableStateOf(false)
    val lastRenderMsState: MutableState<Long?> = mutableStateOf(null)
    val renderErrState: MutableState<String?> = mutableStateOf(null)
    val previewTickState: MutableIntState = mutableIntStateOf(0)

    // Set by loadSource when a compressed (lossy/JPEG-XL) DNG fell back to the platform
    // ImageDecoder. Drives a one-shot snackbar (a render path can't show UI directly).
    val dngFallbackNoticeState: MutableState<Boolean> = mutableStateOf(false)

    // LUT export
    val bakingLutState: MutableState<Boolean> = mutableStateOf(false)
    val pendingLutTextState: MutableState<String?> = mutableStateOf(null)

    // Experimental GPU LUT preview (Settings → Experimental, default OFF): the latest linear
    // proxy and a baked 3D LUT of the current look, GPU-sampled by PreviewRegion.
    val gpuProxyLeaseState: MutableState<DecodedSourceCache.Lease?> = mutableStateOf(null)
    val gpuLutState: MutableState<CubeLut?> = mutableStateOf(null)
    // Auto-exposure gain (2^ev) for the GPU path: the LUT is baked AE-off at unity
    // gain, so the shader multiplies this in before the lookup (see LutGpuPreview).
    val gpuGainState: MutableFloatState = mutableFloatStateOf(1f)

    // Saved presets, and the built-in look browsing run (see EditorScreen's applyWithAmount).
    val presetListState: MutableState<List<String>> = mutableStateOf(emptyList())
    val presetBaselineJsonState: MutableState<String?> = mutableStateOf(null)
    val lastBuiltInOutputState: MutableState<String?> = mutableStateOf(null)
    // Id of the built-in look whose result lastBuiltInOutput is: the Look strip's selection.
    val lastBuiltInIdState: MutableState<String?> = mutableStateOf(null)

    // Non-destructive recipe (sidecar) layer.
    val recipeReadyState: MutableState<Boolean> = mutableStateOf(false)
    val hasRecipeState: MutableState<Boolean> = mutableStateOf(false)
    val defaultsJsonState: MutableState<String?> = mutableStateOf(null)

    val snackbarHost = SnackbarHostState()

    // Explicit conflict marker used only while AppRoot is recovering transient session IO.
    val liveCursorMutatedState: MutableState<Boolean> = mutableStateOf(false)
    // Hold Undo: the whole history as a named list (timeline, index of the live state).
    val historySheetState: MutableState<Pair<List<EditSnapshot>, Int>?> = mutableStateOf(null)
    // Photo info sheet, read on demand for the photo on screen.
    val photoInfoState: MutableState<PhotoInfo?> = mutableStateOf(null)
    val pendingPresetExportState: MutableState<String?> = mutableStateOf(null)
    // What the running export rendered, so Share stays current until the next edit.
    val exportBaselineState: MutableState<Pair<EditSnapshot, Category?>?> = mutableStateOf(null)

    // Export runtime observation (runExportRuntimeObserver).
    val lastHandledExportRunState: MutableLongState = mutableLongStateOf(0L)
    val exportPublicationState: MutableState<Pair<Long, RenderPublicationTicket>?> = mutableStateOf(null)
    val sessionCheckpointAction = arrayOfNulls<() -> Boolean>(1)

    // 0..1 while a predictive-back swipe is closing the open panel / full-screen tool.
    val panelBackProgressState: MutableFloatState = mutableFloatStateOf(0f)
    val overlayBackProgressState: MutableFloatState = mutableFloatStateOf(0f)

    /** Retire every source-derived resource before committing a different source identity. */
    fun retireSourceResources(zoom: EditorZoomController) {
        publicationGate.invalidate()
        sourceCache.invalidate()
        zoomSourceCache.invalidate()
        gradeCache.clear()
        previewDecodeFlight.invalidate()
        zoomDecodeFlight.invalidate()

        // A failed authorization/decode must not leave pixels from the prior source visible or
        // retained. State removal drives EditorScreen's preview DisposableEffects, whose read
        // leases defer physical recycle only until an already-running histogram sample completes.
        previewState.value = null
        beforePreviewState.value = null
        cropFreePreviewState.value = null

        gpuProxyLeaseState.value?.close()
        gpuProxyLeaseState.value = null
        gpuLutState.value = null
        gpuGainState.floatValue = 1f

        zoom.retireSource()
        // Evidence is published only after every production gate/cache/lease owner has been
        // invalidated or retired; callers never observe a start-of-cleanup false positive.
        editorProbe.publishSourceRetirement()
    }

    /** Releases the caches and the GPU proxy lease when the editor leaves composition. */
    fun release() {
        gpuProxyLeaseState.value?.close()
        gradeCache.close()
        zoomDecodeFlight.invalidate()
        previewDecodeFlight.invalidate()
        zoomSourceCache.invalidate()
        sourceCache.invalidate()
    }
}

/** One [EditorScreenState] per editor composition, released when it leaves. */
@Composable
internal fun rememberEditorScreenState(): EditorScreenState {
    val screen = remember { EditorScreenState() }
    DisposableEffect(screen) { onDispose { screen.release() } }
    return screen
}
