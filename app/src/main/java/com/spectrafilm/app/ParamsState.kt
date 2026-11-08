/*
 * Spektrafilm for Android — editable parameter state. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A flat, Compose-observable mirror of the full SpektraParams tree. Each panel section
 * is one immutable data class (#452) held in a single MutableState; the flat properties
 * delegate into it, so an edit replaces that section's value with a copy and
 * [ParamsState.snapshot] hands out every section at once. toParams() rebuilds an
 * immutable SpektraParams and loadFrom() loads one back. The grouping/field set tracks the spektrafilm GUI
 * (widget_sections.py / state.py).
 *
 * Unit note: the GUI (and these sliders) express halation.scatter_tail_weight and
 * halation.halation_strength in 0-100 percent, while the engine stores fractions
 * (params_mapper.py divides by 100). Those fields are kept in PERCENT here and
 * converted in toParams()/fromParams().
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

/** Input color-space option labels matching spektrafilm's RGBColorSpaces enum values. */
val INPUT_COLOR_SPACES = listOf(
    "sRGB", "DCI-P3", "Display P3", "Adobe RGB (1998)",
    "ITU-R BT.2020", "ProPhoto RGB", "ACES2065-1",
)

/** Diffusion-filter PSF families (DiffusionFilterFamilies). */
val DIFFUSION_FAMILIES = listOf("glimmerglass", "black_pro_mist", "pro_mist", "cinebloom")

/** Auto-exposure metering methods (AutoExposureMethods). */
val AUTO_EXPOSURE_METHODS = listOf(
    "center_weighted", "matrix", "multi_zone", "partial",
    "highlight_weighted", "median", "average",
)

/**
 * Flat Compose-state mirror of SpektraParams. Construct from a SpektraParams via
 * [loadFrom]; read back with [toParams].
 */
@Stable
class ParamsState {
    // Each section below is one immutable value (#452). The fields are delegates into it,
    // so callers read and write them as before; [snapshot] returns all of them at once.
    private val profilesGroup = mutableStateOf(ProfileUiParams())
    private val inputGroup = mutableStateOf(InputUiParams())
    private val rawImportGroup = mutableStateOf(RawImportUiParams())
    private val lookGroup = mutableStateOf(LookUiParams())
    private val cameraGroup = mutableStateOf(CameraUiParams())
    private val sceneLatitudeGroup = mutableStateOf(SceneLatitudeUiParams())
    private val dateStampGroup = mutableStateOf(DateStampUiParams())
    private val enlargerGroup = mutableStateOf(EnlargerUiParams())
    private val preflashGroup = mutableStateOf(PreflashUiParams())
    private val scannerGroup = mutableStateOf(ScannerUiParams())
    private val outputGroup = mutableStateOf(OutputUiParams())
    private val grainGroup = mutableStateOf(GrainUiParams())
    private val halationGroup = mutableStateOf(HalationUiParams())
    private val couplersGroup = mutableStateOf(CouplersUiParams())
    private val glareGroup = mutableStateOf(GlareUiParams())
    private val experimentalGroup = mutableStateOf(ExperimentalUiParams())
    private val morphGroup = mutableStateOf(MorphUiParams())
    private val contrastMaskGroup = mutableStateOf(ContrastMaskUiParams())
    private val toneCurveGroup = mutableStateOf(ToneCurveUiParams())
    private val displayGroup = mutableStateOf(DisplayUiParams())

    // --- profiles (Simulation) ---
    var filmProfile by profilesGroup.field({ it.filmProfile }) { copy(filmProfile = it) }
    var printProfile by profilesGroup.field({ it.printProfile }) { copy(printProfile = it) }

    // --- Input ---
    var inputColorSpace by inputGroup.field({ it.inputColorSpace }) { copy(inputColorSpace = it) }
    var inputCctfDecoding by inputGroup.field({ it.inputCctfDecoding }) { copy(inputCctfDecoding = it) }
    var spectralUpsampling by inputGroup.field({ it.spectralUpsampling }) { copy(spectralUpsampling = it) }
    var adaptationWindow by inputGroup.field({ it.adaptationWindow }) { copy(adaptationWindow = it) }
    var adaptationSurface by inputGroup.field({ it.adaptationSurface }) { copy(adaptationSurface = it) }
    var spectralGaussianBlur by inputGroup.field({ it.spectralGaussianBlur }) { copy(spectralGaussianBlur = it) }
    var filterUv by inputGroup.field({ it.filterUv }) { copy(filterUv = it) }
    var filterIr by inputGroup.field({ it.filterIr }) { copy(filterIr = it) }
    var upscaleFactor by inputGroup.field({ it.upscaleFactor }) { copy(upscaleFactor = it) }
    var crop by inputGroup.field({ it.crop }) { copy(crop = it) }
    var cropCenter by inputGroup.field({ it.cropCenter }) { copy(cropCenter = it) }
    var cropSize by inputGroup.field({ it.cropSize }) { copy(cropSize = it) }
    /** Free-angle straighten, degrees clockwise (decode-time, not an engine param; Straighten.kt). */
    var straightenDegrees by inputGroup.field({ it.straightenDegrees }) { copy(straightenDegrees = it) }

    // --- Import Raw ---
    var rawWhiteBalance by rawImportGroup.field({ it.rawWhiteBalance }) { copy(rawWhiteBalance = it) }
    var rawTemperature by rawImportGroup.field({ it.rawTemperature }) { copy(rawTemperature = it) }
    var rawTint by rawImportGroup.field({ it.rawTint }) { copy(rawTint = it) }
    // Opt-in RAW clean-up (#259), applied inside the native decode, RAW-only. Both default
    // OFF so an existing recipe decodes to exactly the bytes it always did.
    var rawHotPixels by rawImportGroup.field({ it.rawHotPixels }) { copy(rawHotPixels = it) }
    var rawChromaNoise by rawImportGroup.field({ it.rawChromaNoise }) { copy(rawChromaNoise = it) }

