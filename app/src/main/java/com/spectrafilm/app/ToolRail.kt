/*
 * Spektrafilm for Android -- Edit's tool rail.
 *
 * Edit shows every tool of every section on one sideways rail of small cards. Each card is
 * a chip (the tool's name, with a light that shows whether it is on) above the tool's
 * instruments, and nothing else: no help text, no plain sliders. The section tabs above
 * the rail follow it: scrolling moves the bold tab, and tapping a tab scrolls there.
 *
 * - Tap a chip to open the tool's full settings (every control and its help).
 * - Hold a chip to switch the tool off or on, for tools that have a switch.
 * - Moving a control of a switched-off tool switches it on.
 */
package com.spectrafilm.app

import android.view.HapticFeedbackConstants
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.OutputGamutCompress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/** Height of the rail's cards: tall enough for the 2D pad, the tallest instrument. */
internal const val TOOL_RAIL_HEIGHT_DP = 224

/** The 2D pads' side on a card: leaves room for the title and the typed-entry values below. */
private const val RAIL_PAD_DP = 104

/** Tools whose card only opens their full settings: dimming that button would hide the way in. */
private val OPENS_SETTINGS_ONLY = setOf(Category.OUTPUT, Category.DISPLAY, Category.INPUT, Category.EXPERIMENTAL)

/** Preview sizes the Display card's stepped slider offers, in px on the long edge. */
internal val RAIL_PREVIEW_SIZES = listOf(256, 384, 512, 640, 768, 1024)

/** Output gamut modes on the Output card, in the full settings' order. */
private val RAIL_GAMUTS = listOf(
    OutputGamutCompress.LEGACY_CLIP, OutputGamutCompress.ACES_RGC, OutputGamutCompress.OKLCH,
    OutputGamutCompress.OKLRAB, OutputGamutCompress.JZAZBZ, OutputGamutCompress.CAM16UCS,
)

/** Short names for the output colour spaces; product names, so not translated. */
private fun colorSpaceShortName(c: ColorSpace): String = when (c) {
    ColorSpace.SRGB -> "sRGB"
    ColorSpace.ADOBE_RGB -> "Adobe RGB"
    ColorSpace.PROPHOTO -> "ProPhoto"
    ColorSpace.REC2020 -> "Rec. 2020"
    ColorSpace.ACES2065_1 -> "ACES 2065-1"
    ColorSpace.LINEAR_SRGB -> "Linear sRGB"
}

/** A tool's on/off switch, for the tools that have one. */
internal class ToolSwitch(val on: Boolean, val set: (Boolean) -> Unit)

internal fun toolSwitch(s: ParamsState, tool: Category): ToolSwitch? = when (tool) {
    Category.TONE_CURVE -> ToolSwitch(s.toneCurveActive) { s.toneCurveActive = it }
    Category.SCENE_LATITUDE -> ToolSwitch(s.sceneLatitudeActive) { s.sceneLatitudeActive = it }
    Category.CONTRAST_MASK -> ToolSwitch(s.contrastMaskActive) { s.contrastMaskActive = it }
    Category.COUPLERS -> ToolSwitch(s.couplersActive) { s.couplersActive = it }
    Category.GRAIN -> ToolSwitch(s.grainActive) { s.grainActive = it }
    Category.HALATION -> ToolSwitch(s.halationActive) { s.halationActive = it }
    Category.CAMERA_DIFFUSION -> ToolSwitch(s.cameraDiffusionState.active) { s.cameraDiffusionState.active = it }
    Category.GLARE -> ToolSwitch(s.glareActive) { s.glareActive = it }
    // No DATE_STAMP: switching it on needs its text set up first (DateStampGroup does that),
    // so its card sends the user to its full settings instead.
    else -> null
}

/** One entry of the rail: a section's label, or one of its tools. */
private sealed interface RailEntry {
    val stage: Stage
    data class Header(override val stage: Stage) : RailEntry
    data class Tool(val tool: Category) : RailEntry {
        override val stage: Stage get() = tool.stage
    }
}

/**
 * The rail itself. [tools] are the Edit tools to show, in section order. [activeStage] is the
 * section the editor has open; when it changes from outside (a tab tapped), the rail scrolls
 * there, and when the user scrolls into another section the rail reports it through
 * [onScrolledTo].
 */
