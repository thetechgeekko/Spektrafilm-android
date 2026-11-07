/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Simulation tool panels (#273, first MainActivity split): scene latitude, date stamp,
 * diffusion, grain, preflash, halation, couplers, glare, experimental and display sections.
 * Stateless over [ParamsState]; moved verbatim out of MainActivity.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

@Composable
internal fun SceneLatitudeGroup(s: ParamsState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(stringResource(R.string.editor_sim_scene_latitude), s.sceneLatitudeActive,
            { s.sceneLatitudeActive = it },
            stringResource(R.string.editor_sim_scene_latitude_tooltip))
        if (s.sceneLatitudeActive) {
            EnhancedSlider(stringResource(R.string.editor_sim_scene_latitude_highlight_knee),
                s.sceneLatitudeHighlightKnee, 0f..6f, { s.sceneLatitudeHighlightKnee = it },
                step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_sim_scene_latitude_highlight_knee_tooltip),
                default = PARAM_DEFAULTS.sceneLatitudeHighlightKnee)
            EnhancedSlider(stringResource(R.string.editor_sim_scene_latitude_highlight_room),
                s.sceneLatitudeHighlightRoom, 0f..8f, { s.sceneLatitudeHighlightRoom = it },
                step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_sim_scene_latitude_highlight_room_tooltip),
                default = PARAM_DEFAULTS.sceneLatitudeHighlightRoom)
            EnhancedSlider(stringResource(R.string.editor_sim_scene_latitude_shadow_knee),
                s.sceneLatitudeShadowKnee, -6f..0f, { s.sceneLatitudeShadowKnee = it },
                step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_sim_scene_latitude_shadow_knee_tooltip),
                default = PARAM_DEFAULTS.sceneLatitudeShadowKnee)
            EnhancedSlider(stringResource(R.string.editor_sim_scene_latitude_shadow_room),
                s.sceneLatitudeShadowRoom, 0f..8f, { s.sceneLatitudeShadowRoom = it },
                step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_sim_scene_latitude_shadow_room_tooltip),
                default = PARAM_DEFAULTS.sceneLatitudeShadowRoom)
            EnhancedSlider(stringResource(R.string.editor_sim_scene_latitude_rolloff),
                s.sceneLatitudeRolloff, 1f..4f, { s.sceneLatitudeRolloff = it },
                step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_sim_scene_latitude_rolloff_tooltip),
                default = PARAM_DEFAULTS.sceneLatitudeRolloff)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DateStampGroup(s: ParamsState, photoDateStamp: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(stringResource(R.string.editor_sim_date_stamp), s.dateStampActive,
            { on ->
                // A camera date back prints the day the picture was taken: fill that in
                // the first time it is switched on, rather than switching on a blank.
                if (on && s.dateStampText.isBlank()) {
                    s.dateStampText = photoDateStamp ?: DateStampText.today()
                }
                s.dateStampActive = on
            },
            stringResource(R.string.editor_sim_date_stamp_tooltip))
        if (s.dateStampActive) {
            OutlinedTextField(
                value = s.dateStampText,
                onValueChange = { s.dateStampText = DateStampText.sanitize(it) },
                label = { Text(stringResource(R.string.editor_sim_date_stamp_text)) },
                supportingText = { Text(stringResource(R.string.editor_sim_date_stamp_text_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { photoDateStamp?.let { s.dateStampText = it } },
                    enabled = photoDateStamp != null,
                ) { Text(stringResource(R.string.editor_sim_date_stamp_photo_date)) }
                OutlinedButton(onClick = { s.dateStampText = DateStampText.today() }) {
                    Text(stringResource(R.string.editor_sim_date_stamp_today))
                }
            }
            Text(stringResource(R.string.editor_sim_date_stamp_corner),
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    0 to R.string.editor_sim_date_stamp_corner_br,
                    1 to R.string.editor_sim_date_stamp_corner_bl,
                    2 to R.string.editor_sim_date_stamp_corner_tr,
                    3 to R.string.editor_sim_date_stamp_corner_tl,
                ).forEach { (corner, label) ->
                    FilterChip(selected = s.dateStampCorner == corner,
                        onClick = { s.dateStampCorner = corner },
                        label = { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.editor_sim_date_stamp_color),
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DATE_STAMP_COLORS.forEach { (color, label) ->
                    FilterChip(selected = s.dateStampColor == color,
                        onClick = { s.dateStampColor = color },
                        label = { Text(stringResource(label)) })
                }
            }
            EnhancedSlider(stringResource(R.string.editor_sim_date_stamp_size),
                s.dateStampHeight * 100f, 2f..10f, { s.dateStampHeight = it / 100f },
                step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_sim_date_stamp_size_tooltip),
                default = PARAM_DEFAULTS.dateStampHeight * 100f)
            EnhancedSlider(stringResource(R.string.editor_sim_date_stamp_margin),
                s.dateStampMargin * 100f, 0f..15f, { s.dateStampMargin = it / 100f },
                step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_sim_date_stamp_margin_tooltip),
                default = PARAM_DEFAULTS.dateStampMargin * 100f)
            EnhancedSlider(stringResource(R.string.editor_sim_date_stamp_brightness),
                s.dateStampExposureEv, 0f..5f, { s.dateStampExposureEv = it },
                step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_sim_date_stamp_brightness_tooltip),
                default = PARAM_DEFAULTS.dateStampExposureEv)
        }
    }
}

