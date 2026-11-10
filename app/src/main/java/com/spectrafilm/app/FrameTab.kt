/*
 * Spektrafilm for Android -- the Frame tab. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Controls for the export frame (style, width, colour, aspect) and the framed copy of the
 * preview the editor shows while the tab is open. The same controls sit in the export sheet,
 * so a library export can set a frame too. Post-engine and UI-only, like the border itself:
 * no engine param, no parity impact. Every framed image here is painted by
 * [FilmBorderRenderer], the code the export uses, so what the tab shows is what the file gets.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Long edge of the style thumbnails in the Frame tab, in pixels. */
private const val STYLE_THUMB_EDGE = 160

/** Long edge of the framed copy the editor shows while the Frame tab is open. */
private const val FRAMED_PREVIEW_EDGE = 1600

/**
 * A copy of [preview] scaled to [edge] with [spec] painted around it, or null for no frame or
 * an unusable preview. Off the main thread: the scale and the border paint are per-pixel passes.
 */
internal fun framedThumbnail(preview: Bitmap?, spec: FrameSpec, edge: Int, caption: String? = null): Bitmap? {
    if (preview == null || preview.isRecycled || spec.isNone) return null
    return runCatching {
        val small = scaleBitmapToLongEdge(preview, edge)
        try {
            val layout = FilmBorderGeometry.layout(spec, small.width, small.height, caption = caption)
                ?: return@runCatching null
            FilmBorderRenderer.render(small, layout)
        } finally {
            if (small !== preview) small.recycle()
        }
    }.getOrNull()
}

/**
 * [preview] with [spec] painted around it, recomputed off the main thread whenever either
 * changes. The previous image stays up until the next is ready, so dragging the width slider
 * does not flash. Left to the GC rather than recycled: Compose may still draw the old one.
 */
@Composable
internal fun rememberFramedPreview(preview: Bitmap?, spec: FrameSpec, edge: Int, caption: String? = null): Bitmap? {
    val dispatchers = rememberAppDispatchers()
    var framed by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(preview, spec, edge, caption) {
        framed = withContext(dispatchers.default) {
            val source = preview ?: return@withContext null
            acquirePreviewLease(source)?.use { framedThumbnail(it.value, spec, edge, caption) }
        }
    }
    return framed
}

/**
 * The caption [look] gives the photo at [sourceUri] with [filmProfile], worded as the export
 * will word it. The EXIF is read off the main thread, once per photo, and only for a caption
 * that needs it.
 */
@Composable
internal fun rememberFrameCaption(look: FrameLook, sourceUri: Uri?, filmProfile: String): String? {
    val context = LocalContext.current
    val dispatchers = rememberAppDispatchers()
    val needsExif = look.caption == CaptionKind.SHOT_INFO || look.caption == CaptionKind.DATE
    val tags by produceState(emptyMap<String, String>(), sourceUri, needsExif) {
        value = if (sourceUri == null || !needsExif) emptyMap() else {
            withContext(dispatchers.io) { readSourceExif(context.applicationContext, sourceUri).tags }
        }
    }
    val stock = remember(filmProfile, look.caption) {
        if (look.caption == CaptionKind.FILM_STOCK) StockCatalog.displayName(context, filmProfile) else null
    }
    return FrameCaptions.text(look, tags, stock)
}

