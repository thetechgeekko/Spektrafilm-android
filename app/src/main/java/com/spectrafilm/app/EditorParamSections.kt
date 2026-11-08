/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Editor parameter sections (#273, second MainActivity split): input, RAW import and
 * simulation sections plus the shared button glyph. Stateless over [ParamsState]; moved
 * verbatim out of MainActivity.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.InputGamutCompress
import com.spectrafilm.engine.OutputGamutCompress
import com.spectrafilm.engine.Rgb2Raw
import com.spectrafilm.libraw.WhiteBalance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Parameter sections (spektrafilm GUI order/grouping) — preserved verbatim
// ---------------------------------------------------------------------------

/**
 * The leading glyph on a text button.
 *
 * 18.dp and an 8.dp gap are Material's own figures for an icon inside a Button
 * (ButtonDefaults.IconSize / IconSpacing), so these rows keep the same rhythm as every
 * other button in the app. contentDescription is null throughout: the label is right
 * there and already carries the meaning, so announcing the glyph as well would make
 * TalkBack say everything twice.
 */
@Composable
internal fun ButtonIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}

@Composable
internal fun InputSection(s: ParamsState, onEditCrop: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_input_title), expanded, { expanded = it }) {
        // Honesty: the engine's input space is fixed to linear ProPhoto RGB (the
        // runtime InputColorSpace enum has a single value). RAW imports are decoded
        // ACES2065-1 -> ProPhoto and photos are converted sRGB -> ProPhoto before the
        // engine, so this selector is retained for presets/forward-compat but does not
        // change the render. Other input spaces are the deferred input-gamut work.
        GatedBlock(stringResource(R.string.editor_input_gated_colorspace)) {
            Dropdown(stringResource(R.string.editor_input_color_space), s.inputColorSpace, INPUT_COLOR_SPACES, { it },
                { s.inputColorSpace = it })
        }
        SwitchRow(stringResource(R.string.editor_input_cctf), s.inputCctfDecoding, { s.inputCctfDecoding = it },
            stringResource(R.string.editor_input_cctf_tooltip))
        // Honesty: the engine only implements HANATOS2025 (filming.expose always calls
        // rgb_to_raw_hanatos2025); MALLETT2019 marshals but falls back, so the choice is inert.
        // Creative warmth/tint and the eyedropper used to be repeated here; they live in
        // White balance only now, one place per control.
        Divider()
        GatedBlock(stringResource(R.string.editor_input_gated_upsampling)) {
            Dropdown(stringResource(R.string.editor_input_spectral_upsampling), s.spectralUpsampling, Rgb2Raw.entries.toList(),
                { it.name.lowercase() }, { s.spectralUpsampling = it })
        }
        SwitchRow(stringResource(R.string.editor_input_adaptation_window), s.adaptationWindow, { s.adaptationWindow = it },
            stringResource(R.string.editor_input_adaptation_window_tooltip))
        SwitchRow(stringResource(R.string.editor_input_adaptation_surface), s.adaptationSurface, { s.adaptationSurface = it },
            stringResource(R.string.editor_input_adaptation_surface_tooltip))
        EnhancedSlider(stringResource(R.string.editor_input_spectral_blur), s.spectralGaussianBlur, OperationalParamLimits.SPECTRAL_GAUSSIAN_BLUR,
            { s.spectralGaussianBlur = it }, step = 0.1f, decimals = 1,
            tooltip = stringResource(R.string.editor_input_spectral_blur_tooltip),
            default = PARAM_DEFAULTS.spectralGaussianBlur)
        TripleSlider(stringResource(R.string.editor_input_uv_filter), s.filterUv, 0f..800f, { s.filterUv = it },
            step = 1f, decimals = 0,
            tooltip = stringResource(R.string.editor_input_uv_filter_tooltip),
            componentLabels = Triple("amp", "λ", "σ"), default = PARAM_DEFAULTS.filterUv)
        TripleSlider(stringResource(R.string.editor_input_ir_filter), s.filterIr, 0f..800f, { s.filterIr = it },
            step = 1f, decimals = 0,
            tooltip = stringResource(R.string.editor_input_ir_filter_tooltip),
            componentLabels = Triple("amp", "λ", "σ"), default = PARAM_DEFAULTS.filterIr)
        EnhancedSlider(stringResource(R.string.editor_input_upscale), s.upscaleFactor, OperationalParamLimits.UPSCALE_FACTOR, { s.upscaleFactor = it },
            step = 0.5f, decimals = 1,
            tooltip = stringResource(R.string.editor_input_upscale_tooltip),
            default = PARAM_DEFAULTS.upscaleFactor)
        // Crop is now an interactive overlay (see the crop button under the
        // preview). Upscale stays a slider: it is a resolution multiplier, not
        // part of the crop rectangle, so it does not fit the crop metaphor.
        Divider()
        Text(
            if (s.crop) stringResource(R.string.editor_input_crop_enabled)
            else stringResource(R.string.editor_input_crop_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onEditCrop, modifier = Modifier.weight(1f)) {
                ButtonIcon(SpectraIcons.Crop)
                Text(stringResource(R.string.editor_input_edit_crop))
            }
            if (s.crop) {
                OutlinedButton(
                    onClick = {
                        s.crop = false
                        s.cropCenter = 0.5f to 0.5f
                        s.cropSize = 0.1f to 0.1f
                    },
                ) { Text(stringResource(R.string.editor_input_clear_crop)) }
            }
        }
        // Granular reset scope (Lightroom-style): restore just crop + upscale to
        // engine defaults, leaving the rest of the edit untouched.
        val geometryDirty = s.crop || s.upscaleFactor != PARAM_DEFAULTS.upscaleFactor
        if (geometryDirty) {
            OutlinedButton(
                onClick = {
                    s.crop = false
                    s.cropCenter = 0.5f to 0.5f
                    s.cropSize = 0.1f to 0.1f
                    s.upscaleFactor = PARAM_DEFAULTS.upscaleFactor
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.editor_input_reset_geometry)) }
        }
    }
}

