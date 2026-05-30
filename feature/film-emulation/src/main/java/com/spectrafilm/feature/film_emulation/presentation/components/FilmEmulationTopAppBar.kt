/*
 * Spektrafilm for Android — film-emulation top app bar.
 * Copyright (C) 2026 Spektrafilm Android contributors.
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
 * Top bar for the film-emulation screen: back, title, and the export action.
 * Mirrors the ImageToolbox EnhancedTopAppBar idiom (see pick-color).
 */
package com.spectrafilm.feature.film_emulation.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// TODO(host): use core:ui EnhancedTopAppBar + EnhancedIconButton + core:resources Icons,
//  with R.string.film_emulation as the title. Material3 used here so the screen
//  compiles independently of the host (M1).

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FilmEmulationTopAppBar(
    scrollBehavior: TopAppBarScrollBehavior,
    isProcessing: Boolean,
    canExport: Boolean,
    onGoBack: () -> Unit,
    onExport: () -> Unit,
) {
    androidx.compose.material3.TopAppBar(
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            // TODO(host): EnhancedIconButton
            androidx.compose.material3.IconButton(onClick = onGoBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back" // TODO(host): stringResource(R.string.exit)
                )
            }
        },
        title = {
            Text(text = "Film Emulation") // TODO(host): stringResource(R.string.film_emulation)
        },
        actions = {
            // TODO(host): EnhancedIconButton; disable while a full scan is running.
            androidx.compose.material3.IconButton(
                onClick = onExport,
                enabled = canExport && !isProcessing,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Save,
                    contentDescription = "Export" // TODO(host): stringResource(R.string.export)
                )
            }
        },
        modifier = Modifier,
    )
}
