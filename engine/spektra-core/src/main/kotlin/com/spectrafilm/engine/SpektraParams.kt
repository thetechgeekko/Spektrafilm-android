/*
 * Spektrafilm for Android — engine parameters.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * 1:1 mirror of spektrafilm's RuntimePhotoParams tree
 * (src/spektrafilm/runtime/params_schema.py). Defaults match upstream so that, for a
 * given profile pair, SpektraParams() reproduces spektrafilm's out-of-the-box look.
 * The JNI bridge marshals these into the flat `spk_params` C struct.
 */
package com.spectrafilm.engine

/** Output color space, mirrors io.output_color_space + spk_color_space. */
enum class ColorSpace { SRGB, ADOBE_RGB, PROPHOTO, REC2020, ACES2065_1, LINEAR_SRGB }

/** RGB→spectral upsampling method, mirrors settings.rgb_to_raw_method. */
enum class Rgb2Raw { HANATOS2025, MALLETT2019 }

/** White-balance mode for RAW import (GUI 'import raw'). */
enum class WhiteBalance { AS_SHOT, DAYLIGHT, TUNGSTEN, CUSTOM }

data class CameraParams(
    val exposureCompensationEv: Float = 0.0f,
    val autoExposure: Boolean = true,
    val autoExposureMethod: String = "center_weighted",
    val lensBlurUm: Float = 0.0f,
    val filmFormatMm: Float = 35.0f,
    val filterUv: Triple<Float, Float, Float> = Triple(0.0f, 410.0f, 8.0f),
    val filterIr: Triple<Float, Float, Float> = Triple(0.0f, 675.0f, 15.0f),
    val diffusionFilter: DiffusionFilterParams = DiffusionFilterParams(),
)

data class EnlargerParams(
    val illuminant: String = "TH-KG3",
    val printExposure: Float = 1.0f,
    val printExposureCompensation: Boolean = true,
    val normalizePrintExposure: Boolean = true,
    val yFilterShift: Float = 0.0f,
    val mFilterShift: Float = 0.0f,
    val yFilterNeutral: Float = 55f, // kodak cc values
    val mFilterNeutral: Float = 65f,
    val cFilterNeutral: Float = 0f,
    val lensBlur: Float = 0.0f,
    val diffusionFilter: DiffusionFilterParams = DiffusionFilterParams(),
    val preflashExposure: Float = 0.0f,
    val preflashYFilterShift: Float = 0.0f,
    val preflashMFilterShift: Float = 0.0f,
)

data class ScannerParams(
    val lensBlur: Float = 0.0f,
    val whiteCorrection: Boolean = false,
    val blackCorrection: Boolean = false,
    val whiteLevel: Float = 0.98f,
    val blackLevel: Float = 0.01f,
    val unsharpMask: Pair<Float, Float> = 0.7f to 0.7f,
)

data class GrainParams(
    val active: Boolean = true,
    val sublayersActive: Boolean = true,
    val agxParticleAreaUm2: Float = 0.2f,
    val agxParticleScale: Triple<Float, Float, Float> = Triple(0.8f, 1.0f, 2.0f),
    val agxParticleScaleLayers: Triple<Float, Float, Float> = Triple(2.5f, 1.0f, 0.5f),
    val densityMin: Triple<Float, Float, Float> = Triple(0.07f, 0.08f, 0.12f),
    val uniformity: Triple<Float, Float, Float> = Triple(0.97f, 0.97f, 0.99f),
    val blur: Float = 0.65f,
    val blurDyeCloudsUm: Float = 1.0f,
    val microStructure: Pair<Float, Float> = 0.2f to 30f,
    val nSubLayers: Int = 1,
)

data class HalationParams(
    val active: Boolean = true,
    val scatterAmount: Float = 1.0f,
    val scatterSpatialScale: Float = 1.0f,
    val halationAmount: Float = 1.0f,
    val halationSpatialScale: Float = 1.0f,
    val scatterCoreUm: Triple<Float, Float, Float> = Triple(2.2f, 2.0f, 1.6f),
    val scatterTailUm: Triple<Float, Float, Float> = Triple(9.3f, 9.7f, 9.1f),
    val scatterTailWeight: Triple<Float, Float, Float> = Triple(0.78f, 0.65f, 0.67f),
    val boostEv: Float = 0.0f,
    val boostRange: Float = 0.3f,
    val protectEv: Float = 4.0f,
    val halationStrength: Triple<Float, Float, Float> = Triple(0.05f, 0.015f, 0.0f),
    val halationFirstSigmaUm: Triple<Float, Float, Float> = Triple(65.0f, 65.0f, 65.0f),
    val halationNBounces: Int = 3,
    val halationBounceDecay: Float = 0.5f,
    val halationRenormalize: Boolean = true,
)