@Composable
internal fun ImportRawSection(s: ParamsState, isRaw: Boolean, onPickNeutral: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_wb_title), expanded, { expanded = it }) {
        // The eyedropper + creative warmth/tint work on EVERY source, so they're shown FIRST and
        // always — the eyedropper is the most prominent control so it's findable. The native RAW
        // camera WB (Kelvin/tint, re-decodes the file) is appended only for RAW/DNG sources.
        OutlinedButton(onClick = onPickNeutral, modifier = Modifier.fillMaxWidth()) {
            ButtonIcon(SpectraIcons.Eyedropper)
            Text(stringResource(R.string.editor_wb_eyedropper))
        }
        Text(
            stringResource(R.string.editor_wb_eyedropper_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Gradient bars (instrument catalogue: gradBar): the colour under the thumb is the
        // shift being applied, so the direction reads without the number.
        Instrument(InstrumentKind.GRAD_BAR, stringResource(R.string.editor_wb_warmth), s.creativeWbTemp, -100f..100f,
            { s.creativeWbTemp = it }, step = 1f, decimals = 0, default = 0f, gradient = WARMTH_STOPS)
        Text(stringResource(R.string.editor_wb_warmth_tooltip), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Instrument(InstrumentKind.GRAD_BAR, stringResource(R.string.editor_wb_tint), s.creativeWbTint, -100f..100f,
            { s.creativeWbTint = it }, step = 1f, decimals = 0, default = 0f, gradient = TINT_STOPS)
        Text(stringResource(R.string.editor_wb_tint_tooltip), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Divider()
        // Balance to film stock (virtual 85-filter): the escape hatch for tungsten stocks, which
        // render a daylight scene authentically blue. Adapts the input to the film's reference white
        // so neutrals render neutral. The hint adapts to whether the selected film is tungsten.
        val ctx = LocalContext.current
        val tungsten = StockCatalog.entry(ctx, s.filmProfile)?.balance == "tungsten"
        SwitchRow(
            stringResource(R.string.editor_wb_balance_to_stock),
            s.balanceToFilmStock,
            { s.balanceToFilmStock = it },
            stringResource(if (tungsten) R.string.editor_wb_balance_tungsten else R.string.editor_wb_balance_daylight),
        )

        if (isRaw) {
            Divider()
            Text(
                stringResource(R.string.editor_wb_raw_heading),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            val customActive = s.rawWhiteBalance == WhiteBalance.CUSTOM
            Dropdown(stringResource(R.string.editor_wb_camera), s.rawWhiteBalance, WhiteBalance.entries.toList(),
                { it.name.lowercase() }, { s.rawWhiteBalance = it })
            OutlinedButton(
                onClick = {
                    s.rawWhiteBalance = WhiteBalance.AS_SHOT
                    s.rawTemperature = 5500f
                    s.rawTint = 1f
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.editor_wb_reset_as_shot)) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (customActive) 1f else 0.45f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!customActive) {
                    Text(
                        stringResource(R.string.editor_wb_custom_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EnhancedSlider(
                    stringResource(R.string.editor_wb_temperature), s.rawTemperature, 1000f..12000f,
                    { s.rawTemperature = it },
                    step = 100f, decimals = 0,
                    tooltip = stringResource(R.string.editor_wb_temperature_tooltip),
                    default = PARAM_DEFAULTS.rawTemperature,
                )
                EnhancedSlider(
                    stringResource(R.string.editor_wb_tint_multiplier), s.rawTint, 0f..2f,
                    { s.rawTint = it },
                    step = 0.01f, decimals = 2,
                    tooltip = stringResource(R.string.editor_wb_tint_multiplier_tooltip),
                    default = PARAM_DEFAULTS.rawTint,
                )
            }
        }
    }
}

/**
 * RAW cleanup (#259): opt-in clean-up inside the RAW decode. Both default off/0, which decodes
 * byte-identically to a build without them. Only offered for a RAW/DNG source.
 */
@Composable
internal fun RawCleanupSection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_raw_cleanup_heading), expanded, { expanded = it }) {
        SwitchRow(
            stringResource(R.string.editor_raw_hot_pixels),
            s.rawHotPixels,
            { s.rawHotPixels = it },
            stringResource(R.string.editor_raw_hot_pixels_tooltip),
        )
        EnhancedSlider(
            stringResource(R.string.editor_raw_chroma_noise), s.rawChromaNoise, 0f..100f,
            { s.rawChromaNoise = it },
            step = 1f, decimals = 0,
            tooltip = stringResource(R.string.editor_raw_chroma_noise_tooltip),
            default = 0f,
        )
    }
}

/**
 * Show a one-action suggestion snackbar (e.g. "Use its defaults" on a profile switch, §6h; or
 * "Slide mode" when a reversal film is picked, §6e). Non-destructive: [onAction] runs only if the
 * user taps [actionLabel]. UI/state only — no parity impact.
 */
internal fun offerSnackbarSuggestion(
    scope: CoroutineScope,
    host: SnackbarHostState,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    scope.launch {
        host.currentSnackbarData?.dismiss()
        val result = host.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) onAction()
    }
}

/**
 * Which tool of the film -> print -> scan simulation a [SimulationSection] shows. One per
 * editor tool since the 2026-11-08 regroup: the old four parts (Film / Print / Scanner /
 * Output) each held several tools, which is why Exposure used to hide in the film-stock panel.
 */
internal enum class SimPart {
    FILM_STOCK, EXPOSURE, FILM_FORMAT, LENS_BLUR, CAMERA_DIFFUSION, SCENE_LATITUDE, DATE_STAMP,
    PAPER, PRINT_EXPOSURE, PRINT_COLOR, ENLARGER, SCANNER, OUTPUT, SAT_VIB,
}

@Composable
internal fun SimulationSection(
    s: ParamsState,
    filmGroups: List<DropdownGroup>,
    printGroups: List<DropdownGroup>,
    onOpenFilmCurves: () -> Unit,
    onOpenPrintCurves: () -> Unit,
    onFilmProfileChange: (String) -> Unit,
    onPrintProfileChange: (String) -> Unit,
    photoDateStamp: String? = null,
    filmPreviewHost: StockPreviewHost? = null,
    printPreviewHost: StockPreviewHost? = null,
    filmStripSourceKey: Any? = null,
    filmStripPaused: Boolean = false,
    part: SimPart = SimPart.FILM_STOCK,
) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_sim_title), expanded, { expanded = it }) {
        // One part per editor tool (m3-design-language, ux-flows §2: one level of peers, no
        // nested tab rows).
        when (part) {
            SimPart.FILM_STOCK -> {
                // Film & look: the user's photo through every stock, one tap to try each.
                // Only with a photo to render; the demo/no-source state keeps the field alone.
                if (filmPreviewHost != null) {
                    FilmStockStrip(
                        selectedId = s.filmProfile,
                        groups = filmGroups,
                        previewHost = filmPreviewHost,
                        sourceKey = filmStripSourceKey,
                        paused = filmStripPaused,
                        onSelect = onFilmProfileChange,
                    )
                }
                // A browser rather than a menu: 28 stocks, each with an ISO, a colour
                // balance and a character note that a dropdown row cannot show well. It is
                // also the strip's "show all" (M3 carousel a11y: every item reachable
                // without horizontal scrolling).
                StockPickerField(
                    label = stringResource(R.string.editor_sim_film_profile),
                    selectedId = s.filmProfile,
                    groups = filmGroups,
                    onSelect = onFilmProfileChange,
                    previewHost = filmPreviewHost,
                )
                // The curve viewer is reference material for the stock just picked.
                OutlinedButton(
                    onClick = onOpenFilmCurves,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.editor_sim_view_film_curves)) }
            }
            SimPart.EXPOSURE -> {
                // Camera exposure, the first thing a photographer reaches for: Edit opens on it.
                EnhancedSlider(stringResource(R.string.editor_sim_camera_ev), s.exposureCompensationEv, -10f..10f,
                    { s.exposureCompensationEv = it }, step = 0.25f, decimals = 2, default = 0f,
                    tooltip = stringResource(R.string.editor_sim_camera_ev_tooltip))
                AutoExposureControl(
                    autoExposure = s.autoExposure,
                    autoExposureMethod = s.autoExposureMethod,
                    methods = AUTO_EXPOSURE_METHODS,
                    onAutoExposureChange = { s.autoExposure = it },
                    onMethodChange = { s.autoExposureMethod = it },
                )
            }
            SimPart.FILM_FORMAT -> {
                EnhancedSlider(stringResource(R.string.editor_sim_film_format), s.filmFormatMm, OperationalParamLimits.FILM_FORMAT_MM, { s.filmFormatMm = it },
                    step = 1f, decimals = 0,
                    tooltip = stringResource(R.string.editor_sim_film_format_tooltip),
                    default = PARAM_DEFAULTS.filmFormatMm)
            }
            SimPart.LENS_BLUR -> {
                EnhancedSlider(stringResource(R.string.editor_sim_camera_lens_blur), s.cameraLensBlurUm, 0f..20f, { s.cameraLensBlurUm = it },
                    step = 0.05f, decimals = 2,
                    tooltip = stringResource(R.string.editor_sim_camera_lens_blur_tooltip),
                    default = PARAM_DEFAULTS.cameraLensBlurUm)
            }
            SimPart.CAMERA_DIFFUSION ->
                DiffusionGroup(stringResource(R.string.editor_sim_camera_diffusion), s.cameraDiffusionState)
            SimPart.SCENE_LATITUDE -> SceneLatitudeGroup(s)
            SimPart.DATE_STAMP -> DateStampGroup(s, photoDateStamp)
            SimPart.PAPER -> {
                StockPickerField(
                    label = stringResource(R.string.editor_sim_print_profile),
                    selectedId = s.printProfile,
                    groups = printGroups,
                    onSelect = onPrintProfileChange,
                    previewHost = printPreviewHost,
                )
                // Slide mode skips the paper altogether, so it sits with the paper choice.
                SwitchRow(stringResource(R.string.editor_sim_slide_mode), s.scanFilm, { s.scanFilm = it },
                    stringResource(R.string.editor_sim_slide_mode_tooltip))
                OutlinedButton(
                    onClick = onOpenPrintCurves,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.editor_sim_view_print_curves)) }
            }
            SimPart.PRINT_EXPOSURE -> {
                if (SilverPrint.isSilverPaper(s.printProfile)) PaperGradeRow(s)
                EnhancedSlider(stringResource(R.string.editor_sim_print_exposure), s.printExposure, 0f..4f, { s.printExposure = it },
                    step = 0.02f, decimals = 2,
                    tooltip = stringResource(R.string.editor_sim_print_exposure_tooltip),
                    default = PARAM_DEFAULTS.printExposure)
                SwitchRow(stringResource(R.string.editor_sim_print_auto_comp), s.printExposureCompensation,
                    { s.printExposureCompensation = it },
                    stringResource(R.string.editor_sim_print_auto_comp_tooltip))
            }
            SimPart.PRINT_COLOR -> {
                EnhancedSlider(stringResource(R.string.editor_sim_print_y_shift), s.printYFilterShift, -50f..50f, { s.printYFilterShift = it },
                    step = 1f, decimals = 0, default = 0f,
                    tooltip = stringResource(R.string.editor_sim_print_y_shift_tooltip))
                EnhancedSlider(stringResource(R.string.editor_sim_print_m_shift), s.printMFilterShift, -50f..50f, { s.printMFilterShift = it },
                    step = 1f, decimals = 0, default = 0f,
                    tooltip = stringResource(R.string.editor_sim_print_m_shift_tooltip))
            }
            SimPart.ENLARGER -> {
                GatedBlock(stringResource(R.string.editor_sim_gated_enlarger_blur)) {
                    EnhancedSlider(stringResource(R.string.editor_sim_enlarger_lens_blur), s.enlargerLensBlur, 0f..20f, { s.enlargerLensBlur = it },
                        step = 0.05f, decimals = 2,
                        tooltip = stringResource(R.string.editor_sim_enlarger_lens_blur_tooltip),
                        default = PARAM_DEFAULTS.enlargerLensBlur)
                }
                DiffusionGroup(stringResource(R.string.editor_sim_print_diffusion), s.printDiffusionState)
            }
            SimPart.SCANNER -> {
                val ctx = LocalContext.current
                EnhancedSlider(stringResource(R.string.editor_sim_scan_lens_blur), s.scanLensBlur, 0f..20f, { s.scanLensBlur = it },
                    step = 0.05f, decimals = 2,
                    tooltip = stringResource(R.string.editor_sim_scan_lens_blur_tooltip),
                    default = PARAM_DEFAULTS.scanLensBlur)
                // Scan white/black correction pins the scan's white/black points to the target levels
                // below. The engine makes it a STRICT no-op in Slide mode on a negative stock (it's
                // active only for a slide/positive film on the scan-film route, or in print mode — all
                // print papers are negative), so we flag and gray it out there; elsewhere it's active
                // but often subtle at the default 0.98/0.01 levels.
                val correctionNoOp = s.scanFilm && !StockCatalog.isReversalFilm(ctx, s.filmProfile)
                if (correctionNoOp) {
                    Text(
                        stringResource(R.string.editor_sim_scan_correction_noop),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().alpha(if (correctionNoOp) 0.5f else 1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SwitchRow(stringResource(R.string.editor_sim_scan_white_correction), s.scanWhiteCorrection, { s.scanWhiteCorrection = it },
                        stringResource(R.string.editor_sim_scan_white_correction_tooltip))
                    EnhancedSlider(stringResource(R.string.editor_sim_scan_white_level), s.scanWhiteLevel, 0f..1f, { s.scanWhiteLevel = it },
                        step = 0.005f, decimals = 3,
                        tooltip = stringResource(R.string.editor_sim_scan_white_level_tooltip),
                        default = PARAM_DEFAULTS.scanWhiteLevel)
                    SwitchRow(stringResource(R.string.editor_sim_scan_black_correction), s.scanBlackCorrection, { s.scanBlackCorrection = it },
                        stringResource(R.string.editor_sim_scan_black_correction_tooltip))
                    EnhancedSlider(stringResource(R.string.editor_sim_scan_black_level), s.scanBlackLevel, 0f..1f, { s.scanBlackLevel = it },
                        step = 0.005f, decimals = 3,
                        tooltip = stringResource(R.string.editor_sim_scan_black_level_tooltip),
                        default = PARAM_DEFAULTS.scanBlackLevel)
                }
                PairSlider(stringResource(R.string.editor_sim_scan_unsharp), s.scanUnsharpMask, 0f..5f, { s.scanUnsharpMask = it },
                    step = 0.05f, decimals = 2,
                    tooltip = stringResource(R.string.editor_sim_scan_unsharp_tooltip),
                    componentLabels = "σ" to "amt", default = PARAM_DEFAULTS.scanUnsharpMask)
            }
            SimPart.OUTPUT -> {
                val res = LocalContext.current.resources
                Dropdown(stringResource(R.string.editor_sim_output_color_space), s.outputColorSpace, ColorSpace.entries.toList(),
                    { it.name }, { s.outputColorSpace = it })
                // Opt-in gamut compression (default Off => byte-identical to the
                // parity oracle). Output: ACES Reference Gamut Compression softens
                // out-of-gamut chromaticities toward the achromatic axis in the
                // output space. Input: a radial CIE-xy compression toward the visible
                // spectral locus, baked into the filming reconstruction LUT, tames
                // over-saturated input before the film responds to it.
                Dropdown(
                    stringResource(R.string.editor_sim_output_gamut),
                    s.outputGamutCompress,
                    listOf(
                        OutputGamutCompress.LEGACY_CLIP,
                        OutputGamutCompress.ACES_RGC,
                        OutputGamutCompress.OKLCH,
                        OutputGamutCompress.OKLRAB,
                        OutputGamutCompress.JZAZBZ,
                        OutputGamutCompress.CAM16UCS,
                    ),
                    {
                        when (it) {
                            OutputGamutCompress.LEGACY_CLIP -> res.getString(R.string.editor_gamut_off)
                            OutputGamutCompress.OFF -> res.getString(R.string.editor_gamut_off_no_clip)
                            OutputGamutCompress.ACES_RGC -> res.getString(R.string.editor_gamut_aces)
                            OutputGamutCompress.OKLCH -> res.getString(R.string.editor_gamut_oklch)
                            OutputGamutCompress.OKLRAB -> res.getString(R.string.editor_gamut_oklrab)
                            OutputGamutCompress.JZAZBZ -> res.getString(R.string.editor_gamut_jzazbz)
                            OutputGamutCompress.CAM16UCS -> res.getString(R.string.editor_gamut_cam16ucs)
                        }
                    },
                    { s.outputGamutCompress = it },
                )
                Dropdown(
                    stringResource(R.string.editor_sim_input_gamut),
                    s.inputGamutCompress,
                    InputGamutCompress.entries.toList(),
                    {
                        when (it) {
                            InputGamutCompress.OFF -> res.getString(R.string.editor_gamut_off)
                            InputGamutCompress.XY -> res.getString(R.string.editor_gamut_spectral_locus)
                        }
                    },
                    { s.inputGamutCompress = it },
                )
                EnhancedSlider(stringResource(R.string.editor_sim_gamut_compression), s.gamutCompress, 0f..100f, { s.gamutCompress = it },
                    step = 1f, decimals = 0, default = 0f,
                    tooltip = stringResource(R.string.editor_sim_gamut_compression_tooltip))
                SwitchRow(stringResource(R.string.editor_sim_saving_cctf), s.savingCctfEncoding, { s.savingCctfEncoding = it },
                    stringResource(R.string.editor_sim_saving_cctf_tooltip))
            }
            SimPart.SAT_VIB -> {
                // Creative grade (post-engine Oklab chroma; parity-safe). Negative Saturation
                // mutes a too-punchy look; Vibrance boosts muted colors while sparing
                // already-saturated ones.
                EnhancedSlider(stringResource(R.string.editor_sim_saturation), s.saturation, -100f..100f, { s.saturation = it },
                    step = 1f, decimals = 0, default = 0f,
                    tooltip = stringResource(R.string.editor_sim_saturation_tooltip))
                EnhancedSlider(stringResource(R.string.editor_sim_vibrance), s.vibrance, -100f..100f, { s.vibrance = it },
                    step = 1f, decimals = 0, default = 0f,
                    tooltip = stringResource(R.string.editor_sim_vibrance_tooltip))
            }
        }
    }
}

/**
 * Multigrade contrast grade, 00 to 5, as one segmented row. Shown only on a silver paper, where
 * [ParamsState.toParams] turns the grade into the enlarger's filter pack and mid-grey exposure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaperGradeRow(s: ParamsState) {
    val label = stringResource(R.string.editor_sim_paper_grade)
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.editor_sim_paper_grade_tooltip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val grades = SilverPrint.GRADES
        val current = SilverPrint.grade(s.paperGrade).label
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            grades.forEachIndexed { index, grade ->
                SegmentedButton(
                    selected = grade.label == current,
                    onClick = { s.paperGrade = grade.label },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = grades.size),
                    modifier = Modifier.semantics { contentDescription = "$label ${grade.label}" },
                    icon = {},
                ) { Text(grade.label, maxLines = 1) }
            }
        }
    }
}