@Composable
internal fun DiffusionGroup(title: String, d: DiffusionState) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(title, d.active, { d.active = it },
            stringResource(R.string.editor_diff_toggle_tooltip))
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.editor_diff_hide_details else R.string.editor_diff_show_details))
        }
        if (expanded) {
            Dropdown(stringResource(R.string.editor_diff_family), d.family, DIFFUSION_FAMILIES, { it }, { d.family = it })
            EnhancedSlider(stringResource(R.string.editor_diff_strength), d.strength, 0f..2f, { d.strength = it },
                step = 0.125f, decimals = 3,
                tooltip = stringResource(R.string.editor_diff_strength_tooltip),
                default = PARAM_DEFAULTS.cameraDiffusionState.strength)
            EnhancedSlider(stringResource(R.string.editor_diff_spatial_scale), d.spatialScale, 0f..4f, { d.spatialScale = it },
                step = 0.1f, decimals = 2,
                tooltip = stringResource(R.string.editor_diff_spatial_scale_tooltip),
                default = PARAM_DEFAULTS.cameraDiffusionState.spatialScale)
            EnhancedSlider(stringResource(R.string.editor_diff_halo_warmth), d.haloWarmth, -1.5f..1.5f, { d.haloWarmth = it },
                step = 0.05f, decimals = 2, default = 0f,
                tooltip = stringResource(R.string.editor_diff_halo_warmth_tooltip))
            EnhancedSlider(stringResource(R.string.editor_diff_core_intensity), d.coreIntensity, 0f..4f, { d.coreIntensity = it },
                step = 0.05f, decimals = 2, default = PARAM_DEFAULTS.cameraDiffusionState.coreIntensity)
            EnhancedSlider(stringResource(R.string.editor_diff_core_size), d.coreSize, 0.1f..4f, { d.coreSize = it },
                step = 0.05f, decimals = 2, default = PARAM_DEFAULTS.cameraDiffusionState.coreSize)
            EnhancedSlider(stringResource(R.string.editor_diff_halo_intensity), d.haloIntensity, 0f..4f, { d.haloIntensity = it },
                step = 0.05f, decimals = 2, default = PARAM_DEFAULTS.cameraDiffusionState.haloIntensity)
            EnhancedSlider(stringResource(R.string.editor_diff_halo_size), d.haloSize, 0.1f..4f, { d.haloSize = it },
                step = 0.05f, decimals = 2, default = PARAM_DEFAULTS.cameraDiffusionState.haloSize)
            EnhancedSlider(stringResource(R.string.editor_diff_bloom_intensity), d.bloomIntensity, 0f..4f, { d.bloomIntensity = it },
                step = 0.05f, decimals = 2, default = PARAM_DEFAULTS.cameraDiffusionState.bloomIntensity)
            EnhancedSlider(stringResource(R.string.editor_diff_bloom_size), d.bloomSize, 0.1f..4f, { d.bloomSize = it },
                step = 0.05f, decimals = 2, default = PARAM_DEFAULTS.cameraDiffusionState.bloomSize)
        }
    }
}

