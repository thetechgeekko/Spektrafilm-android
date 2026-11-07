/*
 * Spektrafilm for Android -- a single-choice M3 connected button group. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A one-of-N picker as an M3 connected button group: equal-width [ConnectedToggle] segments
 * joined by [CONNECTED_GAP], announced as a radio group. The M3 Expressive replacement for a
 * row of single-select filter chips or segmented buttons; keep it to short labels (about
 * four segments at phone width).
 */
@Composable
internal fun <T> ConnectedChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CONNECTED_GAP),
    ) {
        options.forEachIndexed { index, (value, label) ->
            ConnectedToggle(
                label = label,
                selected = value == selected,
                shape = connectedShape(index, options.size),
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