@Composable
internal fun ToolRail(
    tools: List<Category>,
    activeStage: Stage,
    startTool: Category?,
    dirty: Set<Category>,
    switchOf: (Category) -> ToolSwitch?,
    onScrolledTo: (Stage) -> Unit,
    onOpenTool: (Category) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Category) -> Unit,
) {
    val entries = remember(tools) {
        buildList {
            var last: Stage? = null
            for (t in tools) {
                if (t.stage != last) add(RailEntry.Header(t.stage))
                add(RailEntry.Tool(t))
                last = t.stage
            }
        }
    }
    val startIndex = remember(entries) {
        val tool = startTool?.takeIf { it in tools }
        if (tool != null) {
            entries.indexOfFirst { it is RailEntry.Tool && it.tool == tool }.coerceAtLeast(0)
        } else {
            entries.indexOfFirst { it.stage == activeStage }.coerceAtLeast(0)
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    // The section of the first card whose middle is still on screen.
    val shownStage by remember(entries) {
        derivedStateOf {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.offset + it.size / 2 >= info.viewportStartOffset }
                ?: info.visibleItemsInfo.firstOrNull()
            entries.getOrNull(item?.index ?: listState.firstVisibleItemIndex)?.stage ?: activeStage
        }
    }
    // While the rail scrolls itself to a tapped tab it passes other sections; those are not
    // reported, or the tab would jump back to the first one passed.
    var steering by remember { mutableStateOf(false) }
    val latestOnScrolledTo by rememberUpdatedState(onScrolledTo)
    LaunchedEffect(listState, entries) {
        snapshotFlow { shownStage }.distinctUntilChanged().collect { stage ->
            if (!steering) latestOnScrolledTo(stage)
        }
    }
    LaunchedEffect(activeStage, entries) {
        if (shownStage != activeStage) {
            val target = entries.indexOfFirst { it.stage == activeStage }
            if (target >= 0) {
                steering = true
                try {
                    listState.animateScrollToItem(target)
                } finally {
                    steering = false
                }
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().height(TOOL_RAIL_HEIGHT_DP.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            count = entries.size,
            key = { i ->
                when (val e = entries[i]) {
                    is RailEntry.Header -> "h-${e.stage.name}"
                    is RailEntry.Tool -> "t-${e.tool.name}"
                }
            },
        ) { i ->
            when (val e = entries[i]) {
                is RailEntry.Header -> SectionLabel(e.stage)
                is RailEntry.Tool -> ToolCard(
                    tool = e.tool,
                    switch = switchOf(e.tool),
                    modified = e.tool in dirty,
                    onOpen = { onOpenTool(e.tool) },
                ) { content(e.tool) }
            }
        }
    }
}

/** The section's name, set vertically between its tools and the previous section's. */
@Composable
private fun SectionLabel(stage: Stage) {
    Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(stage.labelRes).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .semantics { heading() }
                .verticalText()
                .rotate(-90f),
        )
    }
}

/** Lays a child out sideways: measured as a line, placed in a box as tall as it is long. */
private fun Modifier.verticalText(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight))
    layout(placeable.height, placeable.width) {
        placeable.place(
            x = -(placeable.width / 2 - placeable.height / 2),
            y = -(placeable.height / 2 - placeable.width / 2),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCard(
    tool: Category,
    switch: ToolSwitch?,
    modified: Boolean,
    onOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val name = stringResource(tool.labelRes)
    val lit = switch?.on ?: modified
    val onState = stringResource(R.string.editor_rail_tool_on)
    val offState = stringResource(R.string.editor_rail_tool_off)
    val toggleLabel = stringResource(R.string.editor_rail_toggle_tool, name)
    val openLabel = stringResource(R.string.editor_rail_open_tool, name)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(
            Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerHighest)
                    .combinedClickable(
                        onClickLabel = openLabel,
                        onClick = onOpen,
                        onLongClickLabel = if (switch != null) toggleLabel else null,
                        onLongClick = switch?.let { sw ->
                            {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                sw.set(!sw.on)
                            }
                        },
                    )
                    .semantics {
                        role = Role.Button
                        if (switch != null) stateDescription = if (switch.on) onState else offState
                    }
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (lit) colors.primary else colors.outline),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    MaterialSymbols.ChevronRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(
                Modifier.weight(1f).alpha(if (switch?.on == false && tool !in OPENS_SETTINGS_ONLY) 0.38f else 1f),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    }
}

/** A card body for a tool whose controls are not instruments yet: open its full settings. */
@Composable
internal fun RailOpenButton(onOpen: () -> Unit) {
    FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.editor_rail_all_settings)) }
}

/**
 * The instruments on each tool's card. A switched-off tool switches on when one of its
 * controls moves, so a drag is never silently ignored.
 */
