/*
 * Spektrafilm for Android -- the editor's immutable parameter groups and DiffusionState (#452, split from ParamsState.kt for #273). GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import com.spectrafilm.app.masks.LocalAdjustment
import com.spectrafilm.engine.CameraParams
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.DateStampParams
import com.spectrafilm.engine.DiffusionFilterParams
import com.spectrafilm.engine.DirCouplersParams
import com.spectrafilm.engine.EnlargerParams
import com.spectrafilm.engine.FilmRenderingParams
import com.spectrafilm.engine.GlareParams
import com.spectrafilm.engine.GrainParams
import com.spectrafilm.engine.HalationParams
import com.spectrafilm.engine.InputGamutCompress
import com.spectrafilm.engine.IoParams
import com.spectrafilm.engine.OutputGamutCompress
import com.spectrafilm.engine.ContrastMaskParams
import com.spectrafilm.engine.PrintCurvesMorphParams
import com.spectrafilm.engine.PrintRenderingParams
import com.spectrafilm.engine.Rgb2Raw
import com.spectrafilm.engine.ScannerParams
import com.spectrafilm.engine.SceneLatitudeParams
import com.spectrafilm.engine.SettingsParams
import com.spectrafilm.engine.SpektraParams
import com.spectrafilm.engine.ToneCurveChannel
import com.spectrafilm.engine.ToneCurveParams
import com.spectrafilm.libraw.WhiteBalance
import kotlin.reflect.KProperty

/** A diffusion-filter sub-section (camera or print stage). */
@Stable
class DiffusionState {
    private val state = mutableStateOf(DiffusionUiParams())

    /** The whole filter as one immutable value. */
    val value: DiffusionUiParams get() = state.value

    var active by state.field({ it.active }) { copy(active = it) }
    var family by state.field({ it.family }) { copy(family = it) }
    var strength by state.field({ it.strength }) { copy(strength = it) }
    var spatialScale by state.field({ it.spatialScale }) { copy(spatialScale = it) }
    var haloWarmth by state.field({ it.haloWarmth }) { copy(haloWarmth = it) }
    var coreIntensity by state.field({ it.coreIntensity }) { copy(coreIntensity = it) }
    var coreSize by state.field({ it.coreSize }) { copy(coreSize = it) }
    var haloIntensity by state.field({ it.haloIntensity }) { copy(haloIntensity = it) }
    var haloSize by state.field({ it.haloSize }) { copy(haloSize = it) }
    var bloomIntensity by state.field({ it.bloomIntensity }) { copy(bloomIntensity = it) }
    var bloomSize by state.field({ it.bloomSize }) { copy(bloomSize = it) }

    fun loadFrom(d: DiffusionFilterParams) {
        active = d.active; family = d.filterFamily; strength = d.strength
        spatialScale = d.spatialScale; haloWarmth = d.haloWarmth
        coreIntensity = d.coreIntensity; coreSize = d.coreSize
        haloIntensity = d.haloIntensity; haloSize = d.haloSize
        bloomIntensity = d.bloomIntensity; bloomSize = d.bloomSize
    }

    fun toParams() = DiffusionFilterParams(
        active = active, filterFamily = family, strength = strength,
        spatialScale = spatialScale, haloWarmth = haloWarmth,
        coreIntensity = coreIntensity, coreSize = coreSize,
        haloIntensity = haloIntensity, haloSize = haloSize,
        bloomIntensity = bloomIntensity, bloomSize = bloomSize,
    )
}

internal fun Triple<Float, Float, Float>.times100() =
    Triple(first * 100f, second * 100f, third * 100f)

internal fun Triple<Float, Float, Float>.div100() =
    Triple(first / 100f, second / 100f, third / 100f)

/** The engine's colour enlarger defaults, which a colour print keeps sending unchanged. */
internal val DEFAULT_ENLARGER = EnlargerParams()

/**
 * Immutable editing groups (#452). Field names match the [ParamsState] properties that
 * delegate into them, and defaults match the old per-field initial values.
 */
@Immutable
data class ProfileUiParams(
    val filmProfile: String = "kodak_portra_400",
    val printProfile: String = "kodak_portra_endura",
)

@Immutable
data class InputUiParams(
    val inputColorSpace: String = "ProPhoto RGB",
    val inputCctfDecoding: Boolean = false,
    val spectralUpsampling: Rgb2Raw = Rgb2Raw.HANATOS2025,
    val adaptationWindow: Boolean = true,
    val adaptationSurface: Boolean = false,
    val spectralGaussianBlur: Float = 0f,
    val filterUv: Triple<Float, Float, Float> = Triple(0f, 410f, 8f),
    val filterIr: Triple<Float, Float, Float> = Triple(0f, 675f, 15f),
    val upscaleFactor: Float = 1f,
    val crop: Boolean = false,
    val cropCenter: Pair<Float, Float> = 0.5f to 0.5f,
    val cropSize: Pair<Float, Float> = 0.1f to 0.1f,
    val straightenDegrees: Float = 0f,
)