    // --- Creative white balance (all sources; pre-engine Bradford CAT on the linear input). ---
    // Relative push in [-100,100]; 0 = identity. Distinct from the RAW-decode WB above (which is a
    // camera-illuminant correction, RAW-only). NOT a SpektraParams field — applied in loadSource.
    var creativeWbTemp by lookGroup.field({ it.creativeWbTemp }) { copy(creativeWbTemp = it) }
    var creativeWbTint by lookGroup.field({ it.creativeWbTint }) { copy(creativeWbTint = it) }

    // "Balance to film stock" — the virtual 85-filter. When ON, loadSource Bradford-adapts the linear
    // input from the D50 working white to the selected film's reference illuminant (FilmStockBalance),
    // so a tungsten stock (Vision3 200T/500T) renders a daylight scene neutral instead of authentically
    // blue. Default OFF preserves the bit-exact spektrafilm look. NOT a SpektraParams field — a
    // pre-engine input op, so it's part of the decode-cache key (with filmProfile), not toParams().
    var balanceToFilmStock by lookGroup.field({ it.balanceToFilmStock }) { copy(balanceToFilmStock = it) }

    // Creative Contrast [-100,100]; 0 = identity. NOT a SpektraParams field — composed into the
    // master tone curve in toParams (ContrastCurve), so it drives the wired, parity-gated tone-curve
    // stage. Hue-neutral (master = all channels). Lives in the Tone Curve panel; gated by its switch.
    var contrast by lookGroup.field({ it.contrast }) { copy(contrast = it) }

    // Creative Saturation / Vibrance [-100,100]; 0 = identity. NOT SpektraParams fields — applied as a
    // post-engine Oklab chroma grade on the output buffer (ColorGrade), so the engine + parity suite
    // are untouched. Saturation is uniform; Vibrance weights low-chroma (muted) colors more.
    var saturation by lookGroup.field({ it.saturation }) { copy(saturation = it) }
    var vibrance by lookGroup.field({ it.vibrance }) { copy(vibrance = it) }

    // ACES-style gamut compression amount [0,100]; 0 = off. NOT a SpektraParams field — a post-engine
    // pass (GamutCompress, via ColorGrade) that softens the harsh cyan/edge fringe on saturated colors.
    var gamutCompress by lookGroup.field({ it.gamutCompress }) { copy(gamutCompress = it) }

    // Local-adjustment masks (the masking keystone). NOT a SpektraParams field — composited on the
    // engine OUTPUT (MaskCompositor, via simResultToBitmapGraded), so the engine + parity suite are
    // untouched. Empty = global-only (today's behavior). Round-trips in the recipe "masks" block.
    var localAdjustments by lookGroup.field({ it.localAdjustments }) { copy(localAdjustments = it) }

    // --- Simulation / camera ---
    var exposureCompensationEv by cameraGroup.field({ it.exposureCompensationEv }) { copy(exposureCompensationEv = it) }
    // Default ON to match the upstream spektrafilm pipeline (CameraParams.auto_exposure = True): the
    // metered exposure places the scene correctly on the film's per-channel density curves, so a
    // properly-exposed render comes out balanced instead of skewed (e.g. a tungsten stock going blue).
    var autoExposure by cameraGroup.field({ it.autoExposure }) { copy(autoExposure = it) }
    var autoExposureMethod by cameraGroup.field({ it.autoExposureMethod }) { copy(autoExposureMethod = it) }
    var filmFormatMm by cameraGroup.field({ it.filmFormatMm }) { copy(filmFormatMm = it) }
    var cameraLensBlurUm by cameraGroup.field({ it.cameraLensBlurUm }) { copy(cameraLensBlurUm = it) }
    val cameraDiffusionState = DiffusionState()
    // Opt-in scene latitude (engine model/scene_latitude.h): compresses a scene wider than the
    // film into it before exposure. Off by default, so existing edits render unchanged.
    var sceneLatitudeActive by sceneLatitudeGroup.field({ it.sceneLatitudeActive }) { copy(sceneLatitudeActive = it) }
    var sceneLatitudeHighlightKnee by sceneLatitudeGroup.field({ it.sceneLatitudeHighlightKnee }) { copy(sceneLatitudeHighlightKnee = it) }
    var sceneLatitudeHighlightRoom by sceneLatitudeGroup.field({ it.sceneLatitudeHighlightRoom }) { copy(sceneLatitudeHighlightRoom = it) }
    var sceneLatitudeShadowKnee by sceneLatitudeGroup.field({ it.sceneLatitudeShadowKnee }) { copy(sceneLatitudeShadowKnee = it) }
    var sceneLatitudeShadowRoom by sceneLatitudeGroup.field({ it.sceneLatitudeShadowRoom }) { copy(sceneLatitudeShadowRoom = it) }
    var sceneLatitudeRolloff by sceneLatitudeGroup.field({ it.sceneLatitudeRolloff }) { copy(sceneLatitudeRolloff = it) }
    var sceneLatitudeMaxLift by sceneLatitudeGroup.field({ it.sceneLatitudeMaxLift }) { copy(sceneLatitudeMaxLift = it) }
    // Opt-in date-back imprint (engine model/date_stamp.h): LED digits exposed onto the
    // negative in a corner of the framed picture. Off by default.
    var dateStampActive by dateStampGroup.field({ it.dateStampActive }) { copy(dateStampActive = it) }
    var dateStampText by dateStampGroup.field({ it.dateStampText }) { copy(dateStampText = it) }
    var dateStampCorner by dateStampGroup.field({ it.dateStampCorner }) { copy(dateStampCorner = it) }
    var dateStampHeight by dateStampGroup.field({ it.dateStampHeight }) { copy(dateStampHeight = it) }
    var dateStampMargin by dateStampGroup.field({ it.dateStampMargin }) { copy(dateStampMargin = it) }
    var dateStampExposureEv by dateStampGroup.field({ it.dateStampExposureEv }) { copy(dateStampExposureEv = it) }
    var dateStampColor by dateStampGroup.field({ it.dateStampColor }) { copy(dateStampColor = it) }

