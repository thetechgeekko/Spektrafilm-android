/*
 * Spektrafilm for Android — auto-exposure control. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The auto-exposure switch with its metering-method popup. Split out of Widgets.kt (#273).
 */
package com.spectrafilm.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Converts a snake_case method id to a human-readable Title Case label.
 * e.g. "center_weighted" -> "Center Weighted"
 */
private fun meteringMethodLabel(id: String): String =
    id.split('_').joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercaseChar() }
    }

/**
 * A small drag-handle bar drawn via Canvas — the grabber affordance at the
 * top of the expanded metering list.
 */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(modifier = modifier.size(width = 32.dp, height = 4.dp)) {
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
    }
}

/**
 * Adaptive popup position provider for the metering-method panel.
 *
 * Placement logic:
 *   - If there is enough room ABOVE the anchor (anchorBounds.top >= popupContentSize.height),
 *     place the panel so its bottom edge meets the anchor's top edge (grows upward).
 *   - Otherwise, place the panel so its top edge meets the anchor's bottom edge (grows downward).
 *   - Horizontally, align the panel's left edge to the anchor's left edge.
 *   - Both x and y are clamped to stay fully within the window bounds.
 *
 * The [placeAbove] callback lets the composable know which direction was chosen so
 * the drag-handle can be positioned on the correct edge.
 */
private class MeteringPopupPositionProvider(
    private val onPlacedAbove: (Boolean) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val placeAbove = anchorBounds.top >= popupContentSize.height
        onPlacedAbove(placeAbove)

        val y = if (placeAbove) {
            // Bottom of popup aligns with top of anchor.
            anchorBounds.top - popupContentSize.height
        } else {
            // Top of popup aligns with bottom of anchor.
            anchorBounds.bottom
        }

        val x = anchorBounds.left

        // Clamp so the panel never goes off-screen.
        val clampedX = x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val clampedY = y.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))

        return IntOffset(clampedX, clampedY)
    }
}

/**
 * Lightroom-mobile-style expandable auto-exposure / metering-method control.
 *
 * Collapsed state: a single "Auto" button (OutlinedButton when off, filled Button
 * showing the active method name when on).
 *
 * Expanded state: an elevated Surface rendered in a [Popup] anchored to the Auto
 * button. The popup positions itself ABOVE the button when there is room, or BELOW
 * when the button is near the top of the screen (adaptive vertical anchoring).
 * Tapping outside the popup or pressing Back dismisses it (focusable = true).
 * A drag handle and a swipe gesture also collapse it. Swiping up on the
 * (collapsed) button expands it.
 *
 * State ownership: [autoExposure] / [autoExposureMethod] are owned by the caller
 * (ParamsState fields); expand/collapse is local [remember] state.
 */
