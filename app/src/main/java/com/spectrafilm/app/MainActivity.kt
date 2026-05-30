/*
 * Spektrafilm for Android — app entry. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Lightroom-mobile-style editor: an edge-to-edge near-black canvas with a pinned live
 * preview, a transparent top bar (back/close + export/save + Settings gear + About "?"),
 * an inline adjustment panel that slides up when a category is chosen, and a horizontally
 * scrollable bottom category bar mapping each spektrafilm GUI section (Input, RAW WB,
 * Simulation, Grain, Preflash, Halation, Couplers, Glare, Experimental, Display, Presets,
 * Source) to one icon. Edits rebuild an immutable SpektraParams and trigger a debounced,
 * downscaled preview render. Export renders full-resolution behind a blocking mask then
 * saves to the gallery. A preview rotate button rotates the decoded source so both the
 * preview AND the export reflect the orientation. RAW/DNG import (LibRaw -> ACES2065-1),
 * the sRGB photo picker, the synthetic demo image, named JSON presets, and non-destructive
 * per-image recipe auto-save/restore are all preserved.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.Rgb2Raw
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.libraw.DecodeStatus
import com.spectrafilm.libraw.RawDecodeException
import com.spectrafilm.libraw.RawDecoder
import com.spectrafilm.libraw.WhiteBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which kind of source image is loaded. */
private enum class SourceKind { DEMO, PHOTO, RAW }

/** Top-level navigation destinations. */
private enum class Screen { EDITOR, SETTINGS, ABOUT, CURVES_FILM, CURVES_PRINT }

