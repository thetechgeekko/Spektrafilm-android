/*
 * SpectraFilm for Android — grouped parameter controls.
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
 * The grouped slider/switch panel that edits a SpektraParams tree:
 * Camera / Enlarger / Scanner / Grain / Halation / DIR couplers / Glare.
 * Each control maps to a SpektraParams field and emits a new immutable tree.
 */
package com.spectrafilm.feature.film_emulation.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.SpektraParams

// TODO(host): swap the plain Material3 widgets below for core:ui
//  EnhancedSliderItem (with valueSuffix/steps), PreferenceRowSwitch, and
//  expandable ExpandableItem groups; use core:resources strings for every label.
//  Implemented with stock Material3 so the screen compiles standalone (M1).

/**
 * @param params  the current parameter tree
 * @param onChange emits a fully-rebuilt [SpektraParams]; the component debounces
 *                 these into preview renders.
 */
@Composable
internal fun FilmEmulationControls(
    params: SpektraParams,
    onChange: (SpektraParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CameraGroup(params, onChange)
        EnlargerGroup(params, onChange)
        ScannerGroup(params, onChange)
        GrainGroup(params, onChange)
        HalationGroup(params, onChange)
        DirCouplersGroup(params, onChange)
        GlareGroup(params, onChange)
    }
}

/* ----------------------------- Camera ------------------------------------- */

@Composable
private fun CameraGroup(params: SpektraParams, onChange: (SpektraParams) -> Unit) {
    val cam = params.camera
    ParamGroup(title = "Camera") {
        ParamSwitch("Auto exposure", cam.autoExposure) {
            onChange(params.copy(camera = cam.copy(autoExposure = it)))
        }
        ParamSlider(
            title = "Exposure compensation",
            value = cam.exposureCompensationEv,
            range = -5f..5f,
            suffix = " EV",
        ) { onChange(params.copy(camera = cam.copy(exposureCompensationEv = it))) }
        ParamSlider(
            title = "Lens blur",
            value = cam.lensBlurUm,
            range = 0f..20f,
            suffix = " µm",
        ) { onChange(params.copy(camera = cam.copy(lensBlurUm = it))) }
        ParamSlider(
            title = "Film format",
            value = cam.filmFormatMm,
            range = 8f..120f,
            suffix = " mm",
        ) { onChange(params.copy(camera = cam.copy(filmFormatMm = it))) }
    }
}

/* ----------------------------- Enlarger ----------------------------------- */

@Composable
private fun EnlargerGroup(params: SpektraParams, onChange: (SpektraParams) -> Unit) {
    val enl = params.enlarger
    ParamGroup(title = "Enlarger") {
        ParamSlider(
            title = "Print exposure",
            value = enl.printExposure,
            range = 0f..4f,
        ) { onChange(params.copy(enlarger = enl.copy(printExposure = it))) }
        ParamSlider(
            title = "Yellow filter shift",
            value = enl.yFilterShift,
            range = -50f..50f,
        ) { onChange(params.copy(enlarger = enl.copy(yFilterShift = it))) }
        ParamSlider(
            title = "Magenta filter shift",
            value = enl.mFilterShift,
            range = -50f..50f,
        ) { onChange(params.copy(enlarger = enl.copy(mFilterShift = it))) }
        ParamSlider(
            title = "Lens blur",
            value = enl.lensBlur,
            range = 0f..10f,
        ) { onChange(params.copy(enlarger = enl.copy(lensBlur = it))) }
        ParamSlider(
            title = "Preflash exposure",
            value = enl.preflashExposure,
            range = 0f..1f,
        ) { onChange(params.copy(enlarger = enl.copy(preflashExposure = it))) }
    }
}

/* ----------------------------- Scanner ------------------------------------ */

@Composable
private fun ScannerGroup(params: SpektraParams, onChange: (SpektraParams) -> Unit) {
    val sc = params.scanner
    ParamGroup(title = "Scanner") {
        ParamSlider(
            title = "Lens blur",
            value = sc.lensBlur,
            range = 0f..10f,
        ) { onChange(params.copy(scanner = sc.copy(lensBlur = it))) }
        ParamSwitch("White correction", sc.whiteCorrection) {
            onChange(params.copy(scanner = sc.copy(whiteCorrection = it)))
        }
        ParamSwitch("Black correction", sc.blackCorrection) {
            onChange(params.copy(scanner = sc.copy(blackCorrection = it)))
        }
        ParamSlider(
            title = "White level",
            value = sc.whiteLevel,
            range = 0.5f..1f,
        ) { onChange(params.copy(scanner = sc.copy(whiteLevel = it))) }
        ParamSlider(
            title = "Black level",
            value = sc.blackLevel,
            range = 0f..0.2f,
        ) { onChange(params.copy(scanner = sc.copy(blackLevel = it))) }
        // unsharpMask is a Pair<amount, radius>
        ParamSlider(
            title = "Unsharp amount",
            value = sc.unsharpMask.first,
            range = 0f..3f,
        ) {
            onChange(
                params.copy(
                    scanner = sc.copy(unsharpMask = it to sc.unsharpMask.second)
                )
            )
        }
    }
}

/* ------------------------------- Grain ------------------------------------ */