    // --- Simulation / enlarger (print) ---
    var printIlluminant by enlargerGroup.field({ it.printIlluminant }) { copy(printIlluminant = it) }
    var printExposure by enlargerGroup.field({ it.printExposure }) { copy(printExposure = it) }
    var printExposureCompensation by enlargerGroup.field({ it.printExposureCompensation }) { copy(printExposureCompensation = it) }
    var printYFilterShift by enlargerGroup.field({ it.printYFilterShift }) { copy(printYFilterShift = it) }
    var printMFilterShift by enlargerGroup.field({ it.printMFilterShift }) { copy(printMFilterShift = it) }
    var enlargerLensBlur by enlargerGroup.field({ it.enlargerLensBlur }) { copy(enlargerLensBlur = it) }
    val printDiffusionState = DiffusionState()

    /**
     * Multigrade contrast grade ("00".."5", [SilverPrint.GRADES]). Read only while the print is a
     * silver paper; a colour print ignores it, so it never moves a colour render.
     */
    var paperGrade by enlargerGroup.field({ it.paperGrade }) { copy(paperGrade = it) }

    /**
     * The grade [toParams] sends, or null on a colour print. A silver paper takes its grade's
     * filter pack and mid-grey exposure instead of the colour-pack table (database lookup off).
     */
    private fun silverGradeFor(print: String): PaperGrade? =
        if (SilverPrint.isSilverPaper(print)) SilverPrint.grade(paperGrade) else null

    /**
     * The colour paper a black-and-white film moved the print off, so choosing a colour film
     * again puts it back (SilverPrint.printAfterFilmChange). Held here rather than in the
     * panel so switching editor categories does not drop it. Session-only, not a recipe field.
     */
    var lastColourPrint by enlargerGroup.field({ it.lastColourPrint }) { copy(lastColourPrint = it) }

    // --- Preflash ---
    var preflashExposure by preflashGroup.field({ it.preflashExposure }) { copy(preflashExposure = it) }
    var preflashYFilterShift by preflashGroup.field({ it.preflashYFilterShift }) { copy(preflashYFilterShift = it) }
    var preflashMFilterShift by preflashGroup.field({ it.preflashMFilterShift }) { copy(preflashMFilterShift = it) }

    // --- Scanner ---
    var scanLensBlur by scannerGroup.field({ it.scanLensBlur }) { copy(scanLensBlur = it) }
    var scanWhiteCorrection by scannerGroup.field({ it.scanWhiteCorrection }) { copy(scanWhiteCorrection = it) }
    var scanWhiteLevel by scannerGroup.field({ it.scanWhiteLevel }) { copy(scanWhiteLevel = it) }
    var scanBlackCorrection by scannerGroup.field({ it.scanBlackCorrection }) { copy(scanBlackCorrection = it) }
    var scanBlackLevel by scannerGroup.field({ it.scanBlackLevel }) { copy(scanBlackLevel = it) }
    var scanUnsharpMask by scannerGroup.field({ it.scanUnsharpMask }) { copy(scanUnsharpMask = it) }

    // --- Output / saving ---
    var outputColorSpace by outputGroup.field({ it.outputColorSpace }) { copy(outputColorSpace = it) }
    var savingCctfEncoding by outputGroup.field({ it.savingCctfEncoding }) { copy(savingCctfEncoding = it) }
    // Opt-in gamut compression (default-off => byte-identical to the parity oracle).
    var outputGamutCompress by outputGroup.field({ it.outputGamutCompress }) { copy(outputGamutCompress = it) }
    var inputGamutCompress by outputGroup.field({ it.inputGamutCompress }) { copy(inputGamutCompress = it) }
    var scanFilm by outputGroup.field({ it.scanFilm }) { copy(scanFilm = it) }

    // --- Grain ---
    var grainActive by grainGroup.field({ it.grainActive }) { copy(grainActive = it) }
    var grainSublayersActive by grainGroup.field({ it.grainSublayersActive }) { copy(grainSublayersActive = it) }
    var grainParticleAreaUm2 by grainGroup.field({ it.grainParticleAreaUm2 }) { copy(grainParticleAreaUm2 = it) }
    var grainParticleScale by grainGroup.field({ it.grainParticleScale }) { copy(grainParticleScale = it) }
    var grainParticleScaleLayers by grainGroup.field({ it.grainParticleScaleLayers }) { copy(grainParticleScaleLayers = it) }
    var grainDensityMin by grainGroup.field({ it.grainDensityMin }) { copy(grainDensityMin = it) }
    var grainUniformity by grainGroup.field({ it.grainUniformity }) { copy(grainUniformity = it) }
    var grainBlur by grainGroup.field({ it.grainBlur }) { copy(grainBlur = it) }
    var grainBlurDyeCloudsUm by grainGroup.field({ it.grainBlurDyeCloudsUm }) { copy(grainBlurDyeCloudsUm = it) }
    var grainMicroStructure by grainGroup.field({ it.grainMicroStructure }) { copy(grainMicroStructure = it) }
    var grainNSubLayers by grainGroup.field({ it.grainNSubLayers }) { copy(grainNSubLayers = it) }