@Composable
internal fun GrainSection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_grain_title), expanded, { expanded = it }, enabledSwitch = s.grainActive,
        onEnabledChange = { s.grainActive = it }, help = ParamHelpText.forKey(ParamHelpText.GRAIN)) {
        var advanced by remember { mutableStateOf(false) }
        // Basic: the two knobs most users reach for — grain size (ISO) and softness.
        EnhancedSlider(stringResource(R.string.editor_grain_particle_area), s.grainParticleAreaUm2, OperationalParamLimits.GRAIN_PARTICLE_AREA_UM2, { s.grainParticleAreaUm2 = it },
            step = 0.2f, decimals = 2,
            tooltip = stringResource(R.string.editor_grain_particle_area_tooltip),
            default = PARAM_DEFAULTS.grainParticleAreaUm2)
        EnhancedSlider(stringResource(R.string.editor_grain_blur), s.grainBlur, 0f..3f, { s.grainBlur = it },
            step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_grain_blur_tooltip), default = PARAM_DEFAULTS.grainBlur)
        AdvancedToggle(advanced) { advanced = it }
        if (advanced) {
            SwitchRow(stringResource(R.string.editor_grain_sublayers_active), s.grainSublayersActive, { s.grainSublayersActive = it })
            TripleSlider(stringResource(R.string.editor_grain_particle_scale), s.grainParticleScale, OperationalParamLimits.GRAIN_PARTICLE_SCALE, { s.grainParticleScale = it },
                step = 0.1f, decimals = 2, tooltip = stringResource(R.string.editor_grain_particle_scale_tooltip),
                default = PARAM_DEFAULTS.grainParticleScale)
            TripleSlider(stringResource(R.string.editor_grain_particle_scale_layers), s.grainParticleScaleLayers, OperationalParamLimits.GRAIN_PARTICLE_SCALE_LAYERS,
                { s.grainParticleScaleLayers = it }, step = 0.25f, decimals = 2,
                tooltip = stringResource(R.string.editor_grain_particle_scale_layers_tooltip),
                default = PARAM_DEFAULTS.grainParticleScaleLayers)
            TripleSlider(stringResource(R.string.editor_grain_density_min), s.grainDensityMin, OperationalParamLimits.GRAIN_DENSITY_MIN, { s.grainDensityMin = it },
                step = 0.01f, decimals = 3, tooltip = stringResource(R.string.editor_grain_density_min_tooltip),
                default = PARAM_DEFAULTS.grainDensityMin)
            TripleSlider(stringResource(R.string.editor_grain_uniformity), s.grainUniformity, 0.5f..1f, { s.grainUniformity = it },
                step = 0.005f, decimals = 3, tooltip = stringResource(R.string.editor_grain_uniformity_tooltip),
                default = PARAM_DEFAULTS.grainUniformity)
            EnhancedSlider(stringResource(R.string.editor_grain_blur_dye_clouds), s.grainBlurDyeCloudsUm, 0f..5f, { s.grainBlurDyeCloudsUm = it },
                step = 0.1f, decimals = 2,
                tooltip = stringResource(R.string.editor_grain_blur_dye_clouds_tooltip),
                default = PARAM_DEFAULTS.grainBlurDyeCloudsUm)
            PairSlider(stringResource(R.string.editor_grain_micro_structure), s.grainMicroStructure, 0f..100f, { s.grainMicroStructure = it },
                step = 0.1f, decimals = 2,
                tooltip = stringResource(R.string.editor_grain_micro_structure_tooltip),
                componentLabels = "σ" to "nm", default = PARAM_DEFAULTS.grainMicroStructure)
            IntSlider(stringResource(R.string.editor_grain_sublayers), s.grainNSubLayers, OperationalParamLimits.GRAIN_SUBLAYERS, { s.grainNSubLayers = it },
                default = PARAM_DEFAULTS.grainNSubLayers)
        }
        Divider()
        // Granular reset scope (backlog #F): restore the grain parameters to engine
        // defaults without touching the section's on/off switch or other sections.
        OutlinedButton(
            onClick = {
                val d = PARAM_DEFAULTS
                s.grainSublayersActive = d.grainSublayersActive
                s.grainParticleAreaUm2 = d.grainParticleAreaUm2
                s.grainParticleScale = d.grainParticleScale
                s.grainParticleScaleLayers = d.grainParticleScaleLayers
                s.grainDensityMin = d.grainDensityMin
                s.grainUniformity = d.grainUniformity
                s.grainBlur = d.grainBlur
                s.grainBlurDyeCloudsUm = d.grainBlurDyeCloudsUm
                s.grainMicroStructure = d.grainMicroStructure
                s.grainNSubLayers = d.grainNSubLayers
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.editor_grain_reset)) }
    }
}