@Composable
private fun GrainGroup(params: SpektraParams, onChange: (SpektraParams) -> Unit) {
    val grain = params.filmRender.grain
    fun set(g: com.spectrafilm.engine.GrainParams) =
        onChange(params.copy(filmRender = params.filmRender.copy(grain = g)))
    ParamGroup(title = "Grain") {
        ParamSwitch("Active", grain.active) { set(grain.copy(active = it)) }
        ParamSlider(
            title = "Particle area",
            value = grain.agxParticleAreaUm2,
            range = 0.05f..1f,
            suffix = " µm²",
        ) { set(grain.copy(agxParticleAreaUm2 = it)) }
        ParamSlider(
            title = "Blur",
            value = grain.blur,
            range = 0f..2f,
        ) { set(grain.copy(blur = it)) }
        ParamSlider(
            title = "Dye-cloud blur",
            value = grain.blurDyeCloudsUm,
            range = 0f..5f,
            suffix = " µm",
        ) { set(grain.copy(blurDyeCloudsUm = it)) }
        ParamSlider(
            title = "Sub-layers",
            value = grain.nSubLayers.toFloat(),
            range = 1f..5f,
            steps = 3,
        ) { set(grain.copy(nSubLayers = it.toInt())) }
    }
}

/* ----------------------------- Halation ----------------------------------- */

@Composable
private fun HalationGroup(params: SpektraParams, onChange: (SpektraParams) -> Unit) {
    val hal = params.filmRender.halation
    fun set(h: com.spectrafilm.engine.HalationParams) =
        onChange(params.copy(filmRender = params.filmRender.copy(halation = h)))
    ParamGroup(title = "Halation") {
        ParamSwitch("Active", hal.active) { set(hal.copy(active = it)) }
        ParamSlider(
            title = "Halation amount",
            value = hal.halationAmount,
            range = 0f..4f,
        ) { set(hal.copy(halationAmount = it)) }
        ParamSlider(
            title = "Halation spatial scale",
            value = hal.halationSpatialScale,
            range = 0.1f..4f,
        ) { set(hal.copy(halationSpatialScale = it)) }
        ParamSlider(
            title = "Scatter amount",
            value = hal.scatterAmount,
            range = 0f..4f,
        ) { set(hal.copy(scatterAmount = it)) }
        ParamSlider(
            title = "Boost",
            value = hal.boostEv,
            range = 0f..6f,
            suffix = " EV",
        ) { set(hal.copy(boostEv = it)) }
    }
}

/* --------------------------- DIR couplers --------------------------------- */

@Composable
private fun DirCouplersGroup(params: SpektraParams, onChange: (SpektraParams) -> Unit) {
    val dir = params.filmRender.dirCouplers
    fun set(d: com.spectrafilm.engine.DirCouplersParams) =
        onChange(params.copy(filmRender = params.filmRender.copy(dirCouplers = d)))
    ParamGroup(title = "DIR couplers") {
        ParamSwitch("Active", dir.active) { set(dir.copy(active = it)) }
        ParamSlider(
            title = "Amount",
            value = dir.amount,
            range = 0f..3f,
        ) { set(dir.copy(amount = it)) }
        ParamSlider(
            title = "Same-layer inhibition",
            value = dir.inhibitionSamelayer,
            range = 0f..3f,
        ) { set(dir.copy(inhibitionSamelayer = it)) }
        ParamSlider(
            title = "Interlayer inhibition",
            value = dir.inhibitionInterlayer,
            range = 0f..3f,
        ) { set(dir.copy(inhibitionInterlayer = it)) }
        ParamSlider(
            title = "Diffusion size",
            value = dir.diffusionSizeUm,
            range = 0f..100f,
            suffix = " µm",
        ) { set(dir.copy(diffusionSizeUm = it)) }
    }
}

/* ------------------------------- Glare ------------------------------------ */

@Composable
private fun GlareGroup(params: SpektraParams, onChange: (SpektraParams) -> Unit) {
    // Glare lives on both film and print render; edit the film-render one here.
    val glare = params.filmRender.glare
    fun set(g: com.spectrafilm.engine.GlareParams) =
        onChange(params.copy(filmRender = params.filmRender.copy(glare = g)))
    ParamGroup(title = "Glare") {
        ParamSwitch("Active", glare.active) { set(glare.copy(active = it)) }
        ParamSlider(
            title = "Percent",
            value = glare.percent,
            range = 0f..0.5f,
        ) { set(glare.copy(percent = it)) }
        ParamSlider(
            title = "Roughness",
            value = glare.roughness,
            range = 0f..1f,
        ) { set(glare.copy(roughness = it)) }
        ParamSlider(
            title = "Blur",
            value = glare.blur,
            range = 0f..1f,
        ) { set(glare.copy(blur = it)) }
    }
}

/* --------------------------- reusable controls ---------------------------- */

// TODO(host): replace with core:ui ExpandableItem so each group collapses.
@Composable
private fun ParamGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        if (expanded) content()
    }
}

// TODO(host): replace with core:ui EnhancedSliderItem (valueSuffix + canInputValue).
@Composable
private fun ParamSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, modifier = Modifier.weight(1f))
            Text(text = remember(value) { "%.2f$suffix".format(value) })
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

// TODO(host): replace with core:ui PreferenceRowSwitch.
@Composable
private fun ParamSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