@Composable
fun AutoExposureControl(
    autoExposure: Boolean,
    autoExposureMethod: String,
    methods: List<String>,
    onAutoExposureChange: (Boolean) -> Unit,
    onMethodChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // Whether the popup was last placed above the button (drives drag direction logic).
    var popupIsAbove by remember { mutableStateOf(true) }

    // Accumulated vertical drag on the button (swipe-up to expand).
    var buttonDragAccum by remember { mutableFloatStateOf(0f) }
    // Accumulated vertical drag on the list panel (swipe-down to collapse).
    var listDragAccum by remember { mutableFloatStateOf(0f) }
    // Threshold (px) for swipe recognition — ~40dp worth at most densities.
    val swipeThresholdPx = 80f

    // Human-readable label for the current method.
    val currentLabel = meteringMethodLabel(autoExposureMethod)

    val collapse = { expanded = false }

    // The popup position provider is remembered so it is stable across recompositions.
    val positionProvider = remember {
        MeteringPopupPositionProvider(onPlacedAbove = { popupIsAbove = it })
    }

    // The drag handle's swipe-to-collapse has no TalkBack equivalent, so (as Material's bottom
    // sheet handle does) it carries a name plus a "dismiss" accessibility action.
    val handleDescription = stringResource(R.string.widget_ae_drag_handle)
    val dismissLabel = stringResource(R.string.widget_ae_dismiss_menu)
    val handleSemantics = Modifier.semantics(mergeDescendants = true) {
        contentDescription = handleDescription
        dismiss(dismissLabel) { collapse(); true }
    }

    // ----- Popup containing the metering-method panel -----
    // Placed outside the normal layout flow; the anchor Box below provides the bounds.
    // focusable = true enables tap-outside-to-dismiss and back-press dismissal.
    if (expanded) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = collapse,
            properties = PopupProperties(focusable = true),
        ) {
            // Animate on first appearance: slide in from the direction of the button.
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    // If above → slide up (negative = from below the panel's final position).
                    // If below → slide down (positive).
                    initialOffsetY = { fullHeight ->
                        if (popupIsAbove) fullHeight else -fullHeight
                    },
                    animationSpec = tween(220),
                ) + fadeIn(animationSpec = tween(220)),
            ) {
                Surface(
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        // Swipe gesture on the panel to collapse.
                        // When above: swipe DOWN (positive dragAmount) collapses.
                        // When below: swipe UP (negative dragAmount) collapses.
                        .pointerInput(popupIsAbove) {
                            detectVerticalDragGestures(
                                onDragStart = { listDragAccum = 0f },
                                onDragEnd = { listDragAccum = 0f },
                                onDragCancel = { listDragAccum = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    listDragAccum += dragAmount
                                    val shouldCollapse = if (popupIsAbove) {
                                        listDragAccum > swipeThresholdPx   // swipe down
                                    } else {
                                        listDragAccum < -swipeThresholdPx  // swipe up
                                    }
                                    if (shouldCollapse) {
                                        expanded = false
                                        listDragAccum = 0f
                                    }
                                },
                            )
                        },
                    // An M3 menu surface: surfaceContainer at elevation level 2 (3dp shadow), no
                    // tonal tint (m3-design-language, components-selection-input "Menus").
                    shape = CARD_SHAPE,
                    shadowElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier.wrapContentSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        // Drag handle — placed on the edge nearest the button:
                        //   • panel above button → handle at the BOTTOM of the panel.
                        //   • panel below button → handle at the TOP of the panel.
                        // When above we render the main content first, then the handle.
                        if (!popupIsAbove) {
                            // Below: handle at top.
                            Box(
                                modifier = Modifier
                                    .wrapContentSize()
                                    .then(handleSemantics)
                                    .padding(top = 8.dp, bottom = 4.dp)
                                    .align(Alignment.CenterHorizontally),
                                contentAlignment = Alignment.Center,
                            ) {
                                DragHandle()
                            }
                        }

                        Text(
                            stringResource(R.string.widget_ae_metering_method),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .semantics { heading() },
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // The rows form one radio group: exactly one of Off / Manual or a
                        // metering method is selected at a time.
                        Column(Modifier.selectableGroup()) {
                            // "Off / Manual" entry — always at the top.
                            MeteringMethodRow(
                                label = stringResource(R.string.widget_ae_off_manual),
                                isSelected = !autoExposure,
                                onClick = {
                                    onAutoExposureChange(false)
                                    expanded = false
                                },
                            )

                            // One divider separates "Off" from the metering methods (a menu
                            // group divider); the methods themselves are not divided.
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )

                            // All metering methods.
                            methods.forEach { method ->
                                MeteringMethodRow(
                                    label = meteringMethodLabel(method),
                                    isSelected = autoExposure && autoExposureMethod == method,
                                    onClick = {
                                        onAutoExposureChange(true)
                                        onMethodChange(method)
                                        // Keep the list open so the user sees the selection.
                                        // Dismissed via swipe, tap-outside, or back-press.
                                    },
                                )
                            }
                        }

                        if (popupIsAbove) {
                            // Above: handle at bottom — nearest the button.
                            Box(
                                modifier = Modifier
                                    .wrapContentSize()
                                    .then(handleSemantics)
                                    .padding(top = 4.dp, bottom = 8.dp)
                                    .align(Alignment.CenterHorizontally),
                                contentAlignment = Alignment.Center,
                            ) {
                                DragHandle()
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    // ----- Collapsed "Auto" button (stays in normal layout flow) -----
    // This Box is the popup anchor — the Popup() sibling above is positioned relative to it.
    // Tap: if off → turn on and expand. If on → toggle list visibility.
    // Swipe up: expand the list.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { buttonDragAccum = 0f },
                    onDragEnd = { buttonDragAccum = 0f },
                    onDragCancel = { buttonDragAccum = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        buttonDragAccum += dragAmount
                        // Negative dragAmount = upward swipe → expand.
                        if (buttonDragAccum < -swipeThresholdPx) {
                            if (!autoExposure) onAutoExposureChange(true)
                            expanded = true
                            buttonDragAccum = 0f
                        }
                    },
                )
            },
    ) {
        // Both buttons announce as "Auto-exposure, <On, method | Off, manual exposure>, button";
        // the swipe-up gesture needs no accessible twin because a tap does the same thing.
        val aeDescription = stringResource(R.string.widget_ae_description)
        val aeStateOn = stringResource(R.string.widget_ae_state_on, currentLabel)
        val aeStateOff = stringResource(R.string.widget_ae_state_off)
        val chooseLabel = stringResource(R.string.widget_ae_choose_method)
        val turnOnLabel = stringResource(R.string.widget_ae_turn_on)
        TextTooltip(stringResource(R.string.widget_ae_tooltip)) {
        if (autoExposure) {
            // ON: filled/accent button showing active method name.
            Button(
                onClick = {
                    // Tap while on: toggle the list panel.
                    expanded = !expanded
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = aeDescription
                        stateDescription = aeStateOn
                        onClick(label = chooseLabel, action = null)
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    stringResource(R.string.widget_ae_button_on, currentLabel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } else {
            // OFF: outlined button, subtitle "Manual exposure".
            Column(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        // Tap while off: turn on and expand.
                        onAutoExposureChange(true)
                        expanded = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = aeDescription
                            stateDescription = aeStateOff
                            onClick(label = turnOnLabel, action = null)
                        },
                ) {
                    Text(stringResource(R.string.widget_ae_button_off))
                }
                Text(
                    stringResource(R.string.widget_ae_manual_exposure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
        }
        }
    }
}

/**
 * A single row in the metering-method list: label + trailing checkmark when selected.
 */
@Composable
private fun MeteringMethodRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    // Radio-button semantics expose the selection that is otherwise shown only by colour/weight
    // and the drawn checkmark.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = if (isSelected) {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (isSelected) primaryColor else onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            // Was a second checkmark hand-drawn on a Canvas at stroke = 0.10 * width, next to
            // SpectraIcons.Confirm at the icon set's own weight — two different ticks in one
            // app. The Canvas existed to avoid a material-icons dependency, which the app's
            // own vector already avoids.
            Icon(
                SpectraIcons.Confirm,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
