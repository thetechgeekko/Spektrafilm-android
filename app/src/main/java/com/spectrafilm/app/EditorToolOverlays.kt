/*
 * Spektrafilm for Android — the editor's full-screen tool overlays. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The full-screen editing tools drawn over the photo: the crop overlay, the on-photo mask
 * geometry editor and the eyedropper (white-balance gray point or a mask's color/tone target).
 * Moved verbatim out of EditorScreen (#273); the caller keeps the predictive-back wrapper.
 */
@Composable
internal fun MainActivity.EditorToolOverlays(
    ctx: Context,
    scope: CoroutineScope,
    state: ParamsState,
    toolUi: EditorToolUiState,
    cropBmp: Bitmap?,
    cropShown: Boolean,
    maskGeometryShown: Boolean,
    sampleReady: Boolean,
    loadSourceCachedForZoom: suspend (maxEdge: Int) -> DecodedSourceCache.Lease,
    previewTickState: MutableIntState,
    rotationState: MutableState<SourceRotation>,
    statusState: MutableState<String>,
    snackbarHost: SnackbarHostState,
) {
    var previewTick by previewTickState
    var rotation by rotationState
    var status by statusState
    // --- interactive crop overlay (Lightroom-style) ---
    // Only shown once a preview bitmap exists (the crop tool needs the image
    // to draw and to read its aspect ratio).
    if (cropShown && cropBmp != null) {
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
                toolVm.onAction(EditorToolAction.CloseCrop)
                previewTick++
            },
            onCancel = { toolVm.onAction(EditorToolAction.CloseCrop) },
        )
    }

    // --- draw-on-the-preview mask geometry editor ---
    if (maskGeometryShown && cropBmp != null) {
        MaskGeometryOverlay(
            bitmap = cropBmp,
            mask = state.localAdjustments[toolUi.maskIndex].mask,
            onConfirm = { updated ->
                val maskIndex = toolVm.current.maskIndex
                val list = state.localAdjustments.toMutableList()
                list[maskIndex] = list[maskIndex].copy(mask = updated)
                state.localAdjustments = list
                toolVm.onAction(EditorToolAction.CloseMaskGeometry)
                previewTick++
            },
            onCancel = { toolVm.onAction(EditorToolAction.CloseMaskGeometry) },
        )
    }

    // --- eyedropper: WB gray-point, or a color-/luminance-range mask target ---
    if (sampleReady) {
        PixelSampleOverlay(
            bitmap = requireNotNull(cropBmp),
            title = when {
                toolUi.sampleWb -> stringResource(R.string.editor_sample_title_wb)
                toolUi.sampleLuminance -> stringResource(R.string.editor_sample_title_tone)
                else -> stringResource(R.string.editor_sample_title_color)
            },
            hint = when {
                toolUi.sampleWb -> stringResource(R.string.editor_sample_hint_wb)
                toolUi.sampleLuminance -> stringResource(R.string.editor_sample_hint_tone)
                else -> stringResource(R.string.editor_sample_hint_color)
            },
            onPick = { r, g, b, nx, ny ->
                val tool = toolVm.current
                if (tool.sampleWb) {
                    toolVm.onAction(EditorToolAction.CloseSample)
                    // Sample the engine INPUT (linear ProPhoto — creative WB's space) at the tap
                    // and solve the WB that neutralizes it. Off the main thread (decode + crop).
                    scope.launch {
                        runCatching {
                            val avg = loadSourceCachedForZoom(MAX_EDGE_PX).use { fullLease ->
                                withContext(dispatchers.default) {
                                    val crop = cropLinearImageRect(fullLease.image, nx, ny, 5, 5)
                                    try { avgRgb(crop) } finally { crop.close() }
                                }
                            }
                            CreativeWhiteBalance.solveNeutral(
                                avg.first, avg.second, avg.third,
                                state.creativeWbTemp, state.creativeWbTint,
                            )
                        }.onSuccess { (t, ti) ->
                            state.creativeWbTemp = t; state.creativeWbTint = ti
                            status = ctx.getString(R.string.editor_status_wb_set)
                        }.onFailure {
                            Diag.w("white balance sample failed: ${it.message}")
                            scope.showEditorMessage(
                                snackbarHost,
                                ctx.getString(R.string.editor_msg_wb_sample_failed),
                            )
                        }
                    }
                } else {
                    val list = state.localAdjustments.toMutableList()
                    val m = list[tool.maskIndex].mask
                    val nm = if (tool.sampleLuminance) {
                        m.copy(luminanceRange = com.spectrafilm.app.masks.LuminanceRange.fromSample(r, g, b))
                    } else {
                        val cr = m.colorRange ?: com.spectrafilm.app.masks.ColorRange()
                        m.copy(colorRange = cr.copy(targetR = r, targetG = g, targetB = b))
                    }
                    list[tool.maskIndex] = list[tool.maskIndex].copy(mask = nm)
                    state.localAdjustments = list
                    toolVm.onAction(EditorToolAction.MaskSampleApplied)
                    previewTick++
                }
            },
            onCancel = { toolVm.onAction(EditorToolAction.CloseSample) },
        )
    }
}

private fun avgRgb(img: com.spectrafilm.engine.LinearImage): Triple<Float, Float, Float> {
    return img.acquireDataLease().use { lease ->
        val data = lease.data
        val f = data.asFloatBuffer()
        val n = (img.width * img.height).coerceAtLeast(1)
        var r = 0f; var g = 0f; var b = 0f
        for (i in 0 until n) {
            val k = i * 3
            r += f.get(k); g += f.get(k + 1); b += f.get(k + 2)
        }
        val inv = 1f / n
        Triple(r * inv, g * inv, b * inv)
    }
}
