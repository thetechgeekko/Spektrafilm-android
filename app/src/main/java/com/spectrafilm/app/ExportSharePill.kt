/*
 * Spektrafilm for Android — the editor's Export + Share pill. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Export and a small Share button joined as one M3 Expressive connected button pair
 * (user, 2026-11-08: "both buttons stick close to each other like a pill"). The export's
 * progress fills the pill itself instead of a spinning circle, and the result is a Snackbar,
 * not a full-screen badge.
 */
package com.spectrafilm.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import kotlinx.coroutines.delay

internal const val EXPORT_PILL_EXPORT_TAG = "export-pill-export"
internal const val EXPORT_PILL_SHARE_TAG = "export-pill-share"

/** How long the Export half shows its check after a save, before it reads "Export" again. */
internal const val EXPORT_PILL_SAVED_MS = 1_200L

private val PILL_OUTER = 20.dp // full: half of the 40dp button height
private val PILL_PRESSED_INNER = 4.dp // M3 connected group: inner corners tighten on press
private val SHARE_WIDTH = 48.dp

/** The icon-only pill: 4dp padding, Export 48dp, the 2dp connected gap, Share 48dp, 4dp. */
internal val EXPORT_PILL_ICON_WIDTH = 4.dp + SHARE_WIDTH + 2.dp + SHARE_WIDTH + 4.dp

/** The shared fill the export's progress paints, start to end (mirrored in RTL). */
private fun DrawScope.drawProgressFill(fraction: Float, color: Color) {
    if (fraction <= 0f) return
    val w = size.width * fraction.coerceIn(0f, 1f)
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
    drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
}

@Composable
private fun rememberPercentFormat(): NumberFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { NumberFormat.getPercentInstance(locale) }
}