@Immutable
data class RawImportUiParams(
    val rawWhiteBalance: WhiteBalance = WhiteBalance.AS_SHOT,
    val rawTemperature: Float = 5500f,
    val rawTint: Float = 1f,
    val rawHotPixels: Boolean = false,
    val rawChromaNoise: Float = 0f,
)

@Immutable
data class LookUiParams(
    val creativeWbTemp: Float = 0f,
    val creativeWbTint: Float = 0f,
    val balanceToFilmStock: Boolean = false,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val gamutCompress: Float = 0f,
    val localAdjustments: List<LocalAdjustment> = emptyList(),
)

@Immutable
data class CameraUiParams(
    val exposureCompensationEv: Float = 0f,
    val autoExposure: Boolean = true,
    val autoExposureMethod: String = "center_weighted",
    val filmFormatMm: Float = 35f,
    val cameraLensBlurUm: Float = 0f,
)

@Immutable
data class SceneLatitudeUiParams(
    val sceneLatitudeActive: Boolean = false,
    val sceneLatitudeHighlightKnee: Float = 1f,
    val sceneLatitudeHighlightRoom: Float = 2.5f,
    val sceneLatitudeShadowKnee: Float = -2f,
    val sceneLatitudeShadowRoom: Float = 0f,
    val sceneLatitudeRolloff: Float = 2f,
    val sceneLatitudeMaxLift: Float = 4f,
)

@Immutable
data class DateStampUiParams(
    val dateStampActive: Boolean = false,
    val dateStampText: String = "",
    val dateStampCorner: Int = 0,
    val dateStampHeight: Float = 0.045f,
    val dateStampMargin: Float = 0.05f,
    val dateStampExposureEv: Float = 2.5f,
    val dateStampColor: Triple<Float, Float, Float> = Triple(1.0f, 0.18f, 0.0f),
)

@Immutable
data class EnlargerUiParams(
    val printIlluminant: String = "TH-KG3",
    val printExposure: Float = 1f,
    val printExposureCompensation: Boolean = true,
    val printYFilterShift: Float = 0f,
    val printMFilterShift: Float = 0f,
    val enlargerLensBlur: Float = 0f,
    val paperGrade: String = SilverPrint.DEFAULT_GRADE,
    val lastColourPrint: String? = null,
)

@Immutable
data class PreflashUiParams(
    val preflashExposure: Float = 0f,
    val preflashYFilterShift: Float = 0f,
    val preflashMFilterShift: Float = 0f,
)

@Immutable
data class ScannerUiParams(
    val scanLensBlur: Float = 0f,
    val scanWhiteCorrection: Boolean = false,
    val scanWhiteLevel: Float = 0.98f,
    val scanBlackCorrection: Boolean = false,
    val scanBlackLevel: Float = 0.01f,
    val scanUnsharpMask: Pair<Float, Float> = 0.7f to 0.7f,
)

@Immutable
data class OutputUiParams(
    val outputColorSpace: ColorSpace = ColorSpace.SRGB,
    val savingCctfEncoding: Boolean = true,
    val outputGamutCompress: OutputGamutCompress = OutputGamutCompress.LEGACY_CLIP,
    val inputGamutCompress: InputGamutCompress = InputGamutCompress.OFF,
    val scanFilm: Boolean = false,
)

@Immutable
data class GrainUiParams(
    val grainActive: Boolean = true,
    val grainSublayersActive: Boolean = true,
    val grainParticleAreaUm2: Float = 0.2f,
    val grainParticleScale: Triple<Float, Float, Float> = Triple(0.8f, 1f, 2f),
    val grainParticleScaleLayers: Triple<Float, Float, Float> = Triple(2.5f, 1f, 0.5f),
    val grainDensityMin: Triple<Float, Float, Float> = Triple(0.07f, 0.08f, 0.12f),
    val grainUniformity: Triple<Float, Float, Float> = Triple(0.97f, 0.97f, 0.99f),
    val grainBlur: Float = 0.65f,
    val grainBlurDyeCloudsUm: Float = 1f,
    val grainMicroStructure: Pair<Float, Float> = 0.2f to 30f,
    val grainNSubLayers: Int = 1,
)

@Immutable
data class HalationUiParams(
    val halationActive: Boolean = true,
    val halScatterAmount: Float = 1f,
    val halScatterSpatialScale: Float = 1f,
    val halHalationAmount: Float = 1f,
    val halHalationSpatialScale: Float = 1f,
    val halBoostEv: Float = 0f,
    val halProtectEv: Float = 4f,
    val halBoostRange: Float = 0.3f,
    val halScatterCoreUm: Triple<Float, Float, Float> = Triple(2.2f, 2f, 1.6f),
    val halScatterTailUm: Triple<Float, Float, Float> = Triple(9.3f, 9.7f, 9.1f),
    val halScatterTailWeightPct: Triple<Float, Float, Float> = Triple(78f, 65f, 67f),
    val halHalationStrengthPct: Triple<Float, Float, Float> = Triple(5f, 1.5f, 0f),
    val halFirstSigmaUm: Triple<Float, Float, Float> = Triple(65f, 65f, 65f),
    val halNBounces: Int = 3,
    val halBounceDecay: Float = 0.5f,
    val halRenormalize: Boolean = true,
)