data class DirCouplersParams(
    val active: Boolean = true,
    val amount: Float = 1.0f,
    val inhibitionSamelayer: Float = 1.0f,
    val inhibitionInterlayer: Float = 1.0f,
    val gammaSamelayerRgb: Triple<Float, Float, Float> = Triple(0.341f, 0.324f, 0.273f),
    val gammaInterlayerRToGb: Pair<Float, Float> = 0.355f to 0.305f,
    val gammaInterlayerGToRb: Pair<Float, Float> = 0.154f to 0.358f,
    val gammaInterlayerBToRg: Pair<Float, Float> = 0.171f to 0.225f,
    val diffusionSizeUm: Float = 20.0f,
    val diffusionTailUm: Float = 200.0f,
    val diffusionTailWeight: Float = 0.06f,
)

data class GlareParams(
    val active: Boolean = true,
    val percent: Float = 0.03f,
    val roughness: Float = 0.7f,
    val blur: Float = 0.5f,
)

data class DiffusionFilterParams(
    val active: Boolean = false,
    val filterFamily: String = "black_pro_mist",
    val strength: Float = 0.5f,
    val spatialScale: Float = 1.0f,
    val haloWarmth: Float = 0.0f,
    val coreIntensity: Float = 1.0f,
    val coreSize: Float = 1.0f,
    val haloIntensity: Float = 1.0f,
    val haloSize: Float = 1.0f,
    val bloomIntensity: Float = 1.0f,
    val bloomSize: Float = 1.0f,
)

data class FilmRenderingParams(
    val densityCurveGamma: Float = 1.0f,
    val grain: GrainParams = GrainParams(),
    val halation: HalationParams = HalationParams(),
    val dirCouplers: DirCouplersParams = DirCouplersParams(),
    val glare: GlareParams = GlareParams(),
)

data class PrintRenderingParams(
    val densityCurveGamma: Float = 1.0f,
    val glare: GlareParams = GlareParams(),
)

data class IoParams(
    val inputColorSpace: String = "ProPhoto RGB",
    val inputCctfDecoding: Boolean = false,
    val outputColorSpace: ColorSpace = ColorSpace.SRGB,
    val outputCctfEncoding: Boolean = true,
    val crop: Boolean = false,
    val cropCenter: Pair<Float, Float> = 0.5f to 0.5f,
    val cropSize: Pair<Float, Float> = 0.1f to 0.1f,
    val upscaleFactor: Float = 1.0f,
    /** scan negative directly, skipping the print stage (RuntimePhotoParams.io.scan_film). */
    val scanFilm: Boolean = false,
)

data class SettingsParams(
    val rgbToRawMethod: Rgb2Raw = Rgb2Raw.HANATOS2025,
    val applyHanatos2025AdaptationWindow: Boolean = true,
    val applyHanatos2025AdaptationSurface: Boolean = false,
    val spectralGaussianBlur: Float = 0.0f,
    val useEnlargerLut: Boolean = false,
    val useScannerLut: Boolean = false,
    val lutResolution: Int = 17,
    val useFastStats: Boolean = false,
    val previewMaxSize: Int = 640,
    val previewMode: Boolean = false,
    val neutralPrintFiltersFromDatabase: Boolean = true,
)

/**
 * Top-level engine parameters, mirroring RuntimePhotoParams. `filmProfile` / `printProfile`
 * are profile ids resolved from bundled assets (see docs/ASSETS.md).
 */
data class SpektraParams(
    val filmProfile: String,
    val printProfile: String,
    val filmRender: FilmRenderingParams = FilmRenderingParams(),
    val printRender: PrintRenderingParams = PrintRenderingParams(),
    val camera: CameraParams = CameraParams(),
    val enlarger: EnlargerParams = EnlargerParams(),
    val scanner: ScannerParams = ScannerParams(),
    val io: IoParams = IoParams(),
    val settings: SettingsParams = SettingsParams(),
)