    // --- Halation (strengths/tail-weight in PERCENT 0-100) ---
    var halationActive by halationGroup.field({ it.halationActive }) { copy(halationActive = it) }
    var halScatterAmount by halationGroup.field({ it.halScatterAmount }) { copy(halScatterAmount = it) }
    var halScatterSpatialScale by halationGroup.field({ it.halScatterSpatialScale }) { copy(halScatterSpatialScale = it) }
    var halHalationAmount by halationGroup.field({ it.halHalationAmount }) { copy(halHalationAmount = it) }
    var halHalationSpatialScale by halationGroup.field({ it.halHalationSpatialScale }) { copy(halHalationSpatialScale = it) }
    var halBoostEv by halationGroup.field({ it.halBoostEv }) { copy(halBoostEv = it) }
    var halProtectEv by halationGroup.field({ it.halProtectEv }) { copy(halProtectEv = it) }
    var halBoostRange by halationGroup.field({ it.halBoostRange }) { copy(halBoostRange = it) }
    var halScatterCoreUm by halationGroup.field({ it.halScatterCoreUm }) { copy(halScatterCoreUm = it) }
    var halScatterTailUm by halationGroup.field({ it.halScatterTailUm }) { copy(halScatterTailUm = it) }
    var halScatterTailWeightPct by halationGroup.field({ it.halScatterTailWeightPct }) { copy(halScatterTailWeightPct = it) }
    var halHalationStrengthPct by halationGroup.field({ it.halHalationStrengthPct }) { copy(halHalationStrengthPct = it) }
    var halFirstSigmaUm by halationGroup.field({ it.halFirstSigmaUm }) { copy(halFirstSigmaUm = it) }
    var halNBounces by halationGroup.field({ it.halNBounces }) { copy(halNBounces = it) }
    var halBounceDecay by halationGroup.field({ it.halBounceDecay }) { copy(halBounceDecay = it) }
    var halRenormalize by halationGroup.field({ it.halRenormalize }) { copy(halRenormalize = it) }

    // --- Couplers (DIR) ---
    var couplersActive by couplersGroup.field({ it.couplersActive }) { copy(couplersActive = it) }
    var couplersAmount by couplersGroup.field({ it.couplersAmount }) { copy(couplersAmount = it) }
    var couplersInhibitionSamelayer by couplersGroup.field({ it.couplersInhibitionSamelayer }) { copy(couplersInhibitionSamelayer = it) }
    var couplersInhibitionInterlayer by couplersGroup.field({ it.couplersInhibitionInterlayer }) { copy(couplersInhibitionInterlayer = it) }
    var couplersGammaSamelayer by couplersGroup.field({ it.couplersGammaSamelayer }) { copy(couplersGammaSamelayer = it) }
    var couplersGammaRtoGb by couplersGroup.field({ it.couplersGammaRtoGb }) { copy(couplersGammaRtoGb = it) }
    var couplersGammaGtoRb by couplersGroup.field({ it.couplersGammaGtoRb }) { copy(couplersGammaGtoRb = it) }
    var couplersGammaBtoRg by couplersGroup.field({ it.couplersGammaBtoRg }) { copy(couplersGammaBtoRg = it) }
    var couplersDiffusionSizeUm by couplersGroup.field({ it.couplersDiffusionSizeUm }) { copy(couplersDiffusionSizeUm = it) }
    var couplersDiffusionTailUm by couplersGroup.field({ it.couplersDiffusionTailUm }) { copy(couplersDiffusionTailUm = it) }
    var couplersDiffusionTailWeight by couplersGroup.field({ it.couplersDiffusionTailWeight }) { copy(couplersDiffusionTailWeight = it) }

    // --- Glare (print) ---
    var glareActive by glareGroup.field({ it.glareActive }) { copy(glareActive = it) }
    var glarePercent by glareGroup.field({ it.glarePercent }) { copy(glarePercent = it) }
    var glareRoughness by glareGroup.field({ it.glareRoughness }) { copy(glareRoughness = it) }
    var glareBlur by glareGroup.field({ it.glareBlur }) { copy(glareBlur = it) }

    // --- Experimental ---
    var filmGammaFactor by experimentalGroup.field({ it.filmGammaFactor }) { copy(filmGammaFactor = it) }
    var printGammaFactor by experimentalGroup.field({ it.printGammaFactor }) { copy(printGammaFactor = it) }

    // --- Print curve morph (s023, opt-in) — default off + identity is a strict
    // no-op (the engine uses the stored print density-curve table). ---
    var morphActive by morphGroup.field({ it.morphActive }) { copy(morphActive = it) }
    var morphGammaFactor by morphGroup.field({ it.morphGammaFactor }) { copy(morphGammaFactor = it) }
    var morphGammaFactorFast by morphGroup.field({ it.morphGammaFactorFast }) { copy(morphGammaFactorFast = it) }
    var morphGammaFactorSlow by morphGroup.field({ it.morphGammaFactorSlow }) { copy(morphGammaFactorSlow = it) }
    var morphGammaFactorRed by morphGroup.field({ it.morphGammaFactorRed }) { copy(morphGammaFactorRed = it) }
    var morphGammaFactorGreen by morphGroup.field({ it.morphGammaFactorGreen }) { copy(morphGammaFactorGreen = it) }
    var morphGammaFactorBlue by morphGroup.field({ it.morphGammaFactorBlue }) { copy(morphGammaFactorBlue = it) }
    var morphDeveloperExhaustion by morphGroup.field({ it.morphDeveloperExhaustion }) { copy(morphDeveloperExhaustion = it) }

