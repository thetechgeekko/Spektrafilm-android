/*
 * SpectraFilm for Android — app entry. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Polished tool UI: a live preview at the top, a full parameter panel mirroring the
 * spektrafilm desktop GUI (Input, Import Raw, Simulation incl. camera/enlarger/print/
 * scanner/output, Grain, Preflash, Halation, Couplers, Glare, Experimental, Display),
 * styled after Image Toolbox with collapsible cards and enhanced sliders. Edits rebuild
 * an immutable SpektraParams and trigger a debounced, downscaled preview render. Export
 * runs a full-resolution render behind a blocking full-screen mask, then saves to the
 * gallery. RAW/DNG import (LibRaw -> ACES2065-1), the sRGB photo picker, and the synthetic
 * demo image are all supported, plus named JSON presets (save/apply/delete/import/export).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.Rgb2Raw
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.libraw.RawDecoder
import com.spectrafilm.libraw.WhiteBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which kind of source image is loaded. */
private enum class SourceKind { DEMO, PHOTO, RAW }

/** Top-level navigation destinations. */
private enum class Screen { EDITOR, SETTINGS, ABOUT, CURVES_FILM, CURVES_PRINT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(settings = settings, onThemeChanged = { themeMode = it })
                }
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

        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.EDITOR -> Screen(
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

    /** A simple back-arrow top bar wrapping a sub-screen. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NavScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
            Box(Modifier.weight(1f)) { content() }
        }
    }

    @Composable
    private fun Screen(
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
        var exporting by remember { mutableStateOf(false) }
        var exportDone by remember { mutableStateOf(false) }
        var previewTick by remember { mutableIntStateOf(0) }

        // LUT export
        var bakingLut by remember { mutableStateOf(false) }
        var pendingLutText by remember { mutableStateOf<String?>(null) }

        // viewer modes
        var compareMode by remember { mutableStateOf(false) }
        var showHistogram by remember { mutableStateOf(true) }

        // 100% grain magnifier
        var magnifierOpen by remember { mutableStateOf(false) }
        var magnifierBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var magnifierRendering by remember { mutableStateOf(false) }
        var magnifierStatus by remember { mutableStateOf("") }

        // presets
        var presetList by remember { mutableStateOf<List<String>>(emptyList()) }
        var presetName by remember { mutableStateOf("") }
        var selectedPreset by remember { mutableStateOf("") }

        fun refreshPresets() { presetList = Presets.list(ctx) }

        // --- non-destructive recipe (sidecar) layer ---
        // True once the engine + persisted defaults are applied, so the initial
        // default-load doesn't get auto-saved as a recipe before a source is chosen.
        var recipeReady by remember { mutableStateOf(false) }
        // Whether the currently loaded source has a saved recipe bound to it.
        var hasRecipe by remember { mutableStateOf(false) }
        // Serialized baseline of the default (post-app-defaults) editing state. Auto-save
        // only writes a recipe when the current edit differs from this, so untouched
        // images never get a sidecar and "Reset edits / clear recipe" stays cleared.
        var defaultsJson by remember { mutableStateOf<String?>(null) }
        val snackbarHost = remember { SnackbarHostState() }
        val recipeKey = Recipes.keyFor(sourceUri)

        // One-time engine init.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val dir = extractAssets(ctx)
                val e = SpektraEngine(dir.absolutePath)
                val list = runCatching { e.listProfiles() }.getOrDefault(emptyList())
                // parse bundled data off the main thread
                StockCatalog.stocks(ctx) // warm the catalog cache
                val presetGroups = runCatching { BuiltInPresets.grouped(ctx) }.getOrDefault(emptyMap())
                withContext(Dispatchers.Main) {
                    engine = e
                    profiles = list

                    // Apply persisted app defaults (output space / preview size / profiles).
                    settings.applyDefaultsTo(state, list)
                    if (list.isNotEmpty()) {
                        if (state.filmProfile !in list) state.filmProfile = list.first()
                        if (state.printProfile !in list) state.printProfile = list.first()
                    }
                    builtInGroups = presetGroups
                    catalogReady = true
                    refreshPresets()
                    status = "ready · ${list.size} profiles"
                    // Capture the default editing state as the auto-save baseline.
                    defaultsJson = Presets.toJsonString(state)
                    recipeReady = true
                    previewTick++
                }
            }
        }

        // --- Non-destructive recipe: restore-on-open ---
        // When the source changes to a keyed (persistable) image: if a recipe is saved
        // for its key, load it so the edit is reproduced; otherwise reset to defaults so
        // one image's edits never bleed onto the next. Keyed on the resolved recipeKey so
        // it fires once per distinct source. The first run (initial demo source, key null)
        // is a no-op, preserving the unchanged default-launch behavior.
        var lastRestoredKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(recipeKey, recipeReady) {
            if (!recipeReady) return@LaunchedEffect
            if (recipeKey == null) { hasRecipe = false; return@LaunchedEffect }
            if (recipeKey == lastRestoredKey) return@LaunchedEffect
            lastRestoredKey = recipeKey
            val restored = runCatching { Recipes.load(ctx, recipeKey, state) }.getOrDefault(false)
            hasRecipe = restored
            if (restored) {
                previewTick++
                snackbarHost.currentSnackbarData?.dismiss()
                snackbarHost.showSnackbar(
                    message = "Restored saved edit for this image",
                    withDismissAction = true,
                )
            } else {
                // No recipe for this source — start from defaults (don't inherit prior edits).
                Recipes.resetToDefaults(state, settings, profiles)
                previewTick++
            }
        }

        // --- source pickers ---
        val photoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                sourceUri = uri; sourceKind = SourceKind.PHOTO; sourceName = "picked photo"
                status = "photo selected"; previewTick++
            }
        }
        val rawPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                val name = uri.lastPathSegment ?: "raw"
                if (RawDecoder.isRawFileName(name) || true) {
                    // Persist read access so the same Uri string re-binds its recipe
                    // across app sessions (OpenDocument grants are persistable).
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    sourceUri = uri; sourceKind = SourceKind.RAW
                    sourceName = "RAW: ${name.substringAfterLast('/')}"
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
        // .cube LUT writer: receives the SAF target and writes the already-baked text.
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

        // Decode the current source to a LinearImage capped to [maxEdge].
        suspend fun loadSource(maxEdge: Int): LinearImage = withContext(Dispatchers.IO) {
            when (sourceKind) {
                SourceKind.RAW -> decodeRawToLinear(
                    ctx, sourceUri!!, state.rawWhiteBalance,
                    state.rawTemperature.toDouble(), state.rawTint.toDouble(), maxEdge,
                )
                SourceKind.PHOTO -> decodeToLinearProPhoto(ctx, sourceUri!!, maxEdge)
                SourceKind.DEMO -> syntheticLinearImage(256)
            }
        }

        // 100% grain magnifier: render a real FULL-RESOLUTION crop around a tapped point.
        // We load the source at full native resolution, slice a ~512px window centred on the
        // point (no resampling), and run the full simulate() on that small image — so the
        // dye-cloud grain resolves at 1:1 instead of being an upscale of the preview.
        fun openMagnifier(nx: Float, ny: Float) {
            val e = engine ?: return
            magnifierOpen = true
            magnifierBitmap = null
            magnifierRendering = true
            magnifierStatus = "rendering 100% crop…"
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.Default) {
                        val full = loadSource(MAX_EDGE_PX)
                        val crop = cropLinearImage(full, nx, ny, MAGNIFIER_CROP_PX)
                        val res = e.simulate(crop, state.toParams())
                        simResultToBitmap(res.data, res.width, res.height)
                    }
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

        // Debounced preview render: re-runs whenever params or source change.
        LaunchedEffect(previewTick) {
            val e = engine ?: return@LaunchedEffect
            delay(350) // debounce rapid edits
            previewBusy = true
            status = "rendering preview…"
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val image = loadSource(state.previewMaxSize.coerceAtLeast(256))
                    val before = linearToDisplayBitmap(image)
                    val res = e.simulatePreview(image, state.toParams())
                    before to simResultToBitmap(res.data, res.width, res.height)
                }
            }
            result.onSuccess { (before, after) ->
                beforePreview = before; preview = after; status = "preview ready"
            }.onFailure { status = "preview error: ${it.message}" }
            previewBusy = false
        }

        // re-trigger preview on any change to the params snapshot.
        // Raw WB fields (rawWhiteBalance, rawTemperature, rawTint) are pre-decode params;
        // they're not in SpektraParams/toParams(), so include them explicitly here so that
        // moving the RAW WB sliders re-decodes and re-renders the preview.
        val snapshot = state.toParams()
        LaunchedEffect(snapshot, sourceUri, sourceKind,
            state.rawWhiteBalance, state.rawTemperature, state.rawTint) { previewTick++ }

        // --- Non-destructive recipe: debounced auto-save ---
        // Whenever the edit changes and a persistable source is loaded, write/update
        // the sidecar recipe to app-private storage. Debounced so dragging a slider
        // doesn't thrash the disk. The original image is never written. The RAW WB
        // fields aren't in SpektraParams, so they're included as keys explicitly.
        LaunchedEffect(snapshot, recipeKey, recipeReady, defaultsJson,
            state.rawWhiteBalance, state.rawTemperature, state.rawTint) {
            if (!recipeReady || recipeKey == null) return@LaunchedEffect
            delay(700) // debounce edits
            val current = runCatching { Presets.toJsonString(state) }.getOrNull()
            if (current != null && current == defaultsJson) {
                // State equals defaults — no meaningful edit. Don't create a sidecar;
                // drop any stale one so reverting to defaults clears the recipe too.
                if (Recipes.exists(ctx, recipeKey)) {
                    withContext(Dispatchers.IO) { Recipes.delete(ctx, recipeKey) }
                }
                hasRecipe = false
                return@LaunchedEffect
            }
            runCatching {
                withContext(Dispatchers.IO) { Recipes.save(ctx, recipeKey, state, sourceName) }
            }.onSuccess { hasRecipe = true }
        }

        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SpectraFilm",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                    TextButton(onClick = onOpenAbout) { Text("About") }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)

                PreviewPane(
                    preview = preview,
                    before = beforePreview,
                    busy = previewBusy,
                    compareMode = compareMode,
                    showHistogram = showHistogram,
                    onToggleCompare = { compareMode = !compareMode },
                    onToggleHistogram = { showHistogram = !showHistogram },
                    onPointPicked = { nx, ny -> openMagnifier(nx, ny) },
                    onCropCenter = { openMagnifier(0.5f, 0.5f) },
                )

                SourceCard(
                    sourceName = sourceName,
                    onPickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onOpenRaw = { rawPicker.launch(arrayOf("*/*")) },
                    onUseDemo = {
                        sourceUri = null; sourceKind = SourceKind.DEMO
                        sourceName = "synthetic demo image"; previewTick++
                    },
                    hasRecipe = hasRecipe,
                    onResetEdits = {
                        // Clear the saved sidecar and restore default editing state.
                        Recipes.delete(ctx, recipeKey)
                        Recipes.resetToDefaults(state, settings, profiles)
                        hasRecipe = false
                        status = "edits reset · recipe cleared"
                        previewTick++
                        scope.launch {
                            snackbarHost.currentSnackbarData?.dismiss()
                            snackbarHost.showSnackbar("Edits reset; saved recipe cleared")
                        }
                    },
                )

                PresetCard(
                    builtInGroups = builtInGroups,
                    onApplyBuiltIn = { preset ->
                        BuiltInPresets.apply(preset, state)
                        status = "applied built-in '${preset.name}'"
                        previewTick++
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
                )

                // --- parameter sections in spektrafilm order ---
                // Film/print dropdowns: catalog-grouped friendly names, falling back to raw ids.
                val available = profiles.ifEmpty {
                    listOf(state.filmProfile, state.printProfile).distinct()
                }
                val filmGroups = remember(available, catalogReady) {
                    StockCatalog.optionsFor(ctx, available, forFilm = true).toGroups()
                }
                val printGroups = remember(available, catalogReady) {
                    StockCatalog.optionsFor(ctx, available, forFilm = false).toGroups()
                }
                // Share the profile groups with the Settings screen's default-profile pickers.
                LaunchedEffect(filmGroups, printGroups) { onProfileGroups(filmGroups, printGroups) }

                InputSection(state)
                ImportRawSection(state, isRaw = sourceKind == SourceKind.RAW)
                SimulationSection(
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
                GrainSection(state)
                PreflashSection(state)
                HalationSection(state)
                CouplersSection(state)
                GlareSection(state)
                ExperimentalSection(state)
                DisplaySection(state)

                ExportButton(
                    enabled = engine != null && !previewBusy && !exporting,
                    onExport = {
                        val e = engine ?: return@ExportButton
                        val fmt = settings.exportFormat
                        exporting = true; exportDone = false; status = "rendering full resolution…"
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.Default) {
                                    val image = loadSource(MAX_EDGE_PX)
                                    val res = e.simulate(image, state.toParams())
                                    val bmp = simResultToBitmap(res.data, res.width, res.height)
                                    val uri = withContext(Dispatchers.IO) {
                                        if (fmt == ExportFormat.TIFF) {
                                            saveSimResultAsTiff(ctx, res)
                                        } else {
                                            saveToGallery(ctx, bmp, fmt, settings.exportQuality)
                                        }
                                    }
                                    bmp to uri
                                }
                            }
                            result.onSuccess { (bmp, _) ->
                                preview = bmp; exportDone = true
                                status = "saved to Pictures/SpectraFilm"
                            }.onFailure {
                                exporting = false
                                status = "export failed: ${it.message}"
                                Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )

                // Export LUT: bake a 33³ .cube off-main-thread, then save via SAF.
                Button(
                    onClick = {
                        val e = engine ?: return@Button
                        bakingLut = true
                        status = "baking .cube LUT…"
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.Default) { e.bakeCubeLut(state.toParams(), 33) }
                            }
                            bakingLut = false
                            result.onSuccess { cube ->
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
                    enabled = engine != null && !bakingLut && !exporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (bakingLut) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Baking LUT…")
                    } else {
                        Text("Export LUT (.cube, 33³)")
                    }
                }
                Text(
                    "Bakes the current film + print look into a 33³ .cube 3D LUT. Spatial/" +
                        "stochastic effects (grain, halation, diffusion, glare) can't be captured " +
                        "in a 3D LUT and are omitted from the bake.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    "Film modeling powered by spektrafilm (GPLv3). Preview is downscaled for " +
                        "interactivity; export renders at full resolution.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // --- 100% grain magnifier overlay ---
            if (magnifierOpen) {
                MagnifierOverlay(
                    crop = magnifierBitmap,
                    rendering = magnifierRendering,
                    status = magnifierStatus,
                    onClose = { magnifierOpen = false; magnifierBitmap = null },
                )
            }

            // --- full-screen export mask ---
            if (exporting) {
                ExportMask(
                    done = exportDone,
                    onDismiss = { exporting = false; exportDone = false },
                )
            }

            // Recipe restore / status snackbar.
            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Top-level pieces
    // ---------------------------------------------------------------------------

    @Composable
    private fun PreviewPane(
        preview: Bitmap?,
        before: Bitmap?,
        busy: Boolean,
        compareMode: Boolean,
        showHistogram: Boolean,
        onToggleCompare: () -> Unit,
        onToggleHistogram: () -> Unit,
        onPointPicked: (Float, Float) -> Unit,
        onCropCenter: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = preview
                if (bmp != null) {
                    if (compareMode && before != null) {
                        CompareSlider(before = before, after = bmp, modifier = Modifier.fillMaxWidth())
                    } else {
                        ZoomableImage(
                            bitmap = bmp,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                            onPointPicked = onPointPicked,
                        )
                    }
                } else {
                    Text("No preview yet", style = MaterialTheme.typography.bodyMedium)
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
                }
            }

            // viewer controls
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = compareMode,
                    onClick = onToggleCompare,
                    label = { Text("Compare") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = showHistogram,
                    onClick = onToggleHistogram,
                    label = { Text("Histogram") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onCropCenter,
                    enabled = preview != null,
                    modifier = Modifier.weight(1f),
                ) { Text("100% crop") }
            }
            if (!compareMode) {
                Text(
                    "Pinch to zoom · double-tap to 2x · tap a point for a 100% grain crop",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showHistogram && preview != null) {
                HistogramCard(preview)
            }
        }
    }

    @Composable
    private fun SourceCard(
        sourceName: String,
        hasRecipe: Boolean,
        onPickPhoto: () -> Unit,
        onOpenRaw: () -> Unit,
        onUseDemo: () -> Unit,
        onResetEdits: () -> Unit,
    ) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Source image", expanded, { expanded = it }) {
            Text("Source: $sourceName", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickPhoto, modifier = Modifier.weight(1f)) { Text("Pick photo") }
                Button(onClick = onOpenRaw, modifier = Modifier.weight(1f)) { Text("Open RAW/DNG") }
            }
            OutlinedButton(onClick = onUseDemo, modifier = Modifier.fillMaxWidth()) { Text("Use demo image") }
            // Non-destructive recipe affordance: shown only when an edit is saved for
            // this source. Edits are auto-saved as a sidecar; the original is untouched.
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
        }
    }

    @Composable
    private fun PresetCard(
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
    ) {
        var expanded by remember { mutableStateOf(true) }
        SectionCard("Presets", expanded, { expanded = it }) {
            // --- built-in presets (bundled, grouped) ---
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
        }
    }

    @Composable
    private fun ExportButton(enabled: Boolean, onExport: () -> Unit) {
        Button(
            onClick = onExport,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export (full resolution → gallery)") }
    }

    @Composable
    private fun ExportMask(done: Boolean, onDismiss: () -> Unit) {
        // Scrim filling the whole window, blocking all interaction until dismissed.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .pointerInput(Unit) { /* consume all gestures while masked */
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
                        "Pictures/SpectraFilm",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDismiss) { Text("View result") }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Parameter sections (spektrafilm GUI order/grouping)
    // ---------------------------------------------------------------------------

    @Composable
    private fun InputSection(s: ParamsState) {
        var expanded by remember { mutableStateOf(false) }
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
            GatedBlock("Upscale and crop have no engine effect yet.") {
                EnhancedSlider("Upscale factor", s.upscaleFactor, 0f..4f, { s.upscaleFactor = it },
                    step = 0.5f, decimals = 1, tooltip = "Scale image size up to increase resolution")
                SwitchRow("Crop", s.crop, { s.crop = it },
                    "Crop image to a fraction of the original size to preview details at full scale")
                PairSlider("Crop center", s.cropCenter, 0f..1f, { s.cropCenter = it }, step = 0.01f, decimals = 2,
                    tooltip = "Center of the crop region (x, y) 0-1", componentLabels = "x" to "y")
                PairSlider("Crop size", s.cropSize, 0f..1f, { s.cropSize = it }, step = 0.01f, decimals = 2,
                    tooltip = "Normalized size of the crop region (x, y)", componentLabels = "x" to "y")
            }
        }
    }

    /**
     * RAW white-balance controls. Only meaningful when a RAW/DNG file is loaded;
     * the section is shown but dims its controls and shows a notice for non-RAW sources
     * so users understand the context. When [isRaw] is true the controls are fully active
     * and changes trigger a re-decode + re-render via the [previewTick] mechanism in [Screen].
     *
     * Temperature and Tint only take effect when [WhiteBalance.CUSTOM] is selected;
     * for other modes they are shown at reduced opacity to match the decoder's behaviour.
     */
    @Composable
    private fun ImportRawSection(s: ParamsState, isRaw: Boolean) {
        var expanded by remember { mutableStateOf(false) }
        SectionCard("RAW White Balance", expanded, { expanded = it }) {
            if (!isRaw) {
                // Not a RAW source — show an informational note; dim the controls.
                Text(
                    "Load a RAW/DNG file (\"Open RAW/DNG\" above) to enable RAW white-balance.",
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
                // RAW source loaded — full controls, wired to re-decode on change.
                val customActive = s.rawWhiteBalance == WhiteBalance.CUSTOM
                Dropdown("White balance", s.rawWhiteBalance, WhiteBalance.entries.toList(),
                    { it.name.lowercase() }, { s.rawWhiteBalance = it })
                // Reset button: restore camera/as-shot WB (default decoder behaviour).
                OutlinedButton(
                    onClick = {
                        s.rawWhiteBalance = WhiteBalance.AS_SHOT
                        s.rawTemperature = 5500f
                        s.rawTint = 1f
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset to camera / as-shot WB") }
                // Temperature & Tint: only used by the decoder in CUSTOM mode.
                // Dim but keep interactive for the other modes so values are preserved.
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

            // --- Auto exposure — Lightroom-style expandable metering control ---
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
            GatedBlock("Camera lens blur has no engine effect yet.") {
                EnhancedSlider("Camera lens blur um", s.cameraLensBlurUm, 0f..20f, { s.cameraLensBlurUm = it },
                    step = 0.05f, decimals = 2, tooltip = "Sigma of gaussian filter in um for the camera lens blur.")
            }
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
            Dropdown("Print illuminant", s.printIlluminant, PRINT_ILLUMINANTS, { it }, { s.printIlluminant = it })
            EnhancedSlider("Print exposure", s.printExposure, 0f..4f, { s.printExposure = it },
                step = 0.02f, decimals = 2, tooltip = "Changes the exposure time set in the virtual enlarger")
            SwitchRow("Print auto compensation", s.printExposureCompensation,
                { s.printExposureCompensation = it },
                "Auto adjust the print exposure for the camera exposure compensation ev")
            EnhancedSlider("Print Y filter shift", s.printYFilterShift, -50f..50f, { s.printYFilterShift = it },
                step = 1f, decimals = 0, tooltip = "Y filter shift from neutral, in Kodak CC units")
            EnhancedSlider("Print M filter shift", s.printMFilterShift, -50f..50f, { s.printMFilterShift = it },
                step = 1f, decimals = 0, tooltip = "M filter shift from neutral, in Kodak CC units")
            GatedBlock("Enlarger lens blur has no engine effect yet.") {
                EnhancedSlider("Enlarger lens blur", s.enlargerLensBlur, 0f..20f, { s.enlargerLensBlur = it },
                    step = 0.05f, decimals = 2, tooltip = "Sigma of gaussian filter for the enlarger lens blur")
            }
            DiffusionGroup("Print diffusion filter", s.printDiffusionState)

            Divider()
            Text("Scanner", style = MaterialTheme.typography.titleSmall)
            GatedBlock("Scanner lens blur has no engine effect yet.") {
                EnhancedSlider("Scan lens blur", s.scanLensBlur, 0f..20f, { s.scanLensBlur = it },
                    step = 0.05f, decimals = 2, tooltip = "Sigma of gaussian filter in pixel for the scanner lens blur")
            }
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
            NotYetActiveNote("Diffusion filters are not wired into the engine yet.")
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
        var expanded by remember { mutableStateOf(false) }
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
        var expanded by remember { mutableStateOf(false) }
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
        var expanded by remember { mutableStateOf(false) }
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
        var expanded by remember { mutableStateOf(false) }
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
        var expanded by remember { mutableStateOf(false) }
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
        var expanded by remember { mutableStateOf(false) }
        SectionCard("Experimental", expanded, { expanded = it }) {
            EnhancedSlider("Film gamma factor", s.filmGammaFactor, 0f..3f, { s.filmGammaFactor = it },
                step = 0.05f, decimals = 2, tooltip = "Gamma factor of the negative density curves.")
            EnhancedSlider("Print gamma factor", s.printGammaFactor, 0f..3f, { s.printGammaFactor = it },
                step = 0.05f, decimals = 2, tooltip = "Gamma factor of the print paper.")
        }
    }

    @Composable
    private fun DisplaySection(s: ParamsState) {
        var expanded by remember { mutableStateOf(false) }
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