/** The editor canvas while the Frame tab is open: the preview with its frame, fitted. */
@Composable
internal fun FramedPreviewCanvas(preview: Bitmap, spec: FrameSpec, bottomInset: Dp, caption: String? = null) {
    val framed = rememberFramedPreview(preview, spec, FRAMED_PREVIEW_EDGE, caption)
    // The framed render is ours; the bare preview may be retired and recycled by the
    // editor at any time, so it is only drawn under a read lease.
    val shown = framed?.asImageBitmap() ?: rememberLeasedImage(preview)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset + 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) Image(
            bitmap = shown,
            contentDescription = stringResource(R.string.frame_preview_cd),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The frame controls: style, then width and colour where the style takes them, then aspect,
 * then the caption (paper styles) or the edge markings (strips).
 * [frameless] is true for formats that export the bare photo; the controls stay editable
 * (the choice is kept for the next JPEG/PNG export) under a note saying so. [caption] is the
 * caption as worded for the photo on screen, when there is one photo to word it for
 * ([captionKnown]); it lets the controls say when the photo has nothing to caption with.
 */
@Composable
internal fun FrameControls(
    spec: FrameSpec,
    onChange: (FrameSpec) -> Unit,
    preview: Bitmap?,
    frameless: Boolean,
    modifier: Modifier = Modifier,
    caption: String? = null,
    captionKnown: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (frameless) FrameNote(stringResource(R.string.frame_export_note))
        FrameHeading(stringResource(R.string.frame_style))
        FrameStyleRow(spec, preview, onSelect = { onChange(spec.copy(style = it)) })
        val style = spec.style
        if (style.takesWidth) {
            val defaultPercent = FilmBorderGeometry.defaultWidth(style) * 100f
            val range = FrameLook.WIDTH_RANGE
            EnhancedSlider(
                label = stringResource(R.string.frame_width),
                value = (spec.look.width ?: FilmBorderGeometry.defaultWidth(style)) * 100f,
                range = range.start * 100f..range.endInclusive * 100f,
                step = 0.5f,
                decimals = 1,
                default = defaultPercent,
                onValueChange = { pct ->
                    // Back at the style's own width: store null, so the look stays "default".
                    val w = if (kotlin.math.abs(pct - defaultPercent) < 0.01f) null else pct / 100f
                    onChange(spec.copy(look = spec.look.copy(width = w)))
                },
            )
        }
        if (style.takesColor || spec.look.aspect != FrameAspect.ORIGINAL) {
            FrameHeading(stringResource(R.string.frame_color))
            if (!style.takesColor) FrameNote(stringResource(R.string.frame_strip_color_note))
            FrameColorRow(spec.look.color, onSelect = { onChange(spec.copy(look = spec.look.copy(color = it))) })
        }
        FrameHeading(stringResource(R.string.frame_aspect))
        val aspects = FrameAspect.entries
        SubTabRow(
            tabs = aspects.map { stringResource(it.labelRes) },
            selected = aspects.indexOf(spec.look.aspect),
            onSelect = { onChange(spec.copy(look = spec.look.copy(aspect = aspects[it]))) },
        )
        FrameNote(stringResource(R.string.frame_aspect_note))
        if (style.isStrip) {
            EdgeMarkingControls(spec, onChange)
        } else {
            CaptionControls(spec, onChange, caption, captionKnown)
        }
    }
}