    // --- Contrast mask (print, opt-in) — a soft unsharp mask in the enlarger
    // that pulls large bright/dark areas toward the middle. Off = no-op. ---
    var contrastMaskActive by contrastMaskGroup.field({ it.contrastMaskActive }) { copy(contrastMaskActive = it) }
    var contrastMaskHighlights by contrastMaskGroup.field({ it.contrastMaskHighlights }) { copy(contrastMaskHighlights = it) }
    var contrastMaskShadows by contrastMaskGroup.field({ it.contrastMaskShadows }) { copy(contrastMaskShadows = it) }
    var contrastMaskCore by contrastMaskGroup.field({ it.contrastMaskCore }) { copy(contrastMaskCore = it) }
    var contrastMaskScale by contrastMaskGroup.field({ it.contrastMaskScale }) { copy(contrastMaskScale = it) }

    // --- Tone curve (Lightroom-style, applied to final display RGB) ---
    // Points are (x, y) in [0,1], x increasing; < 2 points = identity. Inactive by
    // default => the engine skips the stage (bit-exact with no curve).
    var toneCurveActive by toneCurveGroup.field({ it.toneCurveActive }) { copy(toneCurveActive = it) }
    var toneCurveMaster by toneCurveGroup.field({ it.toneCurveMaster }) { copy(toneCurveMaster = it) }
    var toneCurveRed by toneCurveGroup.field({ it.toneCurveRed }) { copy(toneCurveRed = it) }
    var toneCurveGreen by toneCurveGroup.field({ it.toneCurveGreen }) { copy(toneCurveGreen = it) }
    var toneCurveBlue by toneCurveGroup.field({ it.toneCurveBlue }) { copy(toneCurveBlue = it) }

    // --- Display / settings ---

    /**
     * Long edge of the interactive preview. Device/app-level setting seeded from
     * AppSettings.previewMaxSize (NOT preset/recipe state — loadFrom leaves it
     * alone, exactly like [gpuEngine] and [gpuExport] below).
     *
     * There were FOUR paths that replayed a stored value over the user's setting, and
     * removing only the obvious one did not fix it. Recipes are keyed by source uri
     * and auto-applied on open, so a real photo always got its stale value back while
     * the demo image (which has no recipe) honoured the setting — which is why the bug
     * presented as "inert for RAW only". The live path was NOT this class:
     *
     *   Recipes.load -> Presets.decode -> the "display" block in Presets   (the read
     *                                                                       that bit)
     *   Presets.encode "display"                                           (the write)
     *   BuiltInPresets                                                     (a 3rd copy)
     *   ParamsState.loadFrom                                               (this one)
     *
     * All four are gone. Dropping the READ is what disarms the recipes already on
     * disk, which keep carrying the field. It is still WRITTEN into the engine params
     * by toParams, exactly as gpuPreview/gpuExport are.
     */
    var previewMaxSize by displayGroup.field({ it.previewMaxSize }) { copy(previewMaxSize = it) }

    /**
     * GPU engine preview (Vulkan, #146). Device/app-level setting seeded from
     * AppSettings.gpuEngine (NOT preset/recipe state — loadFrom leaves it
     * alone). Preview renders only; export ignores it inside the engine.
     */
    var gpuEngine by displayGroup.field({ it.gpuEngine }) { copy(gpuEngine = it) }

    /**
     * EXPERIMENTAL GPU export (Vulkan, #154). Device/app-level setting seeded
     * from AppSettings.gpuExportEngine (NOT preset/recipe state). Applies to
     * full-resolution exports; independent of [gpuEngine].
     */
    var gpuExport by displayGroup.field({ it.gpuExport }) { copy(gpuExport = it) }

    /** Every editing group as one immutable value (the editor's UiState params). */
    fun snapshot(): EditorParams = EditorParams(
        profiles = profilesGroup.value,
        input = inputGroup.value,
        rawImport = rawImportGroup.value,
        look = lookGroup.value,
        camera = cameraGroup.value,
        sceneLatitude = sceneLatitudeGroup.value,
        dateStamp = dateStampGroup.value,
        enlarger = enlargerGroup.value,
        preflash = preflashGroup.value,
        scanner = scannerGroup.value,
        output = outputGroup.value,
        grain = grainGroup.value,
        halation = halationGroup.value,
        couplers = couplersGroup.value,
        glare = glareGroup.value,
        experimental = experimentalGroup.value,
        morph = morphGroup.value,
        contrastMask = contrastMaskGroup.value,
        toneCurve = toneCurveGroup.value,
        display = displayGroup.value,
        cameraDiffusion = cameraDiffusionState.value,
        printDiffusion = printDiffusionState.value,
    )

