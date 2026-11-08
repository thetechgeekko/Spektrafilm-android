/*
 * Spektrafilm for Android — the editor's window frame. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * #582: moved out of EditorScreen. Stateless over the editor: it owns only its own window
 * state (drop hover, the window origin for the fold hinge) and decides, once, whether the
 * editor lays out as an expanded window. The top bar, the body under it and the full-screen
 * layers above them are slots built by EditorScreen.
 */
package com.spectrafilm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

@Composable
internal fun EditorFrame(
    /** A full-screen tool overlay is up: the chrome beneath is hidden from accessibility (#181). */
    modalOverlayShown: Boolean,
    /** Whether a photo dragged in from another app may be dropped now (ImageDrop.kt). */
    acceptDrop: Boolean,
    /** A photo was dropped; true when it was taken. */
    onDropImage: (DragAndDropEvent) -> Boolean,
    topBar: @Composable (wide: Boolean) -> Unit,
    body: @Composable ColumnScope.(wide: Boolean, foldingFeature: FoldingFeature?) -> Unit,
    overlays: @Composable BoxScope.() -> Unit,
) {
    var dropHover by remember { mutableStateOf(false) }
    val currentOnDropImage by rememberUpdatedState(onDropImage)
    val imageDropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropHover = false
                return currentOnDropImage(event)
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
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        style = Stroke(width = w),
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
            topBar(wide)
            body(wide, foldingFeature)
        }
        overlays()
    }
}
