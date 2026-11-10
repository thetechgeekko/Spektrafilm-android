/*
 * Spektrafilm for Android — editor pane layouts. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The expanded-window editor: stage rail, the photo, and the open tool docked as a side pane.
 * Split out of [EditorScreen] (#273); the caller owns every piece of state.
 */
@Composable
internal fun EditorWideLayout(
    chromeHidden: Boolean,
    activeCategory: Category?,
    onActiveCategoryChange: (Category?) -> Unit,
    dirty: Set<Category>,
    lastEdit: Category?,
    openCrop: () -> Unit,
    exportVisible: Boolean,
    exportInFlight: Boolean,
    onExport: () -> Unit,
    onShare: () -> Unit,
    previewRegion: @Composable () -> Unit,
    bookAligned: Boolean,
    bookHingeGap: Dp,
    sidePaneWidth: Dp,
    panelBackProgress: Float,
    resetActiveCategory: (() -> Unit)?,
    editOpen: Boolean,
    editSections: List<Stage>,
    openSection: (Stage) -> Unit,
    panelContent: @Composable ColumnScope.() -> Unit,
    cropOpen: Boolean = false,
    cropPanelContent: @Composable ColumnScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxSize()) {
        // --- STAGE RAIL (expanded windows), Export as its FAB ---
        if (!chromeHidden) {
            StageRail(
                active = activeCategory,
                dirty = dirty,
                onOpen = { onActiveCategoryChange(it) },
                onCrop = openCrop,
                lastEdit = lastEdit,
                exportVisible = exportVisible,
                exporting = exportInFlight,
                onExport = onExport,
                onShare = onShare,
                cropSelected = cropOpen,
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
        val sideCategory = activeCategory?.takeIf { !cropOpen }
        // Book posture on a device with a physical gap: keep the gap empty so
        // neither the photo nor the tools sit under it.
        if (bookAligned && bookHingeGap > 0.dp) Spacer(Modifier.width(bookHingeGap))
        if (cropOpen) {
            CropPanel(sideWidth = sidePaneWidth, content = cropPanelContent)
        } else if (sideCategory == null && bookAligned) {
            // Panel closed in book posture: the photo still keeps to its own
            // screen rather than straddling the fold; the tools' screen stays
            // empty until a category is opened again.
            Spacer(Modifier.width(sidePaneWidth))
        }
        if (sideCategory != null) {
            SidePanel(
                modifier = Modifier.predictiveBackShrink(panelBackProgress),
                category = sideCategory,
                onDismiss = { onActiveCategoryChange(null) },
                onResetCategory = resetActiveCategory,
                width = sidePaneWidth,
                tabs = if (editOpen) emptyList() else categoriesOf(sideCategory.stage),
                dirty = dirty,
                onSelectTab = { onActiveCategoryChange(it) },
                sections = if (editOpen) editSections else emptyList(),
                onSelectSection = openSection,
                content = panelContent,
            )
        }
    }
}

/**
 * The compact and medium editor: the photo over a floating tool panel over the stage row.
 * Split out of [EditorScreen] (#273); the caller owns every piece of state.
 */
@Composable
internal fun EditorNarrowLayout(
    chromeHidden: Boolean,
    activeCategory: Category?,
    onActiveCategoryChange: (Category?) -> Unit,
    dirty: Set<Category>,
    lastEdit: Category?,
    openCrop: () -> Unit,
    posture: FoldPosture,
    interacting: State<Boolean>,
    sliderReadout: MutableState<SliderReadout?>,
    previewRegion: @Composable () -> Unit,
    panelBackProgress: Float,
    panelTall: Boolean,
    onPanelTallChange: (Boolean) -> Unit,
    onPanelHeightChange: (Int) -> Unit,
    resetActiveCategory: (() -> Unit)?,
    editOpen: Boolean,
    editSections: List<Stage>,
    openSection: (Stage) -> Unit,
    panelContent: @Composable ColumnScope.() -> Unit,
    cropOpen: Boolean = false,
    cropPanelContent: @Composable ColumnScope.() -> Unit = {},
) {
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
                visible = (activeCategory != null || cropOpen) && !chromeHidden,
                dimming = interacting,
            ) {
                // The Crop tab uses the same floating spot, so the photo and the bottom bar
                // stay exactly where they are when it opens.
                if (cropOpen) {
                    CropPanel(
                        modifier = Modifier
                            .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                            .onSizeChanged { onPanelHeightChange(it.height) },
                        content = cropPanelContent,
                    )
                } else AdjustmentPanel(
                    modifier = Modifier
                        // M3 bottom sheet max width; centred in wider windows.
                        .widthIn(max = SHEET_MAX_WIDTH_DP.dp)
                        .onSizeChanged { onPanelHeightChange(it.height) }
                        .predictiveBackShrink(panelBackProgress),
                    category = activeCategory,
                    onDismiss = { onActiveCategoryChange(null) },
                    onResetCategory = resetActiveCategory,
                    tall = panelTall,
                    onTallChange = onPanelTallChange,
                    fixedHeight = tabletopPanelHeight,
                    tabs = if (editOpen) emptyList() else activeCategory?.let { categoriesOf(it.stage) }.orEmpty(),
                    dirty = dirty,
                    onSelectTab = { onActiveCategoryChange(it) },
                    sections = if (editOpen) editSections else emptyList(),
                    onSelectSection = openSection,
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
            ) { SliderReadoutPill(sliderReadout, interacting) }
        }

        // --- STAGE ROW (compact and medium windows) ---
        // Tapping the open stage folds the panel away: photo + this row.
        if (!chromeHidden) {
            StageRow(
                active = activeCategory,
                dirty = dirty,
                onOpen = { onActiveCategoryChange(it) },
                onCrop = openCrop,
                lastEdit = lastEdit,
                cropSelected = cropOpen,
            )
        }
    }
}