@Immutable
data class CouplersUiParams(
    val couplersActive: Boolean = true,
    val couplersAmount: Float = 1f,
    val couplersInhibitionSamelayer: Float = 1f,
    val couplersInhibitionInterlayer: Float = 1f,
    val couplersGammaSamelayer: Triple<Float, Float, Float> = Triple(0.341f, 0.324f, 0.273f),
    val couplersGammaRtoGb: Pair<Float, Float> = 0.355f to 0.305f,
    val couplersGammaGtoRb: Pair<Float, Float> = 0.154f to 0.358f,
    val couplersGammaBtoRg: Pair<Float, Float> = 0.171f to 0.225f,
    val couplersDiffusionSizeUm: Float = 20f,
    val couplersDiffusionTailUm: Float = 200f,
    val couplersDiffusionTailWeight: Float = 0.06f,
)

@Immutable
data class GlareUiParams(
    val glareActive: Boolean = true,
    val glarePercent: Float = 0.03f,
    val glareRoughness: Float = 0.7f,
    val glareBlur: Float = 0.5f,
)

@Immutable
data class ExperimentalUiParams(
    val filmGammaFactor: Float = 1f,
    val printGammaFactor: Float = 1f,
)

@Immutable
data class MorphUiParams(
    val morphActive: Boolean = false,
    val morphGammaFactor: Float = 1f,
    val morphGammaFactorFast: Float = 1f,
    val morphGammaFactorSlow: Float = 1f,
    val morphGammaFactorRed: Float = 1f,
    val morphGammaFactorGreen: Float = 1f,
    val morphGammaFactorBlue: Float = 1f,
    val morphDeveloperExhaustion: Float = 0f,
)

@Immutable
data class ContrastMaskUiParams(
    val contrastMaskActive: Boolean = false,
    val contrastMaskHighlights: Float = 1.0f,
    val contrastMaskShadows: Float = 0.5f,
    val contrastMaskCore: Float = 0.25f,
    val contrastMaskScale: Float = 0.03f,
)

@Immutable
data class ToneCurveUiParams(
    val toneCurveActive: Boolean = false,
    val toneCurveMaster: List<Pair<Float, Float>> = emptyList(),
    val toneCurveRed: List<Pair<Float, Float>> = emptyList(),
    val toneCurveGreen: List<Pair<Float, Float>> = emptyList(),
    val toneCurveBlue: List<Pair<Float, Float>> = emptyList(),
)

@Immutable
data class DisplayUiParams(
    val previewMaxSize: Int = 640,
    val gpuEngine: Boolean = false,
    val gpuExport: Boolean = false,
)

@Immutable
data class DiffusionUiParams(
    val active: Boolean = false,
    val family: String = "black_pro_mist",
    val strength: Float = 0.5f,
    val spatialScale: Float = 1f,
    val haloWarmth: Float = 0f,
    val coreIntensity: Float = 1f,
    val coreSize: Float = 1f,
    val haloIntensity: Float = 1f,
    val haloSize: Float = 1f,
    val bloomIntensity: Float = 1f,
    val bloomSize: Float = 1f,
)

/** All of [ParamsState] as one immutable value. */
@Immutable
data class EditorParams(
    val profiles: ProfileUiParams,
    val input: InputUiParams,
    val rawImport: RawImportUiParams,
    val look: LookUiParams,
    val camera: CameraUiParams,
    val sceneLatitude: SceneLatitudeUiParams,
    val dateStamp: DateStampUiParams,
    val enlarger: EnlargerUiParams,
    val preflash: PreflashUiParams,
    val scanner: ScannerUiParams,
    val output: OutputUiParams,
    val grain: GrainUiParams,
    val halation: HalationUiParams,
    val couplers: CouplersUiParams,
    val glare: GlareUiParams,
    val experimental: ExperimentalUiParams,
    val morph: MorphUiParams,
    val contrastMask: ContrastMaskUiParams,
    val toneCurve: ToneCurveUiParams,
    val display: DisplayUiParams,
    val cameraDiffusion: DiffusionUiParams,
    val printDiffusion: DiffusionUiParams,
)

/** A [ParamsState] property stored as one field of an immutable group held in [MutableState]. */
internal class GroupField<G, T>(
    private val group: MutableState<G>,
    private val read: (G) -> T,
    private val write: G.(T) -> G,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = read(group.value)

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        group.value = group.value.write(value)
    }
}

internal fun <G, T> MutableState<G>.field(read: (G) -> T, write: G.(T) -> G) = GroupField(this, read, write)
