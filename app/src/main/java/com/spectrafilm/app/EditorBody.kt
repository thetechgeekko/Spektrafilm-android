/*
 * Spektrafilm for Android — the editor's body under the top bar. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * #582: the adaptive pane layout moved out of EditorScreen. Stateless over the editor: it
 * reads only its parameters, keeps only layout state of its own (pane geometry, the panel's
 * measured height and tall/compact choice, the Edit rail's open tool), and reports every
 * change through a callback. The photo and one tool's controls come in as slots, built by
 * EditorScreen where the editor state lives.
 *
 * Adaptive editor chrome. Narrow (phone portrait): the preview over a floating bottom panel
 * over the category bar. Wide (tablets, landscape phones, desktop windows, unfolded
 * foldables): the active panel docks as a side column beside the preview + category bar.
 * Neither branch touches PreviewRegion or the GPU/CPU render paths; only where the panel
 * body is hosted differs.
 */
package com.spectrafilm.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

@Composable
internal fun ColumnScope.EditorBody(
    foldingFeature: FoldingFeature?,
    state: ParamsState,
    /** A tool reset changed the parameters: re-render. */
    onParamsChanged: () -> Unit,
    activeCategory: Category?,
    onActiveCategoryChange: (Category?) -> Unit,
    toolShown: (Category) -> Boolean,
    wide: Boolean,
    chromeHidden: Boolean,
    panelBackEnabled: Boolean,
    sliderInteraction: SliderInteraction,
    interacting: State<Boolean>,
    sliderReadout: MutableState<SliderReadout?>,
    panelBackProgress: Float,
    exportVisible: Boolean,
    exportInFlight: Boolean,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onOpenCrop: () -> Unit,
    toolContent: @Composable ColumnScope.(Category) -> Unit,
    previewRegion: @Composable (controlsBottomInset: Dp, editOpen: Boolean) -> Unit,
    /** The Crop tab is open: its panel takes the tool panel's place (CropTool.kt). */
    cropOpen: Boolean = false,
    cropPanelContent: @Composable ColumnScope.() -> Unit = {},
) {
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
                onParamsChanged()
            }
        }
        // The tool Edit reopens on: the last one shown there.
        var lastEdit by rememberSaveable { mutableStateOf<Category?>(null) }
        LaunchedEffect(activeCategory) {
            activeCategory?.takeIf { it.mode == EditorMode.EDIT }?.let { lastEdit = it }
        }
        val editOpen = activeCategory?.mode == EditorMode.EDIT
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
            EditorPanelContent(
                sliderInteraction = sliderInteraction,
                state = state,
                activeCategory = activeCategory,
                onActiveCategoryChange = onActiveCategoryChange,
                railDetail = railDetail,
                onRailDetailChange = { railDetail = it },
                backEnabled = panelBackEnabled,
                dirty = dirty,
                resetCategory = resetCategory,
                toolShown = toolShown,
                toolContent = toolContent,
            )
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
        val panelShown = !wide && (activeCategory != null || cropOpen) && !chromeHidden &&
            posture !is FoldPosture.Tabletop
        val panelTarget = if (panelShown) {
            with(LocalDensity.current) { panelHeightPx.toDp() }
        } else {
            0.dp
        }
        val panelInset by animateDpAsState(panelTarget, label = "previewPanelInset")
        // In Edit each stacked tool carries its own reset (ToolHeader); elsewhere the
        // panel offers one for the open tool.
        val resetActiveCategory: (() -> Unit)? =
            activeCategory?.takeIf { !editOpen && it in dirty }?.let { target -> { resetCategory(target) } }
        val editSections = stagesOf(EditorMode.EDIT)
        val openSection: (Stage) -> Unit = { section ->
            onActiveCategoryChange(categoriesOf(section).first(toolShown))
        }
        if (wide) {
            EditorWideLayout(
                chromeHidden = chromeHidden,
                activeCategory = activeCategory,
                onActiveCategoryChange = onActiveCategoryChange,
                dirty = dirty,
                lastEdit = lastEdit,
                openCrop = onOpenCrop,
                exportVisible = exportVisible,
                exportInFlight = exportInFlight,
                onExport = onExport,
                onShare = onShare,
                previewRegion = { previewRegion(panelInset, editOpen) },
                bookAligned = bookAligned,
                bookHingeGap = bookHingeGap,
                sidePaneWidth = sidePaneWidth,
                panelBackProgress = panelBackProgress,
                resetActiveCategory = resetActiveCategory,
                editOpen = editOpen,
                editSections = editSections,
                openSection = openSection,
                panelContent = panelContent,
                cropOpen = cropOpen,
                cropPanelContent = cropPanelContent,
            )
        } else {
            EditorNarrowLayout(
                chromeHidden = chromeHidden,
                activeCategory = activeCategory,
                onActiveCategoryChange = onActiveCategoryChange,
                dirty = dirty,
                lastEdit = lastEdit,
                openCrop = onOpenCrop,
                posture = posture,
                interacting = interacting,
                sliderReadout = sliderReadout,
                previewRegion = { previewRegion(panelInset, editOpen) },
                panelBackProgress = panelBackProgress,
                panelTall = panelTall,
                onPanelTallChange = { panelTall = it },
                onPanelHeightChange = { panelHeightPx = it },
                resetActiveCategory = resetActiveCategory,
                editOpen = editOpen,
                editSections = editSections,
                openSection = openSection,
                panelContent = panelContent,
                cropOpen = cropOpen,
                cropPanelContent = cropPanelContent,
            )
        }
    }
}