@Composable
internal fun PreflashSection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_preflash_title), expanded, { expanded = it }, help = ParamHelpText.forKey(ParamHelpText.PREFLASH)) {
        EnhancedSlider(stringResource(R.string.editor_preflash_exposure), s.preflashExposure, 0f..2f, { s.preflashExposure = it },
            step = 0.005f, decimals = 3,
            tooltip = stringResource(R.string.editor_preflash_exposure_tooltip),
            default = PARAM_DEFAULTS.preflashExposure)
        EnhancedSlider(stringResource(R.string.editor_preflash_y_shift), s.preflashYFilterShift, -20f..20f, { s.preflashYFilterShift = it },
            step = 1f, decimals = 0, default = 0f,
            tooltip = stringResource(R.string.editor_preflash_y_shift_tooltip))
        EnhancedSlider(stringResource(R.string.editor_preflash_m_shift), s.preflashMFilterShift, -20f..20f, { s.preflashMFilterShift = it },
            step = 1f, decimals = 0, default = 0f,
            tooltip = stringResource(R.string.editor_preflash_m_shift_tooltip))
    }
}

@Composable
internal fun HalationSection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_hal_title), expanded, { expanded = it }, enabledSwitch = s.halationActive,
        onEnabledChange = { s.halationActive = it }, help = ParamHelpText.forKey(ParamHelpText.HALATION)) {
        var advanced by remember { mutableStateOf(false) }
        // Basic: glow strength + size, the scatter strength, and the highlight boost.
        EnhancedSlider(stringResource(R.string.editor_hal_amount), s.halHalationAmount, 0f..4f, { s.halHalationAmount = it },
            step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_hal_amount_tooltip),
            default = PARAM_DEFAULTS.halHalationAmount)
        EnhancedSlider(stringResource(R.string.editor_hal_spatial_scale), s.halHalationSpatialScale, 0f..4f,
            { s.halHalationSpatialScale = it }, step = 0.1f, decimals = 2,
            tooltip = stringResource(R.string.editor_hal_spatial_scale_tooltip),
            default = PARAM_DEFAULTS.halHalationSpatialScale)
        EnhancedSlider(stringResource(R.string.editor_hal_scatter_amount), s.halScatterAmount, 0f..4f, { s.halScatterAmount = it },
            step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_hal_scatter_amount_tooltip),
            default = PARAM_DEFAULTS.halScatterAmount)
        EnhancedSlider(stringResource(R.string.editor_hal_boost_ev), s.halBoostEv, 0f..6f, { s.halBoostEv = it },
            step = 0.5f, decimals = 1,
            tooltip = stringResource(R.string.editor_hal_boost_ev_tooltip), default = PARAM_DEFAULTS.halBoostEv)
        AdvancedToggle(advanced) { advanced = it }
        if (advanced) {
            EnhancedSlider(stringResource(R.string.editor_hal_scatter_spatial_scale), s.halScatterSpatialScale, 0f..4f,
                { s.halScatterSpatialScale = it }, step = 0.1f, decimals = 2,
                tooltip = stringResource(R.string.editor_hal_scatter_spatial_scale_tooltip),
                default = PARAM_DEFAULTS.halScatterSpatialScale)
            EnhancedSlider(stringResource(R.string.editor_hal_protect_ev), s.halProtectEv, 0f..10f, { s.halProtectEv = it },
                step = 0.5f, decimals = 1,
                tooltip = stringResource(R.string.editor_hal_protect_ev_tooltip),
                default = PARAM_DEFAULTS.halProtectEv)
            EnhancedSlider(stringResource(R.string.editor_hal_boost_range), s.halBoostRange, 0f..1f, { s.halBoostRange = it },
                step = 0.05f, decimals = 2,
                tooltip = stringResource(R.string.editor_hal_boost_range_tooltip),
                default = PARAM_DEFAULTS.halBoostRange)
            TripleSlider(stringResource(R.string.editor_hal_scatter_core), s.halScatterCoreUm, 0f..20f, { s.halScatterCoreUm = it },
                step = 0.5f, decimals = 2, tooltip = stringResource(R.string.editor_hal_scatter_core_tooltip),
                default = PARAM_DEFAULTS.halScatterCoreUm)
            TripleSlider(stringResource(R.string.editor_hal_scatter_tail), s.halScatterTailUm, 0f..40f, { s.halScatterTailUm = it },
                step = 1f, decimals = 1, tooltip = stringResource(R.string.editor_hal_scatter_tail_tooltip),
                default = PARAM_DEFAULTS.halScatterTailUm)
            TripleSlider(stringResource(R.string.editor_hal_scatter_tail_weight), s.halScatterTailWeightPct, 0f..100f,
                { s.halScatterTailWeightPct = it }, step = 1f, decimals = 1,
                tooltip = stringResource(R.string.editor_hal_scatter_tail_weight_tooltip),
                default = PARAM_DEFAULTS.halScatterTailWeightPct)
            TripleSlider(stringResource(R.string.editor_hal_strength), s.halHalationStrengthPct, 0f..100f,
                { s.halHalationStrengthPct = it }, step = 0.5f, decimals = 2,
                tooltip = stringResource(R.string.editor_hal_strength_tooltip),
                default = PARAM_DEFAULTS.halHalationStrengthPct)
            TripleSlider(stringResource(R.string.editor_hal_first_sigma), s.halFirstSigmaUm, 0f..200f, { s.halFirstSigmaUm = it },
                step = 1f, decimals = 1, tooltip = stringResource(R.string.editor_hal_first_sigma_tooltip),
                default = PARAM_DEFAULTS.halFirstSigmaUm)
            IntSlider(stringResource(R.string.editor_hal_n_bounces), s.halNBounces, OperationalParamLimits.HALATION_BOUNCES, { s.halNBounces = it },
                tooltip = stringResource(R.string.editor_hal_n_bounces_tooltip),
                default = PARAM_DEFAULTS.halNBounces)
            EnhancedSlider(stringResource(R.string.editor_hal_bounce_decay), s.halBounceDecay, 0f..1f, { s.halBounceDecay = it },
                step = 0.05f, decimals = 2,
                tooltip = stringResource(R.string.editor_hal_bounce_decay_tooltip),
                default = PARAM_DEFAULTS.halBounceDecay)
            SwitchRow(stringResource(R.string.editor_hal_renormalize), s.halRenormalize, { s.halRenormalize = it },
                stringResource(R.string.editor_hal_renormalize_tooltip))
        }
    }
}

