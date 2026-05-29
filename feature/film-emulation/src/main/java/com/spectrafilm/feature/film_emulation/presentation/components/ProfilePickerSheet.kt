/*
 * SpectraFilm for Android — film/print profile picker sheet.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Bottom sheet that lists the bundled spektra-core profiles (film or print) and
 * lets the user pick one. Mirrors the ImageToolbox EnhancedModalBottomSheet idiom.
 */
package com.spectrafilm.feature.film_emulation.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Which profile the sheet is editing — drives the title and the bundled list. */
internal enum class ProfileKind { Film, Print }

// TODO(host): use core:ui EnhancedModalBottomSheet + PreferenceItem rows + a
//  DataSelector-style single-choice list, and core:resources strings/icons. Built
//  with plain Material3 here so the screen compiles independently of the host (M1).

@Composable
internal fun ProfilePickerSheet(
    visible: Boolean,
    kind: ProfileKind,
    profiles: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    // TODO(host): EnhancedModalBottomSheet(visible, onDismiss, dragHandle, title = ...)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = when (kind) {
                    ProfileKind.Film -> "Film stock"   // TODO(host): R.string.film_profile
                    ProfileKind.Print -> "Print paper"  // TODO(host): R.string.print_profile
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(profiles) { profile ->
                    ProfileRow(
                        label = profile,
                        selected = profile == selected,
                        onClick = {
                            onSelect(profile)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        if (selected) {
            Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
        }
    }
}
