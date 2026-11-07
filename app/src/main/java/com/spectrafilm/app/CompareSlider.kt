/*
 * Spektrafilm for Android — before/after compare slider. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Split out of Viewer.kt (#273).
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A before/after split viewer. The [after] bitmap is shown across the whole frame; the
 * [before] bitmap is clipped to the left of a draggable vertical handle, revealing the
 * input where the handle hasn't passed. Drag the handle (or anywhere in the frame) left/
 * right to wipe between the two. Both bitmaps are drawn with ContentScale.Fit so they
 * register exactly.
 */
@Composable
fun CompareSlider(
    before: Bitmap,
    after: Bitmap,
    modifier: Modifier = Modifier,
) {
    var split by remember { mutableFloatStateOf(0.5f) }
    var width by remember { mutableIntStateOf(0) }
    val aspect = after.width.toFloat() / after.height.toFloat()
    val beforeImg = rememberLeasedImage(before) ?: return
    val afterImg = rememberLeasedImage(after) ?: return
    // Drag/tap-only wipe: one merged node describing the comparison, its split as state, and
    // three custom actions so a screen reader can move the wipe without a gesture.
    val compareDesc = stringResource(R.string.tool_viewer_compare_desc)
    val compareState = stringResource(R.string.tool_viewer_compare_state, (split * 100f).roundToInt())
    val showBefore = stringResource(R.string.tool_viewer_compare_show_before)
    val showAfter = stringResource(R.string.tool_viewer_compare_show_after)
    val splitHalf = stringResource(R.string.tool_viewer_compare_split_half)

    Box(
        // Fit both dimensions, as ZoomableImage's Image does (see the note there).
        modifier = modifier
            .aspectRatio(aspect)
            .clipToBounds()
            .onSizeChanged { width = it.width }
            .semantics(mergeDescendants = true) {
                contentDescription = compareDesc
                stateDescription = compareState
                customActions = listOf(
                    CustomAccessibilityAction(showBefore) { split = 1f; true },
                    CustomAccessibilityAction(showAfter) { split = 0f; true },
                    CustomAccessibilityAction(splitHalf) { split = 0.5f; true },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (width > 0) split = (pos.x / width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, _, _, _ ->
                    if (width > 0) split = (centroid.x / width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // After (full frame). Both images are described by the parent node, so no per-image cd.
        Image(afterImg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        // Before, clipped to the left of the split via a draw-phase rect clip.
        Image(
            bitmap = beforeImg,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                },
        )
        // Handle + labels.
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * split
                drawLine(
                    color = SpektraBrand.onCanvas,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3f,
                )
            }
            CompareTag(stringResource(R.string.tool_viewer_before_tag), Alignment.TopStart)
            CompareTag(stringResource(R.string.tool_viewer_after_tag), Alignment.TopEnd)
        }
    }
}

@Composable
private fun CompareTag(text: String, alignment: Alignment) {
    Box(Modifier.fillMaxSize()) {
        Text(
            text,
            color = SpektraBrand.onCanvas,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(alignment)
                .padding(6.dp)
                .background(SpektraBrand.scrim.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