@Composable
internal fun RailToolContent(s: ParamsState, tool: Category, onOpen: () -> Unit) {
    val sw = toolSwitch(s, tool)
    fun on(set: (Float) -> Unit): (Float) -> Unit = { v -> if (sw?.on == false) sw.set(true); set(v) }
    val d = PARAM_DEFAULTS
    when (tool) {
        Category.EXPOSURE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Instrument(InstrumentKind.PILL, stringResource(R.string.editor_sim_camera_ev),
                s.exposureCompensationEv, -10f..10f, { s.exposureCompensationEv = it }, step = 0.25f, decimals = 2, default = 0f)
            SunburstToggle(stringResource(R.string.widget_ae_description), s.autoExposure, { s.autoExposure = it })
        }
        // Contrast is composed into the tone curve, so moving it switches the curve on (as the
        // full settings do); the curve points themselves need the full editor.
        Category.TONE_CURVE -> Column(Modifier.width(176.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Instrument(InstrumentKind.WAVE, stringResource(R.string.tool_curve_contrast), s.contrast, -100f..100f,
                { s.contrast = it; if (it != 0f && !s.toneCurveActive) s.toneCurveActive = true },
                step = 1f, decimals = 0, default = 0f)
            RailOpenButton(onOpen)
        }
        Category.SCENE_LATITUDE -> Row {
            Instrument(InstrumentKind.FADER, stringResource(R.string.editor_sim_scene_latitude_highlight_room),
                s.sceneLatitudeHighlightRoom, 0f..8f, on { s.sceneLatitudeHighlightRoom = it }, step = 0.25f, decimals = 2,
                default = d.sceneLatitudeHighlightRoom)
            Instrument(InstrumentKind.FADER, stringResource(R.string.editor_sim_scene_latitude_shadow_room),
                s.sceneLatitudeShadowRoom, 0f..8f, on { s.sceneLatitudeShadowRoom = it }, step = 0.25f, decimals = 2,
                default = d.sceneLatitudeShadowRoom)
        }
        Category.CONTRAST_MASK -> Row {
            Instrument(InstrumentKind.ARC_GAUGE, stringResource(R.string.editor_contrast_mask_highlights),
                s.contrastMaskHighlights, OperationalParamLimits.CONTRAST_MASK_AMOUNT, on { s.contrastMaskHighlights = it },
                step = 0.05f, decimals = 2, default = d.contrastMaskHighlights)
            Instrument(InstrumentKind.ARC_GAUGE, stringResource(R.string.editor_contrast_mask_shadows),
                s.contrastMaskShadows, OperationalParamLimits.CONTRAST_MASK_AMOUNT, on { s.contrastMaskShadows = it },
                step = 0.05f, decimals = 2, default = d.contrastMaskShadows)
        }
        Category.RAW_WB -> Column(Modifier.width(176.dp)) {
            Instrument(InstrumentKind.GRAD_BAR, stringResource(R.string.editor_wb_warmth), s.creativeWbTemp, -100f..100f,
                { s.creativeWbTemp = it }, step = 1f, decimals = 0, default = 0f, gradient = WARMTH_STOPS)
            Instrument(InstrumentKind.GRAD_BAR, stringResource(R.string.editor_wb_tint), s.creativeWbTint, -100f..100f,
                { s.creativeWbTint = it }, step = 1f, decimals = 0, default = 0f, gradient = TINT_STOPS)
        }
        Category.SAT_VIB -> Pad2D(
            title = stringResource(R.string.editor_sim_sat_vib_pad),
            x = PadAxis(stringResource(R.string.editor_sim_saturation), s.saturation, -100f..100f,
                { s.saturation = it }, step = 1f, decimals = 0, default = 0f),
            y = PadAxis(stringResource(R.string.editor_sim_vibrance), s.vibrance, -100f..100f,
                { s.vibrance = it }, step = 1f, decimals = 0, default = 0f),
            padSize = RAIL_PAD_DP.dp,
        )
        Category.PRINT_COLOR -> Pad2D(
            title = stringResource(R.string.editor_sim_print_color_pad),
            x = PadAxis(stringResource(R.string.editor_sim_print_m_shift), s.printMFilterShift, -50f..50f,
                { s.printMFilterShift = it }, step = 1f, decimals = 0, default = 0f),
            y = PadAxis(stringResource(R.string.editor_sim_print_y_shift), s.printYFilterShift, -50f..50f,
                { s.printYFilterShift = it }, step = 1f, decimals = 0, default = 0f),
            padSize = RAIL_PAD_DP.dp,
            // Kodak CC filter letters, the same in every language.
            wheelLabels = "M" to "Y",
        )
        Category.COUPLERS -> Row {
            Instrument(InstrumentKind.ARC_GAUGE, stringResource(R.string.editor_coup_amount), s.couplersAmount, 0f..4f,
                on { s.couplersAmount = it }, step = 0.05f, decimals = 2, default = d.couplersAmount)
            Instrument(InstrumentKind.ARC_GAUGE, stringResource(R.string.editor_coup_samelayer), s.couplersInhibitionSamelayer,
                0f..4f, on { s.couplersInhibitionSamelayer = it }, step = 0.05f, decimals = 2,
                default = d.couplersInhibitionSamelayer)
            Instrument(InstrumentKind.ARC_GAUGE, stringResource(R.string.editor_coup_interlayer), s.couplersInhibitionInterlayer,
                0f..4f, on { s.couplersInhibitionInterlayer = it }, step = 0.05f, decimals = 2,
                default = d.couplersInhibitionInterlayer)
        }
        Category.FILM_FORMAT -> Instrument(InstrumentKind.LENS_GRID, stringResource(R.string.editor_sim_film_format),
            s.filmFormatMm, OperationalParamLimits.FILM_FORMAT_MM, { s.filmFormatMm = it }, Modifier.width(176.dp),
            step = 1f, decimals = 0, default = d.filmFormatMm)
        Category.GRAIN -> Row {
            Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_grain_particle_area), s.grainParticleAreaUm2,
                OperationalParamLimits.GRAIN_PARTICLE_AREA_UM2, on { s.grainParticleAreaUm2 = it }, step = 0.2f, decimals = 2,
                default = d.grainParticleAreaUm2)
            Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_grain_blur), s.grainBlur, 0f..3f,
                on { s.grainBlur = it }, step = 0.05f, decimals = 2, default = d.grainBlur)
        }
        Category.HALATION -> Row {
            Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_hal_amount), s.halHalationAmount, 0f..4f,
                on { s.halHalationAmount = it }, step = 0.05f, decimals = 2, default = d.halHalationAmount)
            Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_hal_spatial_scale), s.halHalationSpatialScale,
                0f..4f, on { s.halHalationSpatialScale = it }, step = 0.1f, decimals = 2, default = d.halHalationSpatialScale)
        }
        Category.CAMERA_DIFFUSION -> {
            val df = s.cameraDiffusionState
            Row {
                Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_diff_strength), df.strength, 0f..2f,
                    on { df.strength = it }, step = 0.125f, decimals = 3, default = d.cameraDiffusionState.strength)
                Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_diff_spatial_scale), df.spatialScale, 0f..4f,
                    on { df.spatialScale = it }, step = 0.1f, decimals = 2, default = d.cameraDiffusionState.spatialScale)
            }
        }
        Category.PRINT_EXPOSURE -> Instrument(InstrumentKind.PILL, stringResource(R.string.editor_sim_print_exposure),
            s.printExposure, 0f..4f, { s.printExposure = it }, step = 0.02f, decimals = 2, default = d.printExposure)
        Category.PREFLASH -> Instrument(InstrumentKind.FADER, stringResource(R.string.editor_preflash_exposure),
            s.preflashExposure, 0f..2f, { s.preflashExposure = it }, step = 0.005f, decimals = 3,
            default = d.preflashExposure)
        Category.ENLARGER -> Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_sim_enlarger_lens_blur),
            s.enlargerLensBlur, 0f..20f, { s.enlargerLensBlur = it }, step = 0.05f, decimals = 2,
            default = d.enlargerLensBlur)
        Category.GLARE -> Row {
            Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_glare_percent), s.glarePercent, 0f..1f,
                on { s.glarePercent = it }, step = 0.01f, decimals = 2, default = d.glarePercent)
            Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_glare_blur), s.glareBlur, 0f..10f,
                on { s.glareBlur = it }, step = 0.1f, decimals = 2, default = d.glareBlur)
        }
        Category.LENS_BLUR -> Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_sim_camera_lens_blur),
            s.cameraLensBlurUm, 0f..20f, { s.cameraLensBlurUm = it }, step = 0.05f, decimals = 2,
            default = d.cameraLensBlurUm)
        // The stamp needs its text and corner picked first; those live in its full settings.
        Category.DATE_STAMP -> if (s.dateStampActive) {
            Row {
                Instrument(InstrumentKind.FILL_KNOB, stringResource(R.string.editor_sim_date_stamp_brightness),
                    s.dateStampExposureEv, 0f..5f, { s.dateStampExposureEv = it }, step = 0.25f, decimals = 2,
                    default = d.dateStampExposureEv, glyph = "\u2600")
                Instrument(InstrumentKind.FADER, stringResource(R.string.editor_sim_date_stamp_size),
                    s.dateStampHeight * 100f, 2f..10f, { s.dateStampHeight = it / 100f }, step = 0.25f, decimals = 2,
                    default = d.dateStampHeight * 100f)
            }
        } else {
            RailOpenButton(onOpen)
        }
        Category.SCANNER -> Column(Modifier.width(176.dp)) {
            Instrument(InstrumentKind.METER_HIGH, stringResource(R.string.editor_sim_scan_white_level), s.scanWhiteLevel,
                0f..1f, { s.scanWhiteLevel = it }, step = 0.005f, decimals = 3, default = d.scanWhiteLevel)
            Instrument(InstrumentKind.METER_LOW, stringResource(R.string.editor_sim_scan_black_level), s.scanBlackLevel,
                0f..1f, { s.scanBlackLevel = it }, step = 0.005f, decimals = 3, default = d.scanBlackLevel)
        }
        Category.RAW_CLEANUP -> Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_raw_chroma_noise),
            s.rawChromaNoise, 0f..100f, { s.rawChromaNoise = it }, step = 1f, decimals = 0, default = d.rawChromaNoise)
        Category.OUTPUT -> Column(Modifier.width(208.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val spaces = ColorSpace.entries
            IconSlider(stringResource(R.string.editor_sim_output_color_space), spaces.map(::colorSpaceShortName),
                spaces.indexOf(s.outputColorSpace), { s.outputColorSpace = spaces[it] })
            // OFF (no clip) is reachable from the full settings only, as before.
            val gamutLabels = RAIL_GAMUTS.map {
                when (it) {
                    OutputGamutCompress.LEGACY_CLIP -> stringResource(R.string.editor_gamut_off)
                    OutputGamutCompress.OFF -> stringResource(R.string.editor_gamut_off_no_clip)
                    OutputGamutCompress.ACES_RGC -> stringResource(R.string.editor_gamut_aces)
                    OutputGamutCompress.OKLCH -> stringResource(R.string.editor_gamut_oklch)
                    OutputGamutCompress.OKLRAB -> stringResource(R.string.editor_gamut_oklrab)
                    OutputGamutCompress.JZAZBZ -> stringResource(R.string.editor_gamut_jzazbz)
                    OutputGamutCompress.CAM16UCS -> stringResource(R.string.editor_gamut_cam16ucs)
                }
            }
            val gamutIndex = RAIL_GAMUTS.indexOf(s.outputGamutCompress)
            if (gamutIndex >= 0) {
                IconSlider(stringResource(R.string.editor_sim_output_gamut), gamutLabels, gamutIndex,
                    { s.outputGamutCompress = RAIL_GAMUTS[it] })
            } else {
                RailOpenButton(onOpen)
            }
        }
        Category.DISPLAY -> IconSlider(
            stringResource(R.string.editor_display_preview_max_size),
            RAIL_PREVIEW_SIZES.map { "$it px" },
            nearestOptionIndex(RAIL_PREVIEW_SIZES, s.previewMaxSize),
            { s.previewMaxSize = RAIL_PREVIEW_SIZES[it] },
            Modifier.width(208.dp),
        )
        Category.INPUT -> Row(verticalAlignment = Alignment.CenterVertically) {
            Instrument(InstrumentKind.KNOB, stringResource(R.string.editor_input_spectral_blur), s.spectralGaussianBlur,
                OperationalParamLimits.SPECTRAL_GAUSSIAN_BLUR, { s.spectralGaussianBlur = it }, step = 0.1f, decimals = 1,
                default = d.spectralGaussianBlur)
            RailOpenButton(onOpen)
        }
        Category.EXPERIMENTAL -> Row {
            Instrument(InstrumentKind.FADER, stringResource(R.string.editor_exp_film_gamma), s.filmGammaFactor,
                GAMMA_FACTOR_RANGE, { s.filmGammaFactor = it }, step = 0.05f, decimals = 2, default = d.filmGammaFactor)
            Instrument(InstrumentKind.FADER, stringResource(R.string.editor_exp_print_gamma), s.printGammaFactor,
                GAMMA_FACTOR_RANGE, { s.printGammaFactor = it }, step = 0.05f, decimals = 2, default = d.printGammaFactor)
        }
        else -> RailOpenButton(onOpen)
    }
}