    /** Reset to the engine defaults for the given profile pair. */
    fun loadFrom(p: SpektraParams) {
        filmProfile = p.filmProfile
        printProfile = p.printProfile

        inputColorSpace = p.io.inputColorSpace
        inputCctfDecoding = p.io.inputCctfDecoding
        spectralUpsampling = p.settings.rgbToRawMethod
        adaptationWindow = p.settings.applyHanatos2025AdaptationWindow
        adaptationSurface = p.settings.applyHanatos2025AdaptationSurface
        spectralGaussianBlur = p.settings.spectralGaussianBlur
        filterUv = p.camera.filterUv
        filterIr = p.camera.filterIr
        upscaleFactor = p.io.upscaleFactor
        crop = p.io.crop
        cropCenter = p.io.cropCenter
        cropSize = p.io.cropSize

        exposureCompensationEv = p.camera.exposureCompensationEv
        autoExposure = p.camera.autoExposure
        autoExposureMethod = p.camera.autoExposureMethod
        filmFormatMm = p.camera.filmFormatMm
        cameraLensBlurUm = p.camera.lensBlurUm
        cameraDiffusionState.loadFrom(p.camera.diffusionFilter)
        p.camera.sceneLatitude.let { sl ->
            sceneLatitudeActive = sl.active
            sceneLatitudeHighlightKnee = sl.highlightKnee
            sceneLatitudeHighlightRoom = sl.highlightRoom
            sceneLatitudeShadowKnee = sl.shadowKnee
            sceneLatitudeShadowRoom = sl.shadowRoom
            sceneLatitudeRolloff = sl.rolloff
            sceneLatitudeMaxLift = sl.maxLift
        }
        p.camera.dateStamp.let { ds ->
            dateStampActive = ds.active
            dateStampText = ds.text
            dateStampCorner = ds.corner
            dateStampHeight = ds.height
            dateStampMargin = ds.margin
            dateStampExposureEv = ds.exposureEv
            dateStampColor = ds.color
        }

        printIlluminant = p.enlarger.illuminant
        printExposure = p.enlarger.printExposure
        printExposureCompensation = p.enlarger.printExposureCompensation
        printYFilterShift = p.enlarger.yFilterShift
        printMFilterShift = p.enlarger.mFilterShift
        enlargerLensBlur = p.enlarger.lensBlur
        printDiffusionState.loadFrom(p.enlarger.diffusionFilter)
        paperGrade = SilverPrint.DEFAULT_GRADE
        lastColourPrint = null

        preflashExposure = p.enlarger.preflashExposure
        preflashYFilterShift = p.enlarger.preflashYFilterShift
        preflashMFilterShift = p.enlarger.preflashMFilterShift

        scanLensBlur = p.scanner.lensBlur
        scanWhiteCorrection = p.scanner.whiteCorrection
        scanWhiteLevel = p.scanner.whiteLevel
        scanBlackCorrection = p.scanner.blackCorrection
        scanBlackLevel = p.scanner.blackLevel
        scanUnsharpMask = p.scanner.unsharpMask

        outputColorSpace = p.io.outputColorSpace
        savingCctfEncoding = p.io.outputCctfEncoding
        outputGamutCompress = p.io.outputGamutCompress
        inputGamutCompress = p.io.inputGamutCompress
        scanFilm = p.io.scanFilm

        val g = p.filmRender.grain
        grainActive = g.active
        grainSublayersActive = g.sublayersActive
        grainParticleAreaUm2 = g.agxParticleAreaUm2
        grainParticleScale = g.agxParticleScale
        grainParticleScaleLayers = g.agxParticleScaleLayers
        grainDensityMin = g.densityMin
        grainUniformity = g.uniformity
        grainBlur = g.blur
        grainBlurDyeCloudsUm = g.blurDyeCloudsUm
        grainMicroStructure = g.microStructure
        grainNSubLayers = g.nSubLayers

        val h = p.filmRender.halation
        halationActive = h.active
        halScatterAmount = h.scatterAmount
        halScatterSpatialScale = h.scatterSpatialScale
        halHalationAmount = h.halationAmount
        halHalationSpatialScale = h.halationSpatialScale
        halBoostEv = h.boostEv
        halProtectEv = h.protectEv
        halBoostRange = h.boostRange
        halScatterCoreUm = h.scatterCoreUm
        halScatterTailUm = h.scatterTailUm
        halScatterTailWeightPct = h.scatterTailWeight.times100()
        halHalationStrengthPct = h.halationStrength.times100()
        halFirstSigmaUm = h.halationFirstSigmaUm
        halNBounces = h.halationNBounces
        halBounceDecay = h.halationBounceDecay
        halRenormalize = h.halationRenormalize

        val c = p.filmRender.dirCouplers
        couplersActive = c.active
        couplersAmount = c.amount
        couplersInhibitionSamelayer = c.inhibitionSamelayer
        couplersInhibitionInterlayer = c.inhibitionInterlayer
        couplersGammaSamelayer = c.gammaSamelayerRgb
        couplersGammaRtoGb = c.gammaInterlayerRToGb
        couplersGammaGtoRb = c.gammaInterlayerGToRb
        couplersGammaBtoRg = c.gammaInterlayerBToRg
        couplersDiffusionSizeUm = c.diffusionSizeUm
        couplersDiffusionTailUm = c.diffusionTailUm
        couplersDiffusionTailWeight = c.diffusionTailWeight

        val gl = p.printRender.glare
        glareActive = gl.active
        glarePercent = gl.percent
        glareRoughness = gl.roughness
        glareBlur = gl.blur

        filmGammaFactor = p.filmRender.densityCurveGamma
        printGammaFactor = p.printRender.densityCurveGamma

        val pm = p.printRender.densityCurvesMorph
        morphActive = pm.active
        morphGammaFactor = pm.gammaFactor
        morphGammaFactorFast = pm.gammaFactorFast
        morphGammaFactorSlow = pm.gammaFactorSlow
        morphGammaFactorRed = pm.gammaFactorRed
        morphGammaFactorGreen = pm.gammaFactorGreen
        morphGammaFactorBlue = pm.gammaFactorBlue
        morphDeveloperExhaustion = pm.developerExhaustion

        val cm = p.printRender.contrastMask
        contrastMaskActive = cm.active
        contrastMaskHighlights = cm.highlights
        contrastMaskShadows = cm.shadows
        contrastMaskCore = cm.core
        contrastMaskScale = cm.scale

        val tc = p.toneCurve
        toneCurveActive = tc.active
        toneCurveMaster = tc.master.points
        toneCurveRed = tc.red.points
        toneCurveGreen = tc.green.points
        toneCurveBlue = tc.blue.points
    }