@Composable
internal fun CouplersSection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_coup_title), expanded, { expanded = it }, enabledSwitch = s.couplersActive,
        onEnabledChange = { s.couplersActive = it }, help = ParamHelpText.forKey(ParamHelpText.COUPLERS)) {
        var advanced by remember { mutableStateOf(false) }
        Text(
            stringResource(R.string.editor_coup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Basic: the three overall strengths. The per-channel mix matrix is advanced.
        EnhancedSlider(stringResource(R.string.editor_coup_amount), s.couplersAmount, 0f..4f, { s.couplersAmount = it },
            step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_coup_amount_tooltip),
            default = PARAM_DEFAULTS.couplersAmount)
        EnhancedSlider(stringResource(R.string.editor_coup_samelayer), s.couplersInhibitionSamelayer, 0f..4f,
            { s.couplersInhibitionSamelayer = it }, step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_coup_samelayer_tooltip),
            default = PARAM_DEFAULTS.couplersInhibitionSamelayer)
        EnhancedSlider(stringResource(R.string.editor_coup_interlayer), s.couplersInhibitionInterlayer, 0f..4f,
            { s.couplersInhibitionInterlayer = it }, step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_coup_interlayer_tooltip),
            default = PARAM_DEFAULTS.couplersInhibitionInterlayer)
        AdvancedToggle(advanced) { advanced = it }
        if (advanced) {
            GatedBlock(stringResource(R.string.editor_coup_gated_gamma)) {
                TripleSlider(stringResource(R.string.editor_coup_gamma_samelayer), s.couplersGammaSamelayer, 0f..2f, { s.couplersGammaSamelayer = it },
                    step = 0.02f, decimals = 3,
                    tooltip = stringResource(R.string.editor_coup_gamma_samelayer_tooltip),
                    default = PARAM_DEFAULTS.couplersGammaSamelayer)
                PairSlider(stringResource(R.string.editor_coup_mix_r), s.couplersGammaRtoGb, 0f..2f, { s.couplersGammaRtoGb = it },
                    step = 0.02f, decimals = 3, tooltip = stringResource(R.string.editor_coup_mix_r_tooltip),
                    componentLabels = "→G" to "→B", default = PARAM_DEFAULTS.couplersGammaRtoGb)
                PairSlider(stringResource(R.string.editor_coup_mix_g), s.couplersGammaGtoRb, 0f..2f, { s.couplersGammaGtoRb = it },
                    step = 0.02f, decimals = 3, tooltip = stringResource(R.string.editor_coup_mix_g_tooltip),
                    componentLabels = "→R" to "→B", default = PARAM_DEFAULTS.couplersGammaGtoRb)
                PairSlider(stringResource(R.string.editor_coup_mix_b), s.couplersGammaBtoRg, 0f..2f, { s.couplersGammaBtoRg = it },
                    step = 0.02f, decimals = 3, tooltip = stringResource(R.string.editor_coup_mix_b_tooltip),
                    componentLabels = "→R" to "→G", default = PARAM_DEFAULTS.couplersGammaBtoRg)
            }
            EnhancedSlider(stringResource(R.string.editor_coup_bleed_radius), s.couplersDiffusionSizeUm, 0f..100f, { s.couplersDiffusionSizeUm = it },
                step = 5f, decimals = 1,
                tooltip = stringResource(R.string.editor_coup_bleed_radius_tooltip),
                default = PARAM_DEFAULTS.couplersDiffusionSizeUm)
            EnhancedSlider(stringResource(R.string.editor_coup_bleed_tail), s.couplersDiffusionTailUm, 0f..500f, { s.couplersDiffusionTailUm = it },
                step = 5f, decimals = 1,
                tooltip = stringResource(R.string.editor_coup_bleed_tail_tooltip),
                default = PARAM_DEFAULTS.couplersDiffusionTailUm)
            EnhancedSlider(stringResource(R.string.editor_coup_bleed_tail_weight), s.couplersDiffusionTailWeight, 0f..1f,
                { s.couplersDiffusionTailWeight = it }, step = 0.01f, decimals = 3,
                tooltip = stringResource(R.string.editor_coup_bleed_tail_weight_tooltip),
                default = PARAM_DEFAULTS.couplersDiffusionTailWeight)
        }
    }
}