/**
 * The editor's primary action on compact and medium windows: a wide filled Export and a
 * narrow tonal Share in one M3 connected pair (2dp apart, small inner corners). Pressing
 * either half tightens its inner corners and widens it while its neighbour gives way, as in
 * an M3 Expressive button group.
 *
 * While an export runs the Export half fills with the stage-weighted fraction (#466) and
 * shows it as a percentage; both halves ignore taps. After a save ([saved]) it shows a check
 * for [EXPORT_PILL_SAVED_MS]. Hidden, not disabled, when there is nothing to export (the
 * caller's job, as for the old Export button).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExportSharePill(
    exporting: Boolean,
    saved: Boolean,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Room the pill may take. When the labelled pill does not fit (a 360dp phone, large
     * text, a long translation), the Export half becomes a 48dp icon button, so the app bar's
     * other actions never get pushed over each other.
     */
    maxWidth: Dp = Dp.Infinity,
) {
    val motion = MaterialTheme.motionScheme
    val scheme = MaterialTheme.colorScheme
    val exportInteraction = remember { MutableInteractionSource() }
    val shareInteraction = remember { MutableInteractionSource() }
    val exportPressed by exportInteraction.collectIsPressedAsState()
    val sharePressed by shareInteraction.collectIsPressedAsState()
    val shareWidth by animateDpAsState(
        when {
            sharePressed -> SHARE_WIDTH + 8.dp
            exportPressed -> SHARE_WIDTH - 4.dp
            else -> SHARE_WIDTH
        },
        motion.fastSpatialSpec(),
        label = "shareWidth",
    )
    val exportGrow by animateDpAsState(
        if (exportPressed) 4.dp else 0.dp,
        motion.fastSpatialSpec(),
        label = "exportGrow",
    )
    val fraction by ExportWorkRuntime.progress.collectAsStateWithLifecycle()
    val shownFraction by animateFloatAsState(
        if (exporting) fraction else 0f,
        motion.defaultEffectsSpec(),
        label = "exportFill",
    )
    var showCheck by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (saved) {
            showCheck = true
            delay(EXPORT_PILL_SAVED_MS)
        }
        showCheck = false
    }
    val percent = rememberPercentFormat()
    val exportLabel = stringResource(R.string.editor_action_export)
    val busyLabel = stringResource(R.string.editor_exporting)
    val shareLabel = stringResource(R.string.editor_export_share)
    val fill = scheme.primary.copy(alpha = 0.35f)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val labelled = remember(exportLabel, labelStyle, density, maxWidth) {
        if (maxWidth == Dp.Infinity) {
            true
        } else {
            val text = with(density) { measurer.measure(exportLabel, labelStyle).size.width.toDp() }
            // 4 + 12 + icon 18 + 8 + text + 16 + gap 2 + Share 48 + 4
            maxWidth >= text + 112.dp
        }
    }

    Row(
        modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CONNECTED_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextTooltip(stringResource(R.string.editor_tooltip_export_gallery)) {
            Button(
                onClick = { if (!exporting) onExport() },
                shapes = ButtonDefaults.shapes(
                    shape = connectedShape(0, 2),
                    pressedShape = RoundedCornerShape(
                        topStart = PILL_OUTER, bottomStart = PILL_OUTER,
                        topEnd = PILL_PRESSED_INNER, bottomEnd = PILL_PRESSED_INNER,
                    ),
                ),
                // While it renders, the half becomes a progress track: the container drops to
                // primaryContainer and the fill paints over it, so the label stays readable
                // at every fraction.
                colors = if (exporting) {
                    ButtonDefaults.buttonColors(
                        containerColor = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                contentPadding = PaddingValues(0.dp),
                interactionSource = exportInteraction,
                modifier = Modifier
                    .testTag(EXPORT_PILL_EXPORT_TAG)
                    .then(if (labelled) Modifier else Modifier.width(SHARE_WIDTH))
                    .semantics {
                        if (!labelled && !exporting) contentDescription = exportLabel
                        if (exporting) {
                            contentDescription = busyLabel
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                        }
                    },
            ) {
                Row(
                    Modifier
                        .height(ButtonDefaults.MinHeight)
                        .then(if (labelled) Modifier else Modifier.width(SHARE_WIDTH))
                        .drawBehind { drawProgressFill(shownFraction, fill) }
                        .padding(
                            start = if (labelled) 12.dp + exportGrow else 0.dp,
                            end = if (labelled) 16.dp + exportGrow else 0.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (showCheck && !exporting) SpectraIcons.Check else SpectraIcons.Export,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    // The label keeps its width while the percentage shows, so the pill
                    // never jumps as the export starts and ends. Icon-only: the fill alone.
                    if (labelled) Box(contentAlignment = Alignment.Center) {
                        Text(exportLabel, modifier = Modifier.alpha(if (exporting) 0f else 1f))
                        if (exporting) {
                            Text(
                                percent.format(fraction.coerceIn(0f, 1f).toDouble()),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        TextTooltip(shareLabel) {
            FilledTonalButton(
                onClick = { if (!exporting) onShare() },
                shapes = ButtonDefaults.shapes(
                    shape = connectedShape(1, 2),
                    pressedShape = RoundedCornerShape(
                        topStart = PILL_PRESSED_INNER, bottomStart = PILL_PRESSED_INNER,
                        topEnd = PILL_OUTER, bottomEnd = PILL_OUTER,
                    ),
                ),
                contentPadding = PaddingValues(0.dp),
                interactionSource = shareInteraction,
                modifier = Modifier
                    .width(shareWidth)
                    .testTag(EXPORT_PILL_SHARE_TAG)
                    .semantics { contentDescription = shareLabel },
            ) {
                Icon(SpectraIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The render mask's progress: the same pill-shaped fill as the Export half, at a size that
 * reads from across the room, with the percentage on it. Replaces the spinning indicator
 * (user, 2026-11-08: no circle after Export). [onDarkroom] draws it in the darkroom tray's
 * fixed safelight tones instead of the theme's.
 */
@Composable
internal fun ExportProgressPill(onDarkroom: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fraction by ExportWorkRuntime.progress.collectAsStateWithLifecycle()
    val shown by animateFloatAsState(
        fraction,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "maskFill",
    )
    val percent = rememberPercentFormat()
    val track = if (onDarkroom) SpektraBrand.onDarkroom.copy(alpha = 0.12f) else scheme.primaryContainer
    val fill = if (onDarkroom) SpektraBrand.onDarkroom.copy(alpha = 0.30f) else scheme.primary.copy(alpha = 0.35f)
    val text = if (onDarkroom) SpektraBrand.onDarkroom else scheme.onPrimaryContainer
    Box(
        modifier
            .widthIn(min = 200.dp)
            .height(40.dp)
            .clip(CircleShape)
            .background(track)
            .drawBehind { drawProgressFill(shown, fill) }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            percent.format(fraction.coerceIn(0f, 1f).toDouble()),
            color = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/**
 * The exported file as a content URI with the minimum read grant and its exact
 * OutputDescriptor MIME -- never a filesystem path (#162).
 */
private fun exportedIntent(action: String, published: String, mime: String): Intent =
    if (action == Intent.ACTION_SEND) {
        Intent(action).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, Uri.parse(published))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(action).apply {
            setDataAndType(Uri.parse(published), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

/** Hands the exported file to the system share sheet. False when nothing can receive it. */
internal fun shareExportedFile(ctx: Context, published: String, mime: String): Boolean = try {
    ctx.startActivity(
        Intent.createChooser(
            exportedIntent(Intent.ACTION_SEND, published, mime),
            ctx.getString(R.string.editor_share_export_title),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Opens the exported file in a viewer. False when no app can show it. */
internal fun openExportedFile(ctx: Context, published: String, mime: String): Boolean = try {
    ctx.startActivity(exportedIntent(Intent.ACTION_VIEW, published, mime))
    true
} catch (_: ActivityNotFoundException) {
    false
}
