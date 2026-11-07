/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * The editor's Photo info sheet: a read-only, grouped two-line list of [PhotoInfo]. Labels sit
 * in onSurfaceVariant above values in onSurface; the sheet is capped at 640dp wide so it reads
 * as a list on tablets and in landscape rather than a stretched table.
 */
package com.spectrafilm.app

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Display rows, in sheet order: group, then (field, value) pairs. Empty groups are left out. */
internal fun PhotoInfo.rows(
    sizeText: (Long) -> String,
    locationText: (Boolean) -> String,
): List<Pair<PhotoInfoGroup, List<Pair<PhotoInfoField, String>>>> =
    PhotoInfoGroup.entries.mapNotNull { group ->
        val items = PhotoInfoField.entries.filter { it.group == group }.mapNotNull { field ->
            when (field) {
                PhotoInfoField.SIZE -> sizeBytes?.let { field to sizeText(it) }
                PhotoInfoField.LOCATION -> field to locationText(hasLocation)
                else -> values[field]?.let { field to it }
            }
        }
        if (items.isEmpty()) null else group to items
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoInfoSheet(info: PhotoInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val present = stringResource(R.string.photo_info_location_present)
    val absent = stringResource(R.string.photo_info_location_absent)
    val rows = info.rows(
        sizeText = { Formatter.formatShortFileSize(context, it) },
        locationText = { if (it) present else absent },
    )
    val hasCameraFacts = info.values.keys.any { it.group != PhotoInfoGroup.FILE }
    // Fully expanded or hidden, no half-height stop: Back (and a swipe down) then closes the
    // sheet in one step, as M3 expects of a short read-only sheet, instead of first
    // collapsing it to a partial height.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.photo_info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (!hasCameraFacts) {
                Text(
                    stringResource(R.string.photo_info_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // The same grouped list as Settings: one rounded container per group, rows split
            // by small gaps rather than dividers.
            rows.forEach { (group, items) ->
                Text(
                    stringResource(group.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
                        .semantics { heading() },
                )
                Column(verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
                    items.forEachIndexed { i, (field, value) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = segmentShape(i, items.size),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .semantics(mergeDescendants = true) {}
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    stringResource(field.labelRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