@Composable
internal fun GlareSection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_glare_title), expanded, { expanded = it }, enabledSwitch = s.glareActive,
        onEnabledChange = { s.glareActive = it }, help = ParamHelpText.forKey(ParamHelpText.GLARE)) {
        // Live control, but only on one route — say so instead of dimming.
        Text(
            stringResource(R.string.editor_glare_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnhancedSlider(stringResource(R.string.editor_glare_percent), s.glarePercent, 0f..1f, { s.glarePercent = it },
            step = 0.01f, decimals = 2,
            tooltip = stringResource(R.string.editor_glare_percent_tooltip),
            default = PARAM_DEFAULTS.glarePercent)
        EnhancedSlider(stringResource(R.string.editor_glare_roughness), s.glareRoughness, 0f..1f, { s.glareRoughness = it },
            step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_glare_roughness_tooltip),
            default = PARAM_DEFAULTS.glareRoughness)
        EnhancedSlider(stringResource(R.string.editor_glare_blur), s.glareBlur, 0f..10f, { s.glareBlur = it },
            step = 0.1f, decimals = 2,
            tooltip = stringResource(R.string.editor_glare_blur_tooltip), default = PARAM_DEFAULTS.glareBlur)
    }
}

@Composable
internal fun ExperimentalSection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_exp_title), expanded, { expanded = it },
        help = ParamHelpText.forKey(ParamHelpText.PRINT_GAMMA)) {
        EnhancedSlider(stringResource(R.string.editor_exp_film_gamma), s.filmGammaFactor, 0f..3f, { s.filmGammaFactor = it },
            step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_exp_film_gamma_tooltip),
            default = PARAM_DEFAULTS.filmGammaFactor)
        EnhancedSlider(stringResource(R.string.editor_exp_print_gamma), s.printGammaFactor, 0f..3f, { s.printGammaFactor = it },
            step = 0.05f, decimals = 2,
            tooltip = stringResource(R.string.editor_exp_print_gamma_tooltip),
            default = PARAM_DEFAULTS.printGammaFactor)
        SwitchRow(stringResource(R.string.editor_exp_morph), s.morphActive, { s.morphActive = it },
            tooltip = stringResource(R.string.editor_exp_morph_tooltip))
        if (s.morphActive) {
            EnhancedSlider(stringResource(R.string.editor_exp_morph_gamma), s.morphGammaFactor, 0.2f..3f, { s.morphGammaFactor = it },
                step = 0.05f, decimals = 2,
                tooltip = stringResource(R.string.editor_exp_morph_gamma_tooltip),
                default = PARAM_DEFAULTS.morphGammaFactor)
            EnhancedSlider(stringResource(R.string.editor_exp_morph_fast), s.morphGammaFactorFast, 0.2f..3f, { s.morphGammaFactorFast = it },
                step = 0.05f, decimals = 2,
                tooltip = stringResource(R.string.editor_exp_morph_fast_tooltip),
                default = PARAM_DEFAULTS.morphGammaFactorFast)
            EnhancedSlider(stringResource(R.string.editor_exp_morph_slow), s.morphGammaFactorSlow, 0.2f..3f, { s.morphGammaFactorSlow = it },
                step = 0.05f, decimals = 2,
                tooltip = stringResource(R.string.editor_exp_morph_slow_tooltip),
                default = PARAM_DEFAULTS.morphGammaFactorSlow)
            EnhancedSlider(stringResource(R.string.editor_exp_morph_red), s.morphGammaFactorRed, 0.2f..3f, { s.morphGammaFactorRed = it },
                step = 0.05f, decimals = 2,
                tooltip = stringResource(R.string.editor_exp_morph_red_tooltip),
                default = PARAM_DEFAULTS.morphGammaFactorRed)
            EnhancedSlider(stringResource(R.string.editor_exp_morph_green), s.morphGammaFactorGreen, 0.2f..3f, { s.morphGammaFactorGreen = it },
                step = 0.05f, decimals = 2,
                tooltip = stringResource(R.string.editor_exp_morph_green_tooltip),
                default = PARAM_DEFAULTS.morphGammaFactorGreen)
            EnhancedSlider(stringResource(R.string.editor_exp_morph_blue), s.morphGammaFactorBlue, 0.2f..3f, { s.morphGammaFactorBlue = it },
                step = 0.05f, decimals = 2,
                tooltip = stringResource(R.string.editor_exp_morph_blue_tooltip),
                default = PARAM_DEFAULTS.morphGammaFactorBlue)
            EnhancedSlider(stringResource(R.string.editor_exp_morph_exhaustion), s.morphDeveloperExhaustion, 0f..1f, { s.morphDeveloperExhaustion = it },
                step = 0.02f, decimals = 2,
                tooltip = stringResource(R.string.editor_exp_morph_exhaustion_tooltip),
                default = PARAM_DEFAULTS.morphDeveloperExhaustion)
        }
    }
}

@Composable
internal fun DisplaySection(s: ParamsState) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.editor_display_title), expanded, { expanded = it }) {
        IntSlider(stringResource(R.string.editor_display_preview_max_size), s.previewMaxSize, 128..1024, { s.previewMaxSize = it },
            tooltip = stringResource(R.string.editor_display_preview_max_size_tooltip),
            default = PARAM_DEFAULTS.previewMaxSize)
    }
}

@Composable
internal fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}