    /** Build an immutable SpektraParams from current state. */
    // [filmFormatMmOverride] lets the zoom/magnifier crop paths feed the engine an EFFECTIVE film
    // format so its `pixel_size_um = film_format_mm*1000/max(w,h)` derivation reflects the crop's
    // true physical extent (a crop covers only a fraction of the frame). Without it the engine
    // treats e.g. an 800px crop as if 800px spanned the whole 35mm frame, so grain/halation (both
    // µm-based) come out too weak even when zoomed in. The full-frame preview/export pass null and
    // are unaffected. See renderRoi/openMagnifier in MainActivity.
    fun toParams(
        previewMaxSizeOverride: Int? = null,
        filmFormatMmOverride: Float? = null,
        // Interactive-preview optimisation (Lightroom-style "grain at 100%"): drop the per-pixel
        // grain + the spatial halation/diffusion branch — the dominant preview cost, ~invisible at
        // fit/draft resolution. The live draft AND the full fit settle set this; the 100% zoom ROI,
        // the magnifier and EXPORT do NOT, so grain/halation still render where they're visible.
        // Preview-only — never set on the export path, so parity is unaffected. Couplers stay on.
        skipGrainHalation: Boolean = false,
        // Stock-preview tiles render a candidate film or paper as a tap would apply it. The
        // paper-dependent fields (grade filtration, exposure, database lookup) and the
        // film-dependent ones (grain sub-layers) must follow the candidate, so they are derived
        // from these overrides rather than patched onto a finished SpektraParams.
        filmOverride: String? = null,
        printOverride: String? = null,
    ): SpektraParams = SpektraParams(
        filmProfile = filmOverride ?: filmProfile,
        printProfile = printOverride ?: printProfile,
        camera = CameraParams(
            exposureCompensationEv = exposureCompensationEv,
            autoExposure = autoExposure,
            autoExposureMethod = autoExposureMethod,
            lensBlurUm = cameraLensBlurUm,
            filmFormatMm = filmFormatMmOverride ?: filmFormatMm,
            filterUv = filterUv,
            filterIr = filterIr,
            diffusionFilter = cameraDiffusionState.toParams(),
            sceneLatitude = SceneLatitudeParams(
                active = sceneLatitudeActive,
                highlightKnee = sceneLatitudeHighlightKnee,
                highlightRoom = sceneLatitudeHighlightRoom,
                shadowKnee = sceneLatitudeShadowKnee,
                shadowRoom = sceneLatitudeShadowRoom,
                rolloff = sceneLatitudeRolloff,
                maxLift = sceneLatitudeMaxLift,
            ),
            dateStamp = DateStampParams(
                active = dateStampActive,
                text = dateStampText,
                corner = dateStampCorner,
                height = dateStampHeight,
                margin = dateStampMargin,
                exposureEv = dateStampExposureEv,
                color = dateStampColor,
            ),
        ),
        enlarger = EnlargerParams(
            illuminant = printIlluminant,
            printExposure = printExposure * (silverGradeFor(printOverride ?: printProfile)?.printExposure ?: 1f),
            yFilterNeutral = silverGradeFor(printOverride ?: printProfile)?.yFilter ?: DEFAULT_ENLARGER.yFilterNeutral,
            mFilterNeutral = silverGradeFor(printOverride ?: printProfile)?.mFilter ?: DEFAULT_ENLARGER.mFilterNeutral,
            cFilterNeutral = if (silverGradeFor(printOverride ?: printProfile) != null) 0f else DEFAULT_ENLARGER.cFilterNeutral,
            printExposureCompensation = printExposureCompensation,
            yFilterShift = printYFilterShift,
            mFilterShift = printMFilterShift,
            lensBlur = enlargerLensBlur,
            diffusionFilter = printDiffusionState.toParams(),
            preflashExposure = preflashExposure,
            preflashYFilterShift = preflashYFilterShift,
            preflashMFilterShift = preflashMFilterShift,
        ),
        scanner = ScannerParams(
            lensBlur = scanLensBlur,
            whiteCorrection = scanWhiteCorrection,
            blackCorrection = scanBlackCorrection,
            whiteLevel = scanWhiteLevel,
            blackLevel = scanBlackLevel,
            unsharpMask = scanUnsharpMask,
        ),
        io = IoParams(
            inputColorSpace = inputColorSpace,
            inputCctfDecoding = inputCctfDecoding,
            outputColorSpace = outputColorSpace,
            outputCctfEncoding = savingCctfEncoding,
            outputGamutCompress = outputGamutCompress,
            inputGamutCompress = inputGamutCompress,
            crop = crop,
            cropCenter = cropCenter,
            cropSize = cropSize,
            upscaleFactor = upscaleFactor,
            scanFilm = scanFilm,
        ),
        settings = SettingsParams(
            rgbToRawMethod = spectralUpsampling,
            applyHanatos2025AdaptationWindow = adaptationWindow,
            applyHanatos2025AdaptationSurface = adaptationSurface,
            spectralGaussianBlur = spectralGaussianBlur,
            previewMaxSize = previewMaxSizeOverride ?: previewMaxSize,
            gpuPreview = gpuEngine,
            gpuExport = gpuExport,
            neutralPrintFiltersFromDatabase = silverGradeFor(printOverride ?: printProfile) == null,
        ),
        filmRender = FilmRenderingParams(
            densityCurveGamma = filmGammaFactor,
            grain = GrainParams(
                active = grainActive && !skipGrainHalation,
                // The three grain sub-layers are the colour films' model; a silver film's
                // datasheet supports no split, so its grain is always one layer.
                sublayersActive = grainSublayersActive && !SilverPrint.isSilverFilm(filmOverride ?: filmProfile),
                agxParticleAreaUm2 = grainParticleAreaUm2,
                agxParticleScale = grainParticleScale,
                agxParticleScaleLayers = grainParticleScaleLayers,
                densityMin = grainDensityMin,
                uniformity = grainUniformity,
                blur = grainBlur,
                blurDyeCloudsUm = grainBlurDyeCloudsUm,
                microStructure = grainMicroStructure,
                nSubLayers = grainNSubLayers,
            ),
            halation = HalationParams(
                active = halationActive && !skipGrainHalation,
                scatterAmount = halScatterAmount,
                scatterSpatialScale = halScatterSpatialScale,
                halationAmount = halHalationAmount,
                halationSpatialScale = halHalationSpatialScale,
                scatterCoreUm = halScatterCoreUm,
                scatterTailUm = halScatterTailUm,
                scatterTailWeight = halScatterTailWeightPct.div100(),
                boostEv = halBoostEv,
                boostRange = halBoostRange,
                protectEv = halProtectEv,
                halationStrength = halHalationStrengthPct.div100(),
                halationFirstSigmaUm = halFirstSigmaUm,
                halationNBounces = halNBounces,
                halationBounceDecay = halBounceDecay,
                halationRenormalize = halRenormalize,
            ),
            dirCouplers = DirCouplersParams(
                active = couplersActive,
                amount = couplersAmount,
                inhibitionSamelayer = couplersInhibitionSamelayer,
                inhibitionInterlayer = couplersInhibitionInterlayer,
                gammaSamelayerRgb = couplersGammaSamelayer,
                gammaInterlayerRToGb = couplersGammaRtoGb,
                gammaInterlayerGToRb = couplersGammaGtoRb,
                gammaInterlayerBToRg = couplersGammaBtoRg,
                diffusionSizeUm = couplersDiffusionSizeUm,
                diffusionTailUm = couplersDiffusionTailUm,
                diffusionTailWeight = couplersDiffusionTailWeight,
            ),
            glare = GlareParams(
                active = glareActive,
                percent = glarePercent,
                roughness = glareRoughness,
                blur = glareBlur,
            ),
        ),
        printRender = PrintRenderingParams(
            densityCurveGamma = printGammaFactor,
            densityCurvesMorph = PrintCurvesMorphParams(
                active = morphActive,
                gammaFactor = morphGammaFactor,
                gammaFactorFast = morphGammaFactorFast,
                gammaFactorSlow = morphGammaFactorSlow,
                gammaFactorRed = morphGammaFactorRed,
                gammaFactorGreen = morphGammaFactorGreen,
                gammaFactorBlue = morphGammaFactorBlue,
                developerExhaustion = morphDeveloperExhaustion,
            ),
            contrastMask = ContrastMaskParams(
                active = contrastMaskActive,
                highlights = contrastMaskHighlights,
                shadows = contrastMaskShadows,
                core = contrastMaskCore,
                scale = contrastMaskScale,
            ),
            glare = GlareParams(
                active = glareActive,
                percent = glarePercent,
                roughness = glareRoughness,
                blur = glareBlur,
            ),
        ),
        toneCurve = ToneCurveParams(
            active = toneCurveActive,
            // Contrast composes UNDER the user's drawn master curve; identity when contrast=0.
            master = ToneCurveChannel(ContrastCurve.composeMaster(toneCurveMaster, contrast)),
            red = ToneCurveChannel(toneCurveRed),
            green = ToneCurveChannel(toneCurveGreen),
            blue = ToneCurveChannel(toneCurveBlue),
        ),
    )

