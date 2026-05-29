/*
 * SpectraFilm for Android — film-emulation screen.
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
 * The Compose screen that drives the spektra-core engine: pick a RAW/image,
 * choose film + print profiles, tune the grouped parameter panel, preview vs
 * full-scan, compare before/after, and export. Mirrors the ImageToolbox
 * <Name>Content.kt idiom (see feature/pick-color/PickColorFromImageContent.kt).
 */
package com.spectrafilm.feature.film_emulation.presentation

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.spectrafilm.feature.film_emulation.presentation.components.FilmEmulationControls
import com.spectrafilm.feature.film_emulation.presentation.components.FilmEmulationTopAppBar
import com.spectrafilm.feature.film_emulation.presentation.components.ProfileKind
import com.spectrafilm.feature.film_emulation.presentation.components.ProfilePickerSheet
import com.spectrafilm.feature.film_emulation.presentation.screenLogic.FilmEmulationComponent
import com.spectrafilm.feature.film_emulation.presentation.screenLogic.RenderMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmEmulationContent(
    component: FilmEmulationComponent,
) {
    // TODO(host): use core:ui content_pickers.rememberImagePicker + AutoFilePicker
    //  so RAW/DNG routes through the LibRaw-backed RawDecoder. Placeholder lambda
    //  keeps the screen self-contained until the host is seeded (M1).
    val pickImage: () -> Unit = {
        // TODO(host): launch picker; on result -> component.pickImage(uri)
        component.initialUri?.let(component::pickImage) ?: component.pickImage(Uri.EMPTY)
    }

    var profileSheetKind by rememberSaveable { mutableStateOf<ProfileKind?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FilmEmulationTopAppBar(
                scrollBehavior = scrollBehavior,
                isProcessing = component.isProcessing,
                canExport = component.sourceBitmap != null,
                onGoBack = component.onGoBack,
                onExport = {
                    component.export { /* TODO(host): hand to core:data ImageSaver */ }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            ImageArea(
                component = component,
                onPickImage = pickImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )

            ProfilePickers(
                filmProfile = component.filmProfile,
                printProfile = component.printProfile,
                onPickFilm = { profileSheetKind = ProfileKind.Film },
                onPickPrint = { profileSheetKind = ProfileKind.Print },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            RenderModeToggle(
                mode = component.renderMode,
                onModeChange = { mode ->
                    when (mode) {
                        RenderMode.Preview -> component.runPreview()
                        RenderMode.FullScan -> component.runFullScan()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )

            // Grouped parameter panel (Camera / Enlarger / Scanner / Grain /
            // Halation / DIR couplers / Glare). Each edit emits a new SpektraParams
            // tree; the component debounces it into a fresh preview render.
            FilmEmulationControls(
                params = component.params,
                onChange = component::updateParams,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    ProfilePickerSheet(
        visible = profileSheetKind != null,
        kind = profileSheetKind ?: ProfileKind.Film,
        profiles = component.availableProfiles,
        selected = when (profileSheetKind) {
            ProfileKind.Print -> component.printProfile
            else -> component.filmProfile
        },
        onSelect = { profile ->
            when (profileSheetKind) {
                ProfileKind.Print -> component.setPrintProfile(profile)
                else -> component.setFilmProfile(profile)
            }
        },
        onDismiss = { profileSheetKind = null },
    )

    // TODO(host): core:ui LoadingDialog(visible = component.isProcessing).

    component.AttachLifecycle()
}

/* --------------------------- image / preview area ------------------------- */

@Composable
private fun ImageArea(
    component: FilmEmulationComponent,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            // before/after compare: press-and-hold shows the original source.
            .pointerInput(component.sourceBitmap) {
                detectTapGestures(
                    onPress = {
                        component.setShowOriginal(true)
                        tryAwaitRelease()
                        component.setShowOriginal(false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // TODO(host): use lib:zoomable ZoomableImage + core:ui Picture to render
        //  either component.previewBitmap (after) or component.sourceBitmap (while
        //  showOriginal). Placeholder text until the host image stack is seeded (M1).
        if (component.sourceBitmap == null) {
            OutlinedButton(onClick = onPickImage) {
                Icon(
                    imageVector = Icons.Rounded.AddPhotoAlternate,
                    contentDescription = null,
                )
                Text(
                    text = "Pick RAW / image", // TODO(host): R.string.pick_image_alt
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            val label = when {
                component.showOriginal -> "Original"
                component.isProcessing -> "Rendering…"
                else -> "Preview" // TODO(host): zoomable rendered bitmap goes here
            }
            Text(text = label)
        }
    }
}

/* ------------------------------ profile pickers --------------------------- */

@Composable
private fun ProfilePickers(
    filmProfile: String,
    printProfile: String,
    onPickFilm: () -> Unit,
    onPickPrint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // TODO(host): core:ui PreferenceItem rows with leading film/paper icons.
        OutlinedButton(onClick = onPickFilm, modifier = Modifier.weight(1f)) {
            Text(text = "Film: $filmProfile")
        }
        OutlinedButton(onClick = onPickPrint, modifier = Modifier.weight(1f)) {
            Text(text = "Print: $printProfile")
        }
    }
}

/* ----------------------------- preview/scan toggle ------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderModeToggle(
    mode: RenderMode,
    onModeChange: (RenderMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        val modes = RenderMode.entries
        modes.forEachIndexed { index, value ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(
                    text = when (value) {
                        RenderMode.Preview -> "Preview"
                        RenderMode.FullScan -> "Full scan"
                    },
                )
            }
        }
    }
}