/** What the caption says, its text when custom, and its typeface. */
@Composable
private fun CaptionControls(spec: FrameSpec, onChange: (FrameSpec) -> Unit, caption: String?, captionKnown: Boolean) {
    val look = spec.look
    fun update(l: FrameLook) = onChange(spec.copy(look = l))
    FrameHeading(stringResource(R.string.frame_caption))
    val kinds = CaptionKind.entries
    FrameChipRow(
        labels = kinds.map { stringResource(it.labelRes) },
        selected = kinds.indexOf(look.caption),
        onSelect = { update(look.copy(caption = kinds[it])) },
    )
    if (look.caption == CaptionKind.CUSTOM) {
        OutlinedTextField(
            value = look.captionText,
            onValueChange = { update(look.copy(captionText = it.replace('\n', ' ').take(FrameLook.CAPTION_MAX))) },
            label = { Text(stringResource(R.string.frame_caption_text)) },
            singleLine = true,
            supportingText = { Text("${look.captionText.length} / ${FrameLook.CAPTION_MAX}") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (look.hasCaption) {
        if (captionKnown && caption == null && look.caption != CaptionKind.CUSTOM) {
            FrameNote(stringResource(R.string.frame_caption_missing))
        }
        val fonts = CaptionFont.entries
        SubTabRow(
            tabs = fonts.map { stringResource(it.labelRes) },
            selected = fonts.indexOf(look.captionFont),
            onSelect = { update(look.copy(captionFont = fonts[it])) },
        )
    }
}

/** Whether the strip carries edge text, its label, and a fixed frame number. */
@Composable
private fun EdgeMarkingControls(spec: FrameSpec, onChange: (FrameSpec) -> Unit) {
    val look = spec.look
    fun update(l: FrameLook) = onChange(spec.copy(look = l))
    FrameHeading(stringResource(R.string.frame_markings))
    SwitchRow(
        label = stringResource(R.string.frame_markings_show),
        checked = look.edgeMarkings,
        onCheckedChange = { update(look.copy(edgeMarkings = it)) },
    )
    if (!look.edgeMarkings) return
    OutlinedTextField(
        value = look.edgeLabel,
        // Trailing spaces stay while typing (the next word follows); the layout trims them.
        onValueChange = { raw ->
            val typed = raw.uppercase(java.util.Locale.ROOT).filter { it in ' '..'~' }.trimStart()
            update(look.copy(edgeLabel = typed.take(FrameLook.EDGE_LABEL_MAX)))
        },
        label = { Text(stringResource(R.string.frame_markings_label)) },
        placeholder = { Text(FilmBorderGeometry.EDGE_LABEL) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    EnhancedSlider(
        label = stringResource(R.string.frame_number),
        value = (look.frameNumber ?: 0).toFloat(),
        range = 0f..FrameLook.FRAME_NUMBER_RANGE.last.toFloat(),
        step = 1f,
        decimals = 0,
        default = 0f,
        onValueChange = { v ->
            val n = v.roundToInt()
            update(look.copy(frameNumber = n.takeIf { it in FrameLook.FRAME_NUMBER_RANGE }))
        },
    )
    FrameNote(stringResource(R.string.frame_number_note))
}

/** A one-of-N row of text chips that scrolls sideways when the labels run long. */
@Composable
private fun FrameChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val isSelected = i == selected
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(i) }),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun FrameHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun FrameNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One thumbnail per style, each painted around the current preview. */
@Composable
private fun FrameStyleRow(spec: FrameSpec, preview: Bitmap?, onSelect: (FilmBorder) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilmBorder.entries.forEach { style ->
            // The look follows into the thumbnails (colour, width), but not the aspect: a row
            // of identical padded squares would hide what tells the styles apart.
            val thumbSpec = FrameSpec(style, spec.look.copy(aspect = FrameAspect.ORIGINAL))
            val thumb = rememberFramedPreview(preview, thumbSpec, STYLE_THUMB_EDGE)
            val selected = style == spec.style
            val label = stringResource(style.labelRes)
            Column(
                Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(style) })
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.size(68.dp),
                ) {
                    // Generated thumbnails are ours; the bare preview (None) is the editor's
                    // and is only drawn under a read lease, as it can be recycled at any time.
                    val shown = if (style == FilmBorder.NONE) {
                        preview?.takeUnless { it.isRecycled }?.let { rememberLeasedImage(it) }
                    } else thumb?.asImageBitmap()
                    if (shown != null) {
                        Image(
                            bitmap = shown,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Colour swatches; "Style default" and "From photo" are text chips beside them. */
@Composable
private fun FrameColorRow(selected: FrameColor, onSelect: (FrameColor) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrameColor.entries.forEach { color ->
            val isSelected = color == selected
            val label = stringResource(color.labelRes)
            val ring = if (isSelected) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            }
            val argb = color.argb
            if (argb != null) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(ring, CircleShape)
                        .padding(if (isSelected) 5.dp else 0.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(color) })
                        .semantics { contentDescription = label },
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(color) }),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