    /**
     * Reset the per-stock character overrides — grain, halation, DIR couplers and the film/print
     * density-curve gamma — to their neutral defaults, so a freshly selected film or paper shows its
     * own baked character instead of the previous stock's manual tweaks. Creative and global edits
     * (exposure, white balance, crop, tone curve, output grade, masks…) are deliberately left
     * untouched.
     *
     * Powers the "Use its defaults" snackbar action on profile switch (onboarding §6h). UI/state only
     * — the engine still receives a complete param set, so this carries no parity impact.
     */
    fun resetStockCharacter() {
        val d = ParamsState()
        // Grain
        grainActive = d.grainActive
        grainSublayersActive = d.grainSublayersActive
        grainParticleAreaUm2 = d.grainParticleAreaUm2
        grainParticleScale = d.grainParticleScale
        grainParticleScaleLayers = d.grainParticleScaleLayers
        grainDensityMin = d.grainDensityMin
        grainUniformity = d.grainUniformity
        grainBlur = d.grainBlur
        grainBlurDyeCloudsUm = d.grainBlurDyeCloudsUm
        grainMicroStructure = d.grainMicroStructure
        grainNSubLayers = d.grainNSubLayers
        // Halation
        halationActive = d.halationActive
        halScatterAmount = d.halScatterAmount
        halScatterSpatialScale = d.halScatterSpatialScale
        halHalationAmount = d.halHalationAmount
        halHalationSpatialScale = d.halHalationSpatialScale
        halBoostEv = d.halBoostEv
        halProtectEv = d.halProtectEv
        halBoostRange = d.halBoostRange
        halScatterCoreUm = d.halScatterCoreUm
        halScatterTailUm = d.halScatterTailUm
        halScatterTailWeightPct = d.halScatterTailWeightPct
        halHalationStrengthPct = d.halHalationStrengthPct
        halFirstSigmaUm = d.halFirstSigmaUm
        halNBounces = d.halNBounces
        halBounceDecay = d.halBounceDecay
        halRenormalize = d.halRenormalize
        // DIR couplers
        couplersActive = d.couplersActive
        couplersAmount = d.couplersAmount
        couplersInhibitionSamelayer = d.couplersInhibitionSamelayer
        couplersInhibitionInterlayer = d.couplersInhibitionInterlayer
        couplersGammaSamelayer = d.couplersGammaSamelayer
        couplersGammaRtoGb = d.couplersGammaRtoGb
        couplersGammaGtoRb = d.couplersGammaGtoRb
        couplersGammaBtoRg = d.couplersGammaBtoRg
        couplersDiffusionSizeUm = d.couplersDiffusionSizeUm
        couplersDiffusionTailUm = d.couplersDiffusionTailUm
        couplersDiffusionTailWeight = d.couplersDiffusionTailWeight
        // Density-curve gamma (Experimental)
        filmGammaFactor = d.filmGammaFactor
        printGammaFactor = d.printGammaFactor
    }
}