/** Adjustment categories shown in the bottom bar; each maps to an existing section. */
private enum class Category(val label: String) {
    SOURCE("Source"),
    PRESETS("Presets"),
    SIMULATION("Simulation"),
    INPUT("Input"),
    RAW_WB("RAW WB"),
    GRAIN("Grain"),
    HALATION("Halation"),
    GLARE("Glare"),
    COUPLERS("Couplers"),
    PREFLASH("Preflash"),
    EXPERIMENTAL("Experimental"),
    DISPLAY("Display"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = AppSettings.from(this)
        setContent {
            var themeMode by remember { mutableStateOf(settings.theme) }
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                AppRoot(settings = settings, onThemeChanged = { themeMode = it })
            }
        }
    }

    /** Hosts onboarding + top-level navigation around the editor. */
    @Composable
    private fun AppRoot(settings: AppSettings, onThemeChanged: (ThemeMode) -> Unit) {
        var showOnboarding by remember { mutableStateOf(!settings.seenOnboarding) }
        var screen by remember { mutableStateOf(Screen.EDITOR) }
        val ctx = LocalContext.current

        // Catalog-grouped profile options for the Settings default-profile pickers.
        var settingsFilmGroups by remember { mutableStateOf<List<DropdownGroup>>(emptyList()) }
        var settingsPrintGroups by remember { mutableStateOf<List<DropdownGroup>>(emptyList()) }

        // Profile IDs and names remembered for the Curves screens.
        var curvesFilmId by remember { mutableStateOf("") }
        var curvesFilmName by remember { mutableStateOf("") }
        var curvesPrintId by remember { mutableStateOf("") }
        var curvesPrintName by remember { mutableStateOf("") }

        // Back from a pushed sub-screen returns to the editor (root).
        BackHandler(enabled = screen != Screen.EDITOR) { screen = Screen.EDITOR }

        Box(
            Modifier
                .fillMaxSize()
                .background(SpectraIcons.nearBlackCanvas),
        ) {
            when (screen) {
                Screen.EDITOR -> EditorScreen(
                    settings = settings,
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onOpenAbout = { screen = Screen.ABOUT },
                    onProfileGroups = { f, p -> settingsFilmGroups = f; settingsPrintGroups = p },
                    onOpenFilmCurves = { id, name ->
                        curvesFilmId = id; curvesFilmName = name; screen = Screen.CURVES_FILM
                    },
                    onOpenPrintCurves = { id, name ->
                        curvesPrintId = id; curvesPrintName = name; screen = Screen.CURVES_PRINT
                    },
                )
                Screen.SETTINGS -> NavScaffold("Settings", onBack = { screen = Screen.EDITOR }) {
                    SettingsScreen(
                        settings = settings,
                        filmGroups = settingsFilmGroups,
                        printGroups = settingsPrintGroups,
                        onThemeChanged = onThemeChanged,
                        onShowOnboarding = { showOnboarding = true; screen = Screen.EDITOR },
                    )
                }
                Screen.ABOUT -> NavScaffold("About", onBack = { screen = Screen.EDITOR }) {
                    AboutScreen()
                }
                Screen.CURVES_FILM -> ProfileCurvesScreen(
                    profileId = curvesFilmId,
                    displayName = curvesFilmName,
                    onBack = { screen = Screen.EDITOR },
                )
                Screen.CURVES_PRINT -> ProfileCurvesScreen(
                    profileId = curvesPrintId,
                    displayName = curvesPrintName,
                    onBack = { screen = Screen.EDITOR },
                )
            }

            if (showOnboarding && screen == Screen.EDITOR) {
                WelcomeFlow(
                    onFinish = { settings.seenOnboarding = true; showOnboarding = false },
                    onOpenSettings = {
                        settings.seenOnboarding = true; showOnboarding = false; screen = Screen.SETTINGS
                    },
                    onReportIssue = { Links.open(ctx, Links.NEW_ISSUE) },
                )
            }
        }
    }

    /** A simple back-arrow top bar wrapping a sub-screen (inset for status bar). */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NavScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        TextButton(onClick = onBack) { Text("Back") }
                    },
                )
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }

    @Composable
    private fun EditorScreen(
        settings: AppSettings,
        onOpenSettings: () -> Unit,
        onOpenAbout: () -> Unit,
        onProfileGroups: (List<DropdownGroup>, List<DropdownGroup>) -> Unit,
        onOpenFilmCurves: (id: String, name: String) -> Unit,
        onOpenPrintCurves: (id: String, name: String) -> Unit,
    ) {
        val ctx = LocalContext.current.applicationContext
        val scope = lifecycleScope

        var engine by remember { mutableStateOf<SpektraEngine?>(null) }
        var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
        val state = remember { ParamsState() }

        // PERF: decoded proxy-source cache. The interactive preview path re-uses this decoded
        // LinearImage across look/film param edits instead of re-running loadSource() (LibRaw
        // RAW decode or bitmap decode + sRGB→ProPhoto linearization + EXIF/manual rotation) on
        // every previewTick. Keyed by the decode-affecting inputs only (URI + kind + RAW WB/
        // temp/tint + manual rotation + target edge); any change to one of those invalidates it
        // (the key mismatches → fresh decode). See DecodedSourceCache for the read-only proof
        // that the same buffer can be re-fed to the engine without a defensive copy. EXPORT and
        // the 100% magnifier do NOT use this cache — they always decode fresh at MAX_EDGE_PX.
        val sourceCache = remember { DecodedSourceCache() }
        DisposableEffect(Unit) { onDispose { sourceCache.invalidate() } }

        // bundled catalog (friendly stock names + grouping) and built-in presets
        var builtInGroups by remember { mutableStateOf<Map<String, List<BuiltInPreset>>>(emptyMap()) }
        var catalogReady by remember { mutableStateOf(false) }

        // image / result state
        var sourceUri by remember { mutableStateOf<Uri?>(null) }
        var sourceKind by remember { mutableStateOf(SourceKind.DEMO) }
        var sourceName by remember { mutableStateOf("synthetic demo image") }
        var preview by remember { mutableStateOf<Bitmap?>(null) }
        var beforePreview by remember { mutableStateOf<Bitmap?>(null) }
        var status by remember { mutableStateOf("initializing…") }
        var previewBusy by remember { mutableStateOf(false) }
        var decoding by remember { mutableStateOf(false) }
        var lastRenderMs by remember { mutableStateOf<Long?>(null) }
        var renderErr by remember { mutableStateOf<String?>(null) }
        var exporting by remember { mutableStateOf(false) }
        var exportDone by remember { mutableStateOf(false) }
        var previewTick by remember { mutableIntStateOf(0) }

        // source rotation (applied to the decoded LinearImage -> preview AND export).
        // This is the user's MANUAL step only; the EXIF baseline is derived fresh per load
        // (see loadSource) and is NOT persisted in the recipe.
        var rotation by remember { mutableStateOf(SourceRotation.NONE) }

        // Set by loadSource when a compressed (lossy/JPEG-XL) DNG fell back to the platform
        // ImageDecoder. Drives a one-shot snackbar (a render path can't show UI directly).
        var dngFallbackNotice by remember { mutableStateOf(false) }

        // LUT export
        var bakingLut by remember { mutableStateOf(false) }
        var pendingLutText by remember { mutableStateOf<String?>(null) }

        // viewer modes
        var compareMode by remember { mutableStateOf(false) }
        var showHistogram by remember { mutableStateOf(false) }

        // interactive crop overlay (Lightroom-style); hosts on top of everything.
        var cropOverlayOpen by remember { mutableStateOf(false) }

        // 100% grain magnifier
        var magnifierOpen by remember { mutableStateOf(false) }
        var magnifierBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var magnifierRendering by remember { mutableStateOf(false) }
        var magnifierStatus by remember { mutableStateOf("") }
        // Tracks the in-flight magnifier render so a rapid re-tap can cancel the previous
        // request (last-tap-wins) instead of racing N full-res renders into magnifierBitmap.
        // Held in a remember box (not Compose state — we never read it during composition).
        val magnifierJobRef = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        // presets
        var presetList by remember { mutableStateOf<List<String>>(emptyList()) }
        var presetName by remember { mutableStateOf("") }
        var selectedPreset by remember { mutableStateOf("") }

        // active adjustment category (null = panel closed)
        var activeCategory by remember { mutableStateOf<Category?>(null) }

        fun refreshPresets() { presetList = Presets.list(ctx) }

        // --- non-destructive recipe (sidecar) layer ---
        var recipeReady by remember { mutableStateOf(false) }
        var hasRecipe by remember { mutableStateOf(false) }
        var defaultsJson by remember { mutableStateOf<String?>(null) }
        val snackbarHost = remember { SnackbarHostState() }
        val recipeKey = Recipes.keyFor(sourceUri)

        // --- double-back-to-exit on the root editor ---
        var backArmed by remember { mutableStateOf(false) }

        // --- in-session undo / redo edit history ---
        // A snapshot = the full preset/recipe JSON (Presets.toJsonString — covers every
        // ParamsState field incl. raw WB/temp/tint) PLUS the editor-local manual rotation.
        val editHistory = remember { EditHistory() }
        // The last SETTLED snapshot we committed. Capture coalescing pushes THIS (the
        // pre-edit state) onto undo when a newer settled state differs (see the capture
        // effect below), so one slider drag (which settles once) == one undo step.
        var committedSnapshot by remember { mutableStateOf<EditSnapshot?>(null) }
        // Set true while we are programmatically restoring a snapshot (undo/redo). The
        // capture effect skips one settle cycle so the restore is NOT recorded as a new
        // edit — without this the restored state would push itself and we'd never escape.
        var restoring by remember { mutableStateOf(false) }

        // Build a snapshot of the CURRENT live editing state (+ manual rotation).
        fun snapshotNow(): EditSnapshot =
            EditSnapshot(Presets.toJsonString(state), rotation.degrees)

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
            applySnapshot(target)
            status = "undo"
        }

        fun doRedo() {
            val target = editHistory.redo(snapshotNow()) ?: return
            applySnapshot(target)
            status = "redo"
        }

        // One-time engine init.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val dir = extractAssets(ctx)
                val e = SpektraEngine(dir.absolutePath)
                val list = runCatching { e.listProfiles() }.getOrDefault(emptyList())
                StockCatalog.stocks(ctx) // warm the catalog cache
                val presetGroups = runCatching { BuiltInPresets.grouped(ctx) }.getOrDefault(emptyMap())
                withContext(Dispatchers.Main) {
                    engine = e
                    profiles = list
                    settings.applyDefaultsTo(state, list)
                    if (list.isNotEmpty()) {
                        if (state.filmProfile !in list) state.filmProfile = list.first()
                        if (state.printProfile !in list) state.printProfile = list.first()
                    }
                    builtInGroups = presetGroups
                    catalogReady = true
                    refreshPresets()
                    status = "ready · ${list.size} profiles"
                    defaultsJson = Presets.toJsonString(state)
                    recipeReady = true
                    previewTick++
                }
            }
        }

        // --- Non-destructive recipe: restore-on-open ---
        var lastRestoredKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(recipeKey, recipeReady) {
            if (!recipeReady) return@LaunchedEffect
            if (recipeKey == null) {
                hasRecipe = false
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
            if (recipeKey == lastRestoredKey) return@LaunchedEffect
            lastRestoredKey = recipeKey
            // New source: undo must never cross images. Drop the history and re-baseline so
            // the just-restored/default state for THIS image is the empty-history baseline
            // (canUndo=false). `restoring` makes the next capture settle adopt the new
            // baseline without recording it as an edit.
            editHistory.clear()
            committedSnapshot = null
            restoring = true
            val restored = runCatching { Recipes.load(ctx, recipeKey, state) }.getOrDefault(false)
            hasRecipe = restored
            if (restored) {
                // Restore the persisted manual rotation (EXIF baseline is re-derived on load).
                rotation = runCatching { Recipes.loadRotation(ctx, recipeKey) }
                    .getOrDefault(SourceRotation.NONE)
                previewTick++
                snackbarHost.currentSnackbarData?.dismiss()
                snackbarHost.showSnackbar(
                    message = "Restored saved edit for this image",
                    withDismissAction = true,
                )
            } else {
                Recipes.resetToDefaults(state, settings, profiles)
                rotation = SourceRotation.NONE
                previewTick++
            }
        }

        // One-shot snackbar when a compressed DNG fell back to the system decoder.
        LaunchedEffect(dngFallbackNotice) {
            if (dngFallbackNotice) {
                snackbarHost.currentSnackbarData?.dismiss()
                snackbarHost.showSnackbar(
                    message = "DNG imported via system decoder (display-referred)",
                    withDismissAction = true,
                )
                dngFallbackNotice = false
            }
        }

        // --- source pickers ---
        val photoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                sourceUri = uri; sourceKind = SourceKind.PHOTO; sourceName = "picked photo"
                rotation = SourceRotation.NONE
                status = "photo selected"; previewTick++
            }
        }
        val rawPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                val name = uri.lastPathSegment ?: "raw"
                if (RawDecoder.isRawFileName(name) || true) {
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    sourceUri = uri; sourceKind = SourceKind.RAW
                    sourceName = "RAW: ${name.substringAfterLast('/')}"
                    rotation = SourceRotation.NONE
                    status = "RAW selected"; previewTick++
                }
            }
        }
        val presetImporter = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                runCatching { Presets.import(ctx, uri, state) }
                    .onSuccess { status = "preset imported"; previewTick++ }
                    .onFailure { status = "import failed: ${it.message}" }
            }
        }
        val presetExporter = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                runCatching { Presets.export(ctx, uri, state) }
                    .onSuccess { status = "preset exported" }
                    .onFailure { status = "export failed: ${it.message}" }
            }
        }
        val lutExporter = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("*/*")
        ) { uri ->
            val text = pendingLutText
            if (uri != null && text != null) {
                runCatching { saveTextToUri(ctx, uri, text) }
                    .onSuccess { status = "LUT saved" }
                    .onFailure { status = "LUT save failed: ${it.message}" }
            }
            pendingLutText = null
        }

        // Decode the current source to a LinearImage capped to [maxEdge], applying the
        // EXIF orientation baseline THEN the user's manual rotate steps so imports appear
        // upright in both the preview and the export. The demo image has no EXIF.
        //
        // RAW/DNG: a compressed (lossy-JPEG / JPEG-XL) Samsung/Pixel Expert-RAW DNG makes
        // LibRaw throw RawDecodeException; we fall back to the platform ImageDecoder
        // (display-referred) and flag a one-shot snackbar. EXIF is then applied to the
        // fallback bitmap too.
        suspend fun loadSource(maxEdge: Int): LinearImage = withContext(Dispatchers.IO) {
            val uri = sourceUri
            // EXIF baseline read once from the original source stream.
            val exif = if (uri != null && sourceKind != SourceKind.DEMO) {
                readExifOrientation(ctx, uri)
            } else {
                ExifOrientation.NONE
            }
            // applyExifBaseline guards the RAW double-rotation case: LibRaw already
            // uprights its linear output (it honours the DNG Orientation tag during
            // dcraw_process), so applying the file's EXIF on top would double-rotate.
            // The platform ImageDecoder fallback does NOT upright by default for DNG, so
            // the fallback path opts back in.
            var applyExifBaseline = sourceKind != SourceKind.RAW
            val img = when (sourceKind) {
                SourceKind.RAW -> try {
                    decodeRawToLinear(
                        ctx, uri!!, state.rawWhiteBalance,
                        state.rawTemperature.toDouble(), state.rawTint.toDouble(), maxEdge,
                    )
                } catch (e: RawDecodeException) {
                    when (e.status) {
                        DecodeStatus.LOSSY_JPEG_DNG,
                        DecodeStatus.FILE_UNSUPPORTED -> {
                            // Compressed Expert-RAW DNG: platform decoder fallback, which
                            // does NOT auto-upright DNGs -> apply the EXIF baseline.
                            dngFallbackNotice = true
                            applyExifBaseline = true
                            decodeViaPlatform(ctx, uri!!, maxEdge)
                        }
                        else -> throw e
                    }
                } catch (e: RuntimeException) {
                    // Defensive: any other native decode failure on a DNG/RAW still tries
                    // the platform decoder before giving up (e.g. an untyped error on a
                    // lossy DNG with no typed status).
                    dngFallbackNotice = true
                    applyExifBaseline = true
                    decodeViaPlatform(ctx, uri!!, maxEdge)
                }
                SourceKind.PHOTO -> decodeToLinearProPhoto(ctx, uri!!, maxEdge)
                SourceKind.DEMO -> syntheticLinearImage(256)
            }
            // EXIF baseline (when applicable) first, then the user's manual rotate steps.
            val based = if (applyExifBaseline) img.applyExif(exif) else img
            based.rotated(rotation)
        }

        // PERF: proxy-source loader used ONLY by the interactive preview path. Consults the
        // single-entry DecodedSourceCache keyed by the decode-affecting inputs (URI + kind +
        // RAW WB/temp/tint + manual rotation + target edge). On a hit we re-feed the SAME
        // cached LinearImage to the engine — proven safe because spk_simulate/_preview take
        // `const spk_image* in` and only read it (see DecodedSourceCache for the full proof),
        // so no defensive copy is required. On a miss we run the full loadSource() decode and
        // store the result, dropping any previous cached source (one entry only). When ONLY
        // look/film params change (the common case while editing sliders) the key is unchanged
        // and we skip the expensive LibRaw/bitmap decode + linearization + rotation entirely.
        // EXPORT and the 100% magnifier deliberately call loadSource() directly (full-res,
        // never cached).
        suspend fun loadSourceCachedForPreview(maxEdge: Int): LinearImage {
            val cached = sourceCache.get(
                uri = sourceUri?.toString(), kind = sourceKind.name,
                whiteBalance = state.rawWhiteBalance, temperature = state.rawTemperature,
                tint = state.rawTint, rotationDegrees = rotation.degrees, maxEdge = maxEdge,
            )
            if (cached != null) return cached
            val decoded = loadSource(maxEdge)
            sourceCache.put(
                uri = sourceUri?.toString(), kind = sourceKind.name,
                whiteBalance = state.rawWhiteBalance, temperature = state.rawTemperature,
                tint = state.rawTint, rotationDegrees = rotation.degrees, maxEdge = maxEdge,
                img = decoded,
            )
            return decoded
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
            val e = engine ?: return
            magnifierJobRef.value?.cancel()
            magnifierOpen = true
            magnifierBitmap = null
            magnifierRendering = true
            magnifierStatus = "rendering 100% crop…"
            magnifierJobRef.value = scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.Default) {
                        val full = loadSource(MAX_EDGE_PX)
                        val crop = cropLinearImage(full, nx, ny, MAGNIFIER_CROP_PX)
                        val res = e.simulate(crop, state.toParams())
                        simResultToBitmap(res.data, res.width, res.height)
                    }
                }
                if (!isActive) {
                    // Superseded by a newer tap (this job was cancelled): drop our render so
                    // it neither overwrites the latest request nor leaks an orphaned bitmap.
                    result.getOrNull()?.let { if (!it.isRecycled) it.recycle() }
                    return@launch
                }
                result.onSuccess {
                    magnifierBitmap = it
                    magnifierStatus = "${it.width}×${it.height}px · 1:1 full-res render"
                }.onFailure {
                    magnifierStatus = "crop render failed: ${it.message}"
                }
                magnifierRendering = false
            }
        }

        // Debounced preview render: re-runs whenever params, source or rotation change.
        LaunchedEffect(previewTick) {
            val e = engine ?: return@LaunchedEffect
            delay(350)
            previewBusy = true
            renderErr = null
            status = "rendering preview…"
            val renderStart = System.currentTimeMillis()
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    decoding = true
                    // Cached proxy source: re-decodes only when a decode-affecting key
                    // (URI/kind/WB/temp/tint/rotation/edge) changed; look-param edits reuse it.
                    val image = loadSourceCachedForPreview(state.previewMaxSize.coerceAtLeast(256))
                    decoding = false
                    val before = linearToDisplayBitmap(image)
                    val res = e.simulatePreview(image, state.toParams())
                    before to simResultToBitmap(res.data, res.width, res.height)
                }
            }
            decoding = false
            result.onSuccess { (before, after) ->
                beforePreview = before; preview = after
                lastRenderMs = System.currentTimeMillis() - renderStart
                renderErr = null
                status = "preview ready"
            }.onFailure {
                renderErr = it.message?.take(60)
                status = "preview error: ${it.message}"
            }
            previewBusy = false
        }

        // MEMORY (#2): `preview` and `beforePreview` are intentionally LEFT TO GC, not recycled
        // on swap. Recycling them is NOT provably safe: `preview` is consumed by HistogramCard,
        // which reads its pixels with getPixels() from a background coroutine
        // (LaunchedEffect(bitmap){ withContext(Dispatchers.Default){ computeHistogram(bitmap) } }).
        // computeHistogram is a tight, non-suspending loop, so when `preview` swaps mid-compute
        // the previous coroutine is NOT actually cancelled (cancellation is cooperative) and runs
        // to completion on the OLD bitmap. Recycling that bitmap on the recomposition swap would
        // race the still-running getPixels() -> "Can't call getPixels() on a recycled bitmap"
        // crash, and a check-then-recycle can't close that window without coordinating with the
        // histogram coroutine. Per the audit's correctness-over-aggressiveness rule these orphaned
        // ARGB_8888 bitmaps (incl. the full-res export bitmap, which becomes `preview`) are left
        // for the GC, which reclaims them promptly under the memory pressure that actually
        // matters. Only the magnifier crop — which has NO async reader — is recycled
        // deterministically (see the magnifier overlay's DisposableEffect below).

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
        LaunchedEffect(snapshot, sourceUri, sourceKind, rotation,
            state.rawWhiteBalance, state.rawTemperature, state.rawTint) { previewTick++ }

        // --- Non-destructive recipe: debounced auto-save ---
        LaunchedEffect(snapshot, recipeKey, recipeReady, defaultsJson, rotation,
            state.rawWhiteBalance, state.rawTemperature, state.rawTint) {
            if (!recipeReady || recipeKey == null) return@LaunchedEffect
            delay(700)
            val current = runCatching { Presets.toJsonString(state) }.getOrNull()
            // A non-NONE manual rotation is itself an edit worth persisting, even when the
            // params are otherwise default — so only treat as "pristine" if rotation is NONE.
            if (current != null && current == defaultsJson && rotation == SourceRotation.NONE) {
                if (Recipes.exists(ctx, recipeKey)) {
                    withContext(Dispatchers.IO) { Recipes.delete(ctx, recipeKey) }
                }
                hasRecipe = false
                return@LaunchedEffect
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    Recipes.save(ctx, recipeKey, state, sourceName, rotation.degrees)
                }
            }.onSuccess { hasRecipe = true }
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
        LaunchedEffect(snapshot, recipeKey, recipeReady, rotation,
            state.rawWhiteBalance, state.rawTemperature, state.rawTint) {
            if (!recipeReady) return@LaunchedEffect
            delay(500)
            val now = snapshotNow()
            when {
                restoring -> {
                    committedSnapshot = now
                    restoring = false
                }
                committedSnapshot == null -> committedSnapshot = now
                now != committedSnapshot -> {
                    editHistory.push(committedSnapshot!!)
                    committedSnapshot = now
                }
            }
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

        // --- back handling on the root editor ---
        // 0) crop overlay open -> close it; 1) panel open -> close panel;
        // 2) else double-back-to-exit with one-time hint.
        BackHandler(enabled = cropOverlayOpen) { cropOverlayOpen = false }
        BackHandler(enabled = !cropOverlayOpen && activeCategory != null) { activeCategory = null }
        BackHandler(enabled = !cropOverlayOpen && activeCategory == null) {
            if (backArmed) {
                finish()
            } else {
                backArmed = true
                scope.launch {
                    val firstTime = !BackHintStore.hasShown(ctx)
                    if (firstTime) {
                        BackHintStore.markShown(ctx)
                        snackbarHost.currentSnackbarData?.dismiss()
                        snackbarHost.showSnackbar("Press back again to exit")
                    }
                    delay(2000)
                    backArmed = false
                }
            }
        }

        // ============================ LAYOUT ============================
        Box(
            Modifier
                .fillMaxSize()
                .background(SpectraIcons.nearBlackCanvas),
        ) {
            Column(Modifier.fillMaxSize()) {
                // --- TOP BAR ---
                EditorTopBar(
                    canExport = engine != null && !previewBusy && !exporting,
                    exporting = exporting,
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
                    onExport = {
                        val e = engine ?: return@EditorTopBar
                        var fmt = settings.exportFormat
                        // Ultra HDR needs the API 34 Gainmap framework class; on older devices
                        // fall back to a standard JPEG rather than write a non-HDR file labelled
                        // HDR. Warn the user once via a toast.
                        if (fmt == ExportFormat.ULTRA_HDR && android.os.Build.VERSION.SDK_INT < 34) {
                            fmt = ExportFormat.JPEG
                            Toast.makeText(ctx, "Ultra HDR needs Android 14+ — saving JPEG instead", Toast.LENGTH_LONG).show()
                        }
                        val exportFmt = fmt
                        exporting = true; exportDone = false; status = "rendering full resolution…"
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.Default) {
                                    // Copy source EXIF (camera/lens/exposure/date) into the export;
                                    // GPS/location only when the user opted in (default OFF). Empty
                                    // for the synthetic demo image.
                                    val srcExif = withContext(Dispatchers.IO) { readSourceExif(ctx, sourceUri, keepGps = settings.exportKeepGps) }
                                    val image = loadSource(MAX_EDGE_PX)
                                    val res = e.simulate(image, state.toParams())
                                    val bmp = simResultToBitmap(res.data, res.width, res.height)
                                    val uri = withContext(Dispatchers.IO) {
                                        when (exportFmt) {
                                            ExportFormat.TIFF -> saveSimResultAsTiff(ctx, res)
                                            ExportFormat.PNG16 -> saveSimResultAsPng16(ctx, res)
                                            else -> saveToGallery(ctx, bmp, exportFmt, settings.exportQuality, srcExif)
                                        }
                                    }
                                    bmp to uri
                                }
                            }
                            result.onSuccess { (bmp, _) ->
                                preview = bmp; exportDone = true
                                status = "saved to Pictures/Spektrafilm"
                            }.onFailure {
                                exporting = false
                                status = "export failed: ${it.message}"
                                Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout,
                )

                // --- PREVIEW (pinned, weight) ---
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 220.dp),
                ) {
                    PreviewRegion(
                        preview = preview,
                        before = beforePreview,
                        busy = previewBusy,
                        decoding = decoding,
                        exporting = exporting,
                        lastRenderMs = lastRenderMs,
                        renderErr = renderErr,
                        compareMode = compareMode,
                        showHistogram = showHistogram,
                        onToggleCompare = { compareMode = !compareMode },
                        onToggleHistogram = { showHistogram = !showHistogram },
                        onRotate = { rotation = rotation.next() },
                        onEditCrop = { cropOverlayOpen = true },
                        onPointPicked = { nx, ny -> openMagnifier(nx, ny) },
                    )
                }

                // --- ADJUSTMENT PANEL (inline, animated) ---
                AnimatedVisibility(
                    visible = activeCategory != null,
                    enter = expandVertically(animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    )) + fadeIn(),
                    exit = shrinkVertically(animationSpec = spring(
                        stiffness = Spring.StiffnessMedium,
                    )) + fadeOut(),
                ) {
                    AdjustmentPanel(
                        category = activeCategory,
                        onDismiss = { activeCategory = null },
                    ) {
                        when (activeCategory) {
                            Category.INPUT -> InputSection(state, onEditCrop = { cropOverlayOpen = true })
                            Category.RAW_WB -> ImportRawSection(state, isRaw = sourceKind == SourceKind.RAW)
                            Category.SIMULATION -> SimulationSection(
                                s = state,
                                filmGroups = filmGroups,
                                printGroups = printGroups,
                                onOpenFilmCurves = {
                                    onOpenFilmCurves(
                                        state.filmProfile,
                                        StockCatalog.displayName(ctx, state.filmProfile),
                                    )
                                },
                                onOpenPrintCurves = {
                                    onOpenPrintCurves(
                                        state.printProfile,
                                        StockCatalog.displayName(ctx, state.printProfile),
                                    )
                                },
                            )
                            Category.GRAIN -> GrainSection(state)
                            Category.PREFLASH -> PreflashSection(state)
                            Category.HALATION -> HalationSection(state)
                            Category.COUPLERS -> CouplersSection(state)
                            Category.GLARE -> GlareSection(state)
                            Category.EXPERIMENTAL -> ExperimentalSection(state)
                            Category.DISPLAY -> DisplaySection(state)
                            Category.PRESETS -> PresetPanel(
                                builtInGroups = builtInGroups,
                                onApplyBuiltIn = { p ->
                                    BuiltInPresets.apply(p, state)
                                    status = "applied built-in '${p.name}'"; previewTick++
                                },
                                presets = presetList,
                                selected = selectedPreset,
                                name = presetName,
                                onNameChange = { presetName = it },
                                onSelect = { selectedPreset = it },
                                onSave = {
                                    if (presetName.isNotBlank()) {
                                        Presets.save(ctx, presetName, state); refreshPresets()
                                        status = "saved preset '${presetName}'"
                                    }
                                },
                                onApply = {
                                    if (selectedPreset.isNotBlank()) {
                                        runCatching { Presets.load(ctx, selectedPreset, state) }
                                            .onSuccess { status = "applied '${selectedPreset}'"; previewTick++ }
                                            .onFailure { status = "apply failed: ${it.message}" }
                                    }
                                },
                                onDelete = {
                                    if (selectedPreset.isNotBlank()) {
                                        Presets.delete(ctx, selectedPreset); refreshPresets()
                                        status = "deleted '${selectedPreset}'"; selectedPreset = ""
                                    }
                                },
                                onImport = { presetImporter.launch(arrayOf("application/json", "text/*", "*/*")) },
                                onExport = { presetExporter.launch("spectrafilm_preset.json") },
                                onExportLut = {
                                    val e = engine ?: return@PresetPanel
                                    bakingLut = true; status = "baking .cube LUT…"
                                    scope.launch {
                                        val r = runCatching {
                                            withContext(Dispatchers.Default) { e.bakeCubeLut(state.toParams(), 33) }
                                        }
                                        bakingLut = false
                                        r.onSuccess { cube ->
                                            pendingLutText = cube
                                            val fileName = cubeFileName(
                                                StockCatalog.displayName(ctx, state.filmProfile),
                                                StockCatalog.displayName(ctx, state.printProfile),
                                            )
                                            runCatching { lutExporter.launch(fileName) }
                                                .onFailure { status = "could not open save dialog: ${it.message}" }
                                        }.onFailure {
                                            status = "LUT bake failed: ${it.message}"
                                            Toast.makeText(ctx, "LUT bake failed: ${it.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                bakingLut = bakingLut,
                            )
                            Category.SOURCE -> SourcePanel(
                                sourceName = sourceName,
                                status = status,
                                showHistogram = showHistogram,
                                onToggleHistogram = { showHistogram = !showHistogram },
                                hasRecipe = hasRecipe,
                                onPickPhoto = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onOpenRaw = { rawPicker.launch(arrayOf("*/*")) },
                                onUseDemo = {
                                    sourceUri = null; sourceKind = SourceKind.DEMO
                                    sourceName = "synthetic demo image"; rotation = SourceRotation.NONE
                                    previewTick++
                                },
                                onResetEdits = {
                                    Recipes.delete(ctx, recipeKey)
                                    Recipes.resetToDefaults(state, settings, profiles)
                                    hasRecipe = false; rotation = SourceRotation.NONE
                                    // Reset clears history: the defaults become the new
                                    // empty-history baseline (you can't undo back across a
                                    // reset). `restoring` makes the next settle adopt it.
                                    editHistory.clear(); committedSnapshot = null; restoring = true
                                    status = "edits reset · recipe cleared"; previewTick++
                                    scope.launch {
                                        snackbarHost.currentSnackbarData?.dismiss()
                                        snackbarHost.showSnackbar("Edits reset; saved recipe cleared")
                                    }
                                },
                            )
                            null -> {}
                        }
                    }
                }

                // --- BOTTOM CATEGORY BAR ---
                CategoryBar(
                    active = activeCategory,
                    onSelect = { cat ->
                        activeCategory = if (activeCategory == cat) null else cat
                    },
                )
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
                    onClose = {
                        // Cancel any in-flight crop render so it doesn't resurrect the overlay
                        // state or leak its bitmap (a cancelled job recycles its own result).
                        magnifierJobRef.value?.cancel()
                        magnifierRendering = false
                        magnifierOpen = false
                        magnifierBitmap = null
                    },
                )
            }

            // --- interactive crop overlay (Lightroom-style) ---
            // Only shown once a preview bitmap exists (the crop tool needs the image
            // to draw and to read its aspect ratio).
            val cropBmp = preview
            if (cropOverlayOpen && cropBmp != null) {
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
                        cropOverlayOpen = false
                        previewTick++
                    },
                    onCancel = { cropOverlayOpen = false },
                )
            }

            // --- full-screen export mask ---
            if (exporting) {
                ExportMask(
                    done = exportDone,
                    onDismiss = { exporting = false; exportDone = false },
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
    private fun EditorTopBar(
        canExport: Boolean,
        exporting: Boolean,
        canUndo: Boolean,
        canRedo: Boolean,
        onUndo: () -> Unit,
        onRedo: () -> Unit,
        onOpenSource: () -> Unit,
        onExport: () -> Unit,
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
                TextTooltip("Open photo") {
                    PressIconButton(onClick = onOpenSource) {
                        Icon(
                            SpectraIcons.OpenPhoto, contentDescription = "Open photo",
                            tint = Color.White,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Spektrafilm",
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                // Undo / redo — in-session edit history. Disabled (dimmed) when the
                // respective stack is empty; one slider drag = one undo step.
                TextTooltip("Undo") {
                    PressIconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(
                            SpectraIcons.Undo, contentDescription = "Undo",
                            tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.4f),
                        )
                    }
                }
                TextTooltip("Redo") {
                    PressIconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(
                            SpectraIcons.Redo, contentDescription = "Redo",
                            tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.4f),
                        )
                    }
                }
                // Export / save
                TextTooltip("Export to gallery") {
                    PressIconButton(onClick = onExport, enabled = canExport) {
                        if (exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White,
                            )
                        } else {
                            Icon(
                                SpectraIcons.Presets, contentDescription = "Export to gallery",
                                tint = if (canExport) Color.White else Color.White.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
                TextTooltip("Settings") {
                    PressIconButton(onClick = onOpenSettings) {
                        Icon(SpectraIcons.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
                TextTooltip("About") {
                    PressIconButton(onClick = onOpenAbout) {
                        Icon(SpectraIcons.Help, contentDescription = "About", tint = Color.White)
                    }
                }
            }
        }
    }

    /** The pinned preview region on the near-black canvas with a rotate control. */
    @Composable
    private fun PreviewRegion(
        preview: Bitmap?,
        before: Bitmap?,
        busy: Boolean,
        decoding: Boolean,
        exporting: Boolean,
        lastRenderMs: Long?,
        renderErr: String?,
        compareMode: Boolean,
        showHistogram: Boolean,
        onToggleCompare: () -> Unit,
        onToggleHistogram: () -> Unit,
        onRotate: () -> Unit,
        onEditCrop: () -> Unit,
        onPointPicked: (Float, Float) -> Unit,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(SpectraIcons.nearBlackCanvas)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview
            if (bmp != null) {
                if (compareMode && before != null) {
                    CompareSlider(before = before, after = bmp, modifier = Modifier.fillMaxWidth())
                } else {
                    ZoomableImage(
                        bitmap = bmp,
                        modifier = Modifier.fillMaxSize(),
                        onPointPicked = onPointPicked,
                    )
                }
            } else {
                Text("No preview yet", color = Color.White.copy(alpha = 0.7f))
            }

            // Compact translucent histogram overlaid at the TOP EDGE of the preview
            // (Lightroom-style). Driven by the same `showHistogram` state as the
            // Source-panel toggle. computeHistogram runs on a bg coroutine inside
            // PreviewHistogramOverlay; it reads pixels from `bmp` which is the live
            // preview reference — the preview swap path replaces (not recycles) this
            // bitmap, so there is no recycle race here.
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
                    .padding(start = 6.dp, top = 6.dp),
            )

            // bottom-center controls on a scrim: histogram + compare + crop + rotate
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextTooltip("Histogram") {
                    CircleScrimButton(onClick = onToggleHistogram, active = showHistogram) {
                        Icon(
                            SpectraIcons.Histogram, contentDescription = "Toggle histogram",
                            tint = Color.White,
                        )
                    }
                }
                TextTooltip("Before / after compare") {
                    CircleScrimButton(onClick = onToggleCompare, active = compareMode) {
                        Icon(
                            SpectraIcons.Display, contentDescription = "Before / after compare",
                            tint = Color.White,
                        )
                    }
                }
                TextTooltip("Crop & transform") {
                    CircleScrimButton(onClick = onEditCrop) {
                        Icon(SpectraIcons.Crop, contentDescription = "Crop and transform", tint = Color.White)
                    }
                }
                TextTooltip("Rotate 90°") {
                    CircleScrimButton(onClick = onRotate) {
                        Icon(SpectraIcons.Rotate, contentDescription = "Rotate 90°", tint = Color.White)
                    }
                }
            }
        }
    }

    /**
     * A compact status pill overlaid in the top-start corner of the preview canvas.
     *
     * Priority: exporting > decoding > rendering > error > idle (last render time or "Ready").
     * Fades in when visible; the idle state auto-fades after 2 s using [animateFloatAsState].
     * Shows a small [CircularProgressIndicator] while busy.
     */
    @Composable
    private fun StatusPill(
        exporting: Boolean,
        rendering: Boolean,
        decoding: Boolean,
        lastRenderMs: Long?,
        renderErr: String?,
        modifier: Modifier = Modifier,
    ) {
        // Derive a single status from the priority chain.
        val pillState: PillState = when {
            exporting -> PillState.Busy("Exporting…")
            decoding  -> PillState.Busy("Decoding…")
            rendering -> PillState.Busy("Rendering…")
            renderErr != null -> PillState.Error(renderErr)
            lastRenderMs != null -> PillState.Done(lastRenderMs)
            else -> PillState.Hidden
        }

        // For the idle "Rendered in X ms" state we auto-fade after 2 s.
        var showIdle by remember { mutableStateOf(false) }
        LaunchedEffect(lastRenderMs, rendering, exporting, decoding, renderErr) {
            if (pillState is PillState.Done) {
                showIdle = true
                delay(2000)
                showIdle = false
            } else {
                showIdle = false
            }
        }

        val visible = pillState is PillState.Busy ||
            pillState is PillState.Error ||
            (pillState is PillState.Done && showIdle)

        val pillDesc = when (pillState) {
            is PillState.Busy  -> pillState.label
            is PillState.Error -> "Render error: ${pillState.message}"
            is PillState.Done  -> "Rendered in ${pillState.ms} ms"
            PillState.Hidden   -> ""
        }

        AnimatedVisibility(
            visible = visible,
            modifier = modifier,
            enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(180)),
            exit  = fadeOut(animationSpec = androidx.compose.animation.core.tween(400)),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
                modifier = Modifier
                    .wrapContentSize()
                    .semantics { contentDescription = pillDesc },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (pillState) {
                        is PillState.Busy -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.5.dp,
                                color = Color.White,
                            )
                            Text(
                                pillState.label,
                                fontSize = 12.sp,
                                color = Color.White,
                            )
                        }
                        is PillState.Error -> {
                            Text(
                                "Error: ${pillState.message}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.errorContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        is PillState.Done -> {
                            Text(
                                "Rendered in ${pillState.ms} ms",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                        PillState.Hidden -> { /* not shown */ }
                    }
                }
            }
        }
    }

    /** Internal sealed hierarchy for the status-pill priority logic. */
    private sealed interface PillState {
        data class Busy(val label: String) : PillState
        data class Error(val message: String) : PillState
        data class Done(val ms: Long) : PillState
        data object Hidden : PillState
    }

    /** 40dp circular control over a ~35% scrim. */
    @Composable
    private fun CircleScrimButton(
        onClick: () -> Unit,
        active: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Box(
            Modifier
                .size(40.dp)
                .scale(if (pressed) 0.9f else 1f)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else Color.Black.copy(alpha = 0.35f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onClick, interactionSource = interaction) { content() }
        }
    }

    /** A press-scaling icon button used in the top bar. */
    @Composable
    private fun PressIconButton(
        onClick: () -> Unit,
        enabled: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = Modifier.scale(if (pressed) 0.88f else 1f),
        ) { content() }
    }

    /** The inline adjustment panel: drag-handle header (swipe down to dismiss) + scrolling content. */
    @Composable
    private fun AdjustmentPanel(
        category: Category?,
        onDismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val maxH = (LocalConfigurationHeightDp() * 0.38f).dp
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxH),
            ) {
                // drag-handle header: swipe DOWN to dismiss; tap to dismiss too.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(category) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 18f) onDismiss()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                        )
                        Text(
                            category?.label ?: "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }

    /** Horizontally scrollable category bar with a sliding pill indicator. */
    @Composable
    private fun CategoryBar(
        active: Category?,
        onSelect: (Category) -> Unit,
    ) {
        val items = remember { Category.entries.toList() }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // ease the tapped/active category toward center
        LaunchedEffect(active) {
            val idx = active?.let { items.indexOf(it) } ?: return@LaunchedEffect
            scope.launch { listState.animateScrollToItem(idx.coerceAtLeast(0)) }
        }

        Surface(
            color = SpectraIcons.nearBlackCanvas,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                itemsIndexed(items) { _, cat ->
                    CategoryItem(
                        category = cat,
                        selected = cat == active,
                        onClick = { onSelect(cat) },
                    )
                }
            }
        }
    }

    @Composable
    private fun CategoryItem(
        category: Category,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        TextTooltip(categoryHint(category)) {
        Column(
            Modifier
                .width(72.dp)
                .scale(if (pressed) 0.92f else 1f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
                .clickableNoRipple(interaction, onClick)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = category.label,
                tint = if (selected) accent else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(24.dp),
            )
            Text(
                category.label,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) accent else Color.White.copy(alpha = 0.78f),
            )
            // sliding pill indicator
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(width = if (selected) 20.dp else 0.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) accent else Color.Transparent),
            )
        }
        }
    }

    /** One-line tooltip description for each editor category. */
    private fun categoryHint(c: Category) = when (c) {
        Category.SOURCE -> "Pick a photo / RAW, demo image, histogram & reset edits"
        Category.PRESETS -> "Built-in looks and your saved presets; import/export & LUT"
        Category.SIMULATION -> "Film stock, print paper, exposure & auto-exposure metering"
        Category.INPUT -> "Input colour space, spectral upsampling, filters, crop & upscale"
        Category.RAW_WB -> "RAW/DNG white balance (temperature & tint)"
        Category.GRAIN -> "Film grain structure, size and blur"
        Category.HALATION -> "Halation glow and in-emulsion light scatter"
        Category.GLARE -> "Print glare (stochastic; off by default)"
        Category.COUPLERS -> "DIR couplers — cross-channel inhibition & saturation"
        Category.PREFLASH -> "Enlarger pre-flash exposure and filtration"
        Category.EXPERIMENTAL -> "Film and print density-curve gamma factors"
        Category.DISPLAY -> "Output colour space, CCTF encoding and preview size"
    }

    private fun categoryIcon(c: Category) = when (c) {
        Category.SIMULATION -> SpectraIcons.Simulation
        Category.INPUT -> SpectraIcons.Input
        Category.RAW_WB -> SpectraIcons.ImportRaw
        Category.GRAIN -> SpectraIcons.Grain
        Category.PREFLASH -> SpectraIcons.Preflash
        Category.HALATION -> SpectraIcons.Halation
        Category.COUPLERS -> SpectraIcons.Couplers
        Category.GLARE -> SpectraIcons.Glare
        Category.EXPERIMENTAL -> SpectraIcons.Experimental
        Category.DISPLAY -> SpectraIcons.Display
        Category.PRESETS -> SpectraIcons.Presets
        Category.SOURCE -> SpectraIcons.SourceImage
    }

    // ---------------------------------------------------------------------------
    // Panels that wrap existing functionality (preset/source) for the new layout
    // ---------------------------------------------------------------------------

    @Composable
    private fun PresetPanel(
        builtInGroups: Map<String, List<BuiltInPreset>>,
        onApplyBuiltIn: (BuiltInPreset) -> Unit,
        presets: List<String>,
        selected: String,
        name: String,
        onNameChange: (String) -> Unit,
        onSelect: (String) -> Unit,
        onSave: () -> Unit,
        onApply: () -> Unit,
        onDelete: () -> Unit,
        onImport: () -> Unit,
        onExport: () -> Unit,
        onExportLut: () -> Unit,
        bakingLut: Boolean,
    ) {
        if (builtInGroups.isNotEmpty()) {
            Text("Built-in looks", style = MaterialTheme.typography.titleSmall)
            var selectedBuiltIn by remember {
                mutableStateOf(builtInGroups.values.firstOrNull()?.firstOrNull()?.id ?: "")
            }
            val all = remember(builtInGroups) { builtInGroups.values.flatten() }
            GroupedDropdown(
                label = "Built-in preset",
                selectedId = selectedBuiltIn,
                groups = builtInGroups.map { (g, ps) ->
                    DropdownGroup(g, ps.map { DropdownOption(it.id, it.name) })
                },
                onSelect = { selectedBuiltIn = it },
            )
            val current = all.firstOrNull { it.id == selectedBuiltIn }
            if (current?.description?.isNotBlank() == true) {
                Text(
                    current.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { current?.let(onApplyBuiltIn) },
                enabled = current != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply built-in preset") }
            Divider()
            Text("Your presets", style = MaterialTheme.typography.titleSmall)
        }

        OutlinedTextField(
            value = name, onValueChange = onNameChange,
            label = { Text("Preset name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save current as preset") }
        if (presets.isNotEmpty()) {
            Dropdown(
                label = "Saved presets",
                selected = selected.ifEmpty { presets.first() },
                options = presets,
                display = { it },
                onSelect = onSelect,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, modifier = Modifier.weight(1f)) { Text("Apply") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Delete") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import .json") }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export / share") }
        }
        Divider()
        Button(
            onClick = onExportLut,
            enabled = !bakingLut,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (bakingLut) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("Baking LUT…")
            } else {
                Text("Export LUT (.cube, 33³)")
            }
        }
        Text(
            "Bakes the current film + print look into a 33³ .cube 3D LUT. Spatial/stochastic " +
                "effects (grain, halation, diffusion, glare) can't be captured in a 3D LUT and " +
                "are omitted from the bake.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun SourcePanel(
        sourceName: String,
        status: String,
        showHistogram: Boolean,
        onToggleHistogram: () -> Unit,
        hasRecipe: Boolean,
        onPickPhoto: () -> Unit,
        onOpenRaw: () -> Unit,
        onUseDemo: () -> Unit,
        onResetEdits: () -> Unit,
    ) {
        Text("Source: $sourceName", style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickPhoto, modifier = Modifier.weight(1f)) { Text("Pick photo") }
            Button(onClick = onOpenRaw, modifier = Modifier.weight(1f)) { Text("Open RAW/DNG") }
        }
        OutlinedButton(onClick = onUseDemo, modifier = Modifier.fillMaxWidth()) { Text("Use demo image") }

        FilterChip(
            selected = showHistogram,
            onClick = onToggleHistogram,
            label = { Text("Histogram") },
        )
        Text(
            "Shows a live RGB histogram over the top of the preview.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (hasRecipe) {
            Text(
                "Edits auto-saved for this image (original never modified).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onResetEdits, modifier = Modifier.fillMaxWidth()) {
                Text("Reset edits / clear recipe")
            }
        }
        Text(
            "Tip: pinch to zoom · double-tap for 2x · tap a point for a 100% grain crop · " +
                "use the rotate button under the preview to rotate the image (preview + export).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Film modeling powered by spektrafilm (GPLv3). Preview is downscaled for " +
                "interactivity; export renders at full resolution.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun ExportMask(done: Boolean, onDismiss: () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!done) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        "Rendering at full resolution…",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        "Saved to gallery",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Pictures/Spektrafilm",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDismiss) { Text("View result") }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Parameter sections (spektrafilm GUI order/grouping) — preserved verbatim
    // ---------------------------------------------------------------------------

    @Composable
    private fun InputSection(s: ParamsState, onEditCrop: () -> Unit) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Input", expanded, { expanded = it }) {
            Dropdown("Input color space", s.inputColorSpace, INPUT_COLOR_SPACES, { it },
                { s.inputColorSpace = it })
            SwitchRow("Apply CCTF decoding", s.inputCctfDecoding, { s.inputCctfDecoding = it },
                "Apply the inverse cctf transfer function of the color space")
            Dropdown("Spectral upsampling", s.spectralUpsampling, Rgb2Raw.entries.toList(),
                { it.name.lowercase() }, { s.spectralUpsampling = it })
            GatedBlock("The hanatos2025 adaptation toggles and spectral Gaussian blur (spectral-blur) are not wired into the engine yet.") {
                SwitchRow("hanatos2025 adaptation window", s.adaptationWindow, { s.adaptationWindow = it },
                    "Apply the hanatos2025 bandpass adaptation window when reconstructing spectra.")
                SwitchRow("hanatos2025 adaptation surface", s.adaptationSurface, { s.adaptationSurface = it },
                    "Apply the hanatos2025 surface adaptation polynomial when reconstructing spectra.")
                EnhancedSlider("Spectral gaussian blur", s.spectralGaussianBlur, 0f..20f,
                    { s.spectralGaussianBlur = it }, step = 0.1f, decimals = 1,
                    tooltip = "Sigma in nm for Gaussian blur applied to reconstructed spectra.")
            }
            TripleSlider("UV filter", s.filterUv, 0f..800f, { s.filterUv = it }, step = 1f, decimals = 0,
                tooltip = "Filter UV light (amplitude, wavelength cutoff nm, sigma nm).",
                componentLabels = Triple("amp", "λ", "σ"))
            TripleSlider("IR filter", s.filterIr, 0f..800f, { s.filterIr = it }, step = 1f, decimals = 0,
                tooltip = "Filter IR light (amplitude, wavelength cutoff nm, sigma nm).",
                componentLabels = Triple("amp", "λ", "σ"))
            EnhancedSlider("Upscale factor", s.upscaleFactor, 0f..4f, { s.upscaleFactor = it },
                step = 0.5f, decimals = 1, tooltip = "Scale image size up to increase resolution")
            // Crop is now an interactive overlay (see the crop button under the
            // preview). Upscale stays a slider: it is a resolution multiplier, not
            // part of the crop rectangle, so it does not fit the crop metaphor.
            Divider()
            Text(
                if (s.crop) "Crop: enabled (use the crop tool to adjust)"
                else "Crop: off (full frame)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEditCrop, modifier = Modifier.weight(1f)) { Text("Edit crop") }
                if (s.crop) {
                    OutlinedButton(
                        onClick = {
                            s.crop = false
                            s.cropCenter = 0.5f to 0.5f
                            s.cropSize = 0.1f to 0.1f
                        },
                    ) { Text("Clear") }
                }
            }
        }
    }

    @Composable
    private fun ImportRawSection(s: ParamsState, isRaw: Boolean) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("RAW White Balance", expanded, { expanded = it }) {
            if (!isRaw) {
                Text(
                    "Load a RAW/DNG file (\"Open RAW/DNG\" in Source) to enable RAW white-balance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.fillMaxWidth().alpha(0.38f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Dropdown("White balance", s.rawWhiteBalance, WhiteBalance.entries.toList(),
                        { it.name.lowercase() }, { /* no-op when not RAW */ })
                    EnhancedSlider("Temperature (K)", s.rawTemperature, 1000f..12000f, { },
                        step = 100f, decimals = 0,
                        tooltip = "Temperature in Kelvin (active only for RAW files with Custom WB)")
                    EnhancedSlider("Tint", s.rawTint, 0f..2f, { },
                        step = 0.01f, decimals = 2,
                        tooltip = "Tint multiplier (active only for RAW files with Custom WB)")
                }
            } else {
                val customActive = s.rawWhiteBalance == WhiteBalance.CUSTOM
                Dropdown("White balance", s.rawWhiteBalance, WhiteBalance.entries.toList(),
                    { it.name.lowercase() }, { s.rawWhiteBalance = it })
                OutlinedButton(
                    onClick = {
                        s.rawWhiteBalance = WhiteBalance.AS_SHOT
                        s.rawTemperature = 5500f
                        s.rawTint = 1f
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset to camera / as-shot WB") }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (customActive) 1f else 0.45f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!customActive) {
                        Text(
                            "Temperature and Tint are used only when \"custom\" is selected above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EnhancedSlider(
                        "Temperature (K)", s.rawTemperature, 1000f..12000f,
                        { s.rawTemperature = it },
                        step = 100f, decimals = 0,
                        tooltip = "Colour temperature in Kelvin for Custom white balance (1000 K – 12000 K).",
                    )
                    EnhancedSlider(
                        "Tint", s.rawTint, 0f..2f,
                        { s.rawTint = it },
                        step = 0.01f, decimals = 2,
                        tooltip = "Green/magenta tint multiplier for Custom white balance (1.0 = neutral).",
                    )
                }
                Text(
                    "Changes re-decode the RAW file and update the preview automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun SimulationSection(
        s: ParamsState,
        filmGroups: List<DropdownGroup>,
        printGroups: List<DropdownGroup>,
        onOpenFilmCurves: () -> Unit,
        onOpenPrintCurves: () -> Unit,
    ) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Simulation", expanded, { expanded = it }) {
            GroupedDropdown(
                label = "Film profile",
                selectedId = s.filmProfile,
                groups = filmGroups,
                onSelect = { s.filmProfile = it },
            )
            OutlinedButton(
                onClick = onOpenFilmCurves,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("View film profile curves") }
            EnhancedSlider("Camera compensation EV", s.exposureCompensationEv, -10f..10f,
                { s.exposureCompensationEv = it }, step = 0.25f, decimals = 2,
                tooltip = "Add a bias to the auto-exposure of the camera")

            AutoExposureControl(
                autoExposure = s.autoExposure,
                autoExposureMethod = s.autoExposureMethod,
                methods = AUTO_EXPOSURE_METHODS,
                onAutoExposureChange = { s.autoExposure = it },
                onMethodChange = { s.autoExposureMethod = it },
            )
            EnhancedSlider("Film format mm", s.filmFormatMm, 8f..120f, { s.filmFormatMm = it },
                step = 1f, decimals = 0,
                tooltip = "Long edge of the film format in mm (8, 16, 35, 60, 120)")
            EnhancedSlider("Camera lens blur um", s.cameraLensBlurUm, 0f..20f, { s.cameraLensBlurUm = it },
                step = 0.05f, decimals = 2,
                tooltip = "Sigma of gaussian filter in um for the camera lens blur. " +
                    "Spatial effect — applied only when Halation is enabled (the spatial branch).")
            DiffusionGroup("Camera diffusion filter", s.cameraDiffusionState)

            Divider()
            GroupedDropdown(
                label = "Print profile",
                selectedId = s.printProfile,
                groups = printGroups,
                onSelect = { s.printProfile = it },
            )
            OutlinedButton(
                onClick = onOpenPrintCurves,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("View print profile curves") }
            EnhancedSlider("Print exposure", s.printExposure, 0f..4f, { s.printExposure = it },
                step = 0.02f, decimals = 2, tooltip = "Changes the exposure time set in the virtual enlarger")
            SwitchRow("Print auto compensation", s.printExposureCompensation,
                { s.printExposureCompensation = it },
                "Auto adjust the print exposure for the camera exposure compensation ev")
            EnhancedSlider("Print Y filter shift", s.printYFilterShift, -50f..50f, { s.printYFilterShift = it },
                step = 1f, decimals = 0, tooltip = "Y filter shift from neutral, in Kodak CC units")
            EnhancedSlider("Print M filter shift", s.printMFilterShift, -50f..50f, { s.printMFilterShift = it },
                step = 1f, decimals = 0, tooltip = "M filter shift from neutral, in Kodak CC units")
            GatedBlock("Enlarger lens blur is not applicable to the enlarger stage (no engine call site).") {
                EnhancedSlider("Enlarger lens blur", s.enlargerLensBlur, 0f..20f, { s.enlargerLensBlur = it },
                    step = 0.05f, decimals = 2, tooltip = "Sigma of gaussian filter for the enlarger lens blur")
            }
            DiffusionGroup("Print diffusion filter", s.printDiffusionState)

            Divider()
            Text("Scanner", style = MaterialTheme.typography.titleSmall)
            EnhancedSlider("Scan lens blur", s.scanLensBlur, 0f..20f, { s.scanLensBlur = it },
                step = 0.05f, decimals = 2,
                tooltip = "Sigma of gaussian filter in pixel for the scanner lens blur. " +
                    "Spatial effect — applied only when Halation is enabled (the spatial branch).")
            SwitchRow("Scan white correction", s.scanWhiteCorrection, { s.scanWhiteCorrection = it },
                "Enable white point correction applied to the scanner output")
            EnhancedSlider("Scan white level", s.scanWhiteLevel, 0f..1f, { s.scanWhiteLevel = it },
                step = 0.005f, decimals = 3, tooltip = "Target white level when white correction is enabled")
            SwitchRow("Scan black correction", s.scanBlackCorrection, { s.scanBlackCorrection = it },
                "Enable black point correction applied to the scanner output")
            EnhancedSlider("Scan black level", s.scanBlackLevel, 0f..1f, { s.scanBlackLevel = it },
                step = 0.005f, decimals = 3, tooltip = "Target black level when black correction is enabled")
            PairSlider("Scan unsharp mask", s.scanUnsharpMask, 0f..5f, { s.scanUnsharpMask = it },
                step = 0.05f, decimals = 2, tooltip = "[sigma in pixel, amount]",
                componentLabels = "σ" to "amt")

            Divider()
            Text("Output", style = MaterialTheme.typography.titleSmall)
            Dropdown("Output color space", s.outputColorSpace, ColorSpace.entries.toList(),
                { it.name }, { s.outputColorSpace = it })
            SwitchRow("Saving CCTF encoding", s.savingCctfEncoding, { s.savingCctfEncoding = it },
                "Add or not the CCTF to the saved image file")
            SwitchRow("Scan film (skip print)", s.scanFilm, { s.scanFilm = it },
                "Show a scan of the negative instead of the print")
        }
    }

    @Composable
    private fun DiffusionGroup(title: String, d: DiffusionState) {
        var expanded by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(title, d.active, { d.active = it },
                "Toggle the diffusion filter on this stage.")
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide diffusion details" else "Show diffusion details")
            }
            if (expanded) {
                Dropdown("Diffusion family", d.family, DIFFUSION_FAMILIES, { it }, { d.family = it })
                EnhancedSlider("Diffusion strength", d.strength, 0f..2f, { d.strength = it },
                    step = 0.125f, decimals = 3, tooltip = "Commercial filter stop: 0, 1/8, 1/4, 1/2, 1, 2.")
                EnhancedSlider("Spatial scale", d.spatialScale, 0f..4f, { d.spatialScale = it },
                    step = 0.1f, decimals = 2, tooltip = "Multiplier on the image-plane PSF widths.")
                EnhancedSlider("Halo warmth", d.haloWarmth, -1.5f..1.5f, { d.haloWarmth = it },
                    step = 0.05f, decimals = 2, tooltip = "Additive offset on the halo warmth axis.")
                EnhancedSlider("Core intensity", d.coreIntensity, 0f..4f, { d.coreIntensity = it },
                    step = 0.05f, decimals = 2)
                EnhancedSlider("Core size", d.coreSize, 0.1f..4f, { d.coreSize = it }, step = 0.05f, decimals = 2)
                EnhancedSlider("Halo intensity", d.haloIntensity, 0f..4f, { d.haloIntensity = it },
                    step = 0.05f, decimals = 2)
                EnhancedSlider("Halo size", d.haloSize, 0.1f..4f, { d.haloSize = it }, step = 0.05f, decimals = 2)
                EnhancedSlider("Bloom intensity", d.bloomIntensity, 0f..4f, { d.bloomIntensity = it },
                    step = 0.05f, decimals = 2)
                EnhancedSlider("Bloom size", d.bloomSize, 0.1f..4f, { d.bloomSize = it }, step = 0.05f, decimals = 2)
            }
        }
    }

    @Composable
    private fun GrainSection(s: ParamsState) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Grain", expanded, { expanded = it }, enabledSwitch = s.grainActive,
            onEnabledChange = { s.grainActive = it }) {
            SwitchRow("Sublayers active", s.grainSublayersActive, { s.grainSublayersActive = it })
            EnhancedSlider("Particle area um2", s.grainParticleAreaUm2, 0f..2f, { s.grainParticleAreaUm2 = it },
                step = 0.2f, decimals = 2, tooltip = "Area of particles in um2, relates to ISO.")
            TripleSlider("Particle scale", s.grainParticleScale, 0f..5f, { s.grainParticleScale = it },
                step = 0.1f, decimals = 2, tooltip = "Scale of particle area for the RGB layers.")
            TripleSlider("Particle scale layers", s.grainParticleScaleLayers, 0f..5f,
                { s.grainParticleScaleLayers = it }, step = 0.25f, decimals = 2,
                tooltip = "Scale of particle area for the sublayers in each color layer.")
            TripleSlider("Density min", s.grainDensityMin, 0f..0.5f, { s.grainDensityMin = it },
                step = 0.01f, decimals = 3, tooltip = "Minimum density of the grain (0.03-0.06).")
            TripleSlider("Uniformity", s.grainUniformity, 0.5f..1f, { s.grainUniformity = it },
                step = 0.005f, decimals = 3, tooltip = "Uniformity of the grain (0.94-0.98).")
            EnhancedSlider("Blur", s.grainBlur, 0f..3f, { s.grainBlur = it }, step = 0.05f, decimals = 2,
                tooltip = "Sigma of gaussian blur in pixels for the grain.")
            EnhancedSlider("Blur dye clouds um", s.grainBlurDyeCloudsUm, 0f..5f, { s.grainBlurDyeCloudsUm = it },
                step = 0.1f, decimals = 2, tooltip = "Scale the sigma of gaussian blur in um for the dye clouds.")
            PairSlider("Micro structure", s.grainMicroStructure, 0f..100f, { s.grainMicroStructure = it },
                step = 0.1f, decimals = 2, tooltip = "[sigma blur um, molecular clump size nm]",
                componentLabels = "σ" to "nm")
            IntSlider("Sublayers", s.grainNSubLayers, 1..5, { s.grainNSubLayers = it })
        }
    }

    @Composable
    private fun PreflashSection(s: ParamsState) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Preflash", expanded, { expanded = it }) {
            EnhancedSlider("Exposure", s.preflashExposure, 0f..2f, { s.preflashExposure = it },
                step = 0.005f, decimals = 3, tooltip = "Preflash exposure value in ev for the print")
            EnhancedSlider("Y filter shift", s.preflashYFilterShift, -20f..20f, { s.preflashYFilterShift = it },
                step = 1f, decimals = 0, tooltip = "Shift the Y filter from neutral for the preflash (Kodak CC)")
            EnhancedSlider("M filter shift", s.preflashMFilterShift, -20f..20f, { s.preflashMFilterShift = it },
                step = 1f, decimals = 0, tooltip = "Shift the M filter from neutral for the preflash (Kodak CC)")
        }
    }

    @Composable
    private fun HalationSection(s: ParamsState) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Halation", expanded, { expanded = it }, enabledSwitch = s.halationActive,
            onEnabledChange = { s.halationActive = it }) {
            EnhancedSlider("Scatter amount", s.halScatterAmount, 0f..4f, { s.halScatterAmount = it },
                step = 0.05f, decimals = 2, tooltip = "High-level scatter strength. 1.0 = full physical scatter.")
            EnhancedSlider("Scatter spatial scale", s.halScatterSpatialScale, 0f..4f,
                { s.halScatterSpatialScale = it }, step = 0.1f, decimals = 2,
                tooltip = "High-level scatter size multiplier (1.0 = physical defaults).")
            EnhancedSlider("Halation amount", s.halHalationAmount, 0f..4f, { s.halHalationAmount = it },
                step = 0.05f, decimals = 2, tooltip = "High-level halation strength multiplier.")
            EnhancedSlider("Halation spatial scale", s.halHalationSpatialScale, 0f..4f,
                { s.halHalationSpatialScale = it }, step = 0.1f, decimals = 2,
                tooltip = "High-level halation size multiplier.")
            EnhancedSlider("Boost EV", s.halBoostEv, 0f..6f, { s.halBoostEv = it }, step = 0.5f, decimals = 1,
                tooltip = "Maximum highlight boost in stops.")
            EnhancedSlider("Protect EV", s.halProtectEv, 0f..10f, { s.halProtectEv = it }, step = 0.5f, decimals = 1,
                tooltip = "Protected range above midgray for the boost onset in stops.")
            EnhancedSlider("Boost range", s.halBoostRange, 0f..1f, { s.halBoostRange = it },
                step = 0.05f, decimals = 2, tooltip = "How quickly the highlight boost ramps in (0-1).")
            TripleSlider("Scatter core um", s.halScatterCoreUm, 0f..20f, { s.halScatterCoreUm = it },
                step = 0.5f, decimals = 2, tooltip = "Sigma of the scatter core Gaussian per channel, in um.")
            TripleSlider("Scatter tail um", s.halScatterTailUm, 0f..40f, { s.halScatterTailUm = it },
                step = 1f, decimals = 1, tooltip = "Decay constant of the scatter exponential tail per channel, in um.")
            TripleSlider("Scatter tail weight %", s.halScatterTailWeightPct, 0f..100f,
                { s.halScatterTailWeightPct = it }, step = 1f, decimals = 1,
                tooltip = "Weight of the scatter tail Gaussian per channel (0-100%).")
            TripleSlider("Halation strength %", s.halHalationStrengthPct, 0f..100f,
                { s.halHalationStrengthPct = it }, step = 0.5f, decimals = 2,
                tooltip = "Total back-reflection halation amplitude per channel (0-100%).")
            TripleSlider("First sigma um", s.halFirstSigmaUm, 0f..200f, { s.halFirstSigmaUm = it },
                step = 1f, decimals = 1, tooltip = "Sigma of the first halation bounce per channel, in um.")
            IntSlider("N bounces", s.halNBounces, 1..5, { s.halNBounces = it },
                tooltip = "Number of multi-bounce Gaussians summed (typical 2-3).")
            EnhancedSlider("Bounce decay", s.halBounceDecay, 0f..1f, { s.halBounceDecay = it },
                step = 0.05f, decimals = 2, tooltip = "Per-bounce amplitude decay ratio (0.3-0.7).")
            SwitchRow("Renormalize", s.halRenormalize, { s.halRenormalize = it },
                "Divide by (1 + sum of bounce amplitudes) so mid-grey is preserved.")
        }
    }

    @Composable
    private fun CouplersSection(s: ParamsState) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Couplers", expanded, { expanded = it }, enabledSwitch = s.couplersActive,
            onEnabledChange = { s.couplersActive = it }) {
            EnhancedSlider("Amount", s.couplersAmount, 0f..4f, { s.couplersAmount = it },
                step = 0.05f, decimals = 2, tooltip = "Global multiplier on the DIR coupler inhibition matrix.")
            EnhancedSlider("Inhibition samelayer", s.couplersInhibitionSamelayer, 0f..4f,
                { s.couplersInhibitionSamelayer = it }, step = 0.05f, decimals = 2,
                tooltip = "Multiplier on the same-layer (diagonal) inhibition.")
            EnhancedSlider("Inhibition interlayer", s.couplersInhibitionInterlayer, 0f..4f,
                { s.couplersInhibitionInterlayer = it }, step = 0.05f, decimals = 2,
                tooltip = "Multiplier on the cross-layer (off-diagonal) inhibition.")
            TripleSlider("Gamma samelayer RGB", s.couplersGammaSamelayer, 0f..2f, { s.couplersGammaSamelayer = it },
                step = 0.02f, decimals = 3, tooltip = "Per-channel same-layer DIR gamma (R, G, B).")
            PairSlider("Gamma R→GB", s.couplersGammaRtoGb, 0f..2f, { s.couplersGammaRtoGb = it },
                step = 0.02f, decimals = 3, tooltip = "DIR inhibition from R onto G and B.",
                componentLabels = "→G" to "→B")
            PairSlider("Gamma G→RB", s.couplersGammaGtoRb, 0f..2f, { s.couplersGammaGtoRb = it },
                step = 0.02f, decimals = 3, tooltip = "DIR inhibition from G onto R and B.",
                componentLabels = "→R" to "→B")
            PairSlider("Gamma B→RG", s.couplersGammaBtoRg, 0f..2f, { s.couplersGammaBtoRg = it },
                step = 0.02f, decimals = 3, tooltip = "DIR inhibition from B onto R and G.",
                componentLabels = "→R" to "→G")
            EnhancedSlider("Diffusion size um", s.couplersDiffusionSizeUm, 0f..100f, { s.couplersDiffusionSizeUm = it },
                step = 5f, decimals = 1, tooltip = "Sigma in um for the diffusion of the couplers (5-20 um).")
            EnhancedSlider("Diffusion tail um", s.couplersDiffusionTailUm, 0f..500f, { s.couplersDiffusionTailUm = it },
                step = 5f, decimals = 1)
            EnhancedSlider("Diffusion tail weight", s.couplersDiffusionTailWeight, 0f..1f,
                { s.couplersDiffusionTailWeight = it }, step = 0.01f, decimals = 3)
        }
    }

    @Composable
    private fun GlareSection(s: ParamsState) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Glare", expanded, { expanded = it }, enabledSwitch = s.glareActive,
            onEnabledChange = { s.glareActive = it }) {
            EnhancedSlider("Percent", s.glarePercent, 0f..1f, { s.glarePercent = it },
                step = 0.01f, decimals = 2, tooltip = "Percentage of the glare light (typically 0.1-0.25)")
            EnhancedSlider("Roughness", s.glareRoughness, 0f..1f, { s.glareRoughness = it },
                step = 0.05f, decimals = 2, tooltip = "Roughness of the glare light (0-1)")
            EnhancedSlider("Blur", s.glareBlur, 0f..10f, { s.glareBlur = it }, step = 0.1f, decimals = 2,
                tooltip = "Sigma of gaussian blur in pixels for the glare")
        }
    }

    @Composable
    private fun ExperimentalSection(s: ParamsState) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Experimental", expanded, { expanded = it }) {
            EnhancedSlider("Film gamma factor", s.filmGammaFactor, 0f..3f, { s.filmGammaFactor = it },
                step = 0.05f, decimals = 2, tooltip = "Gamma factor of the negative density curves.")
            EnhancedSlider("Print gamma factor", s.printGammaFactor, 0f..3f, { s.printGammaFactor = it },
                step = 0.05f, decimals = 2, tooltip = "Gamma factor of the print paper.")
        }
    }

    @Composable
    private fun DisplaySection(s: ParamsState) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Display", expanded, { expanded = it }) {
            IntSlider("Preview max size", s.previewMaxSize, 128..1024, { s.previewMaxSize = it },
                tooltip = "Max size of the long edge of the preview image, in pixels.")
        }
    }

    @Composable
    private fun Divider() {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

/** Small helpers kept at file scope. */
@Composable
private fun LocalConfigurationHeightDp(): Int =
    androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

private fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.then(
    Modifier.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
)
