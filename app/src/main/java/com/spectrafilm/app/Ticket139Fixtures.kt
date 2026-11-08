/*
 * Spektrafilm for Android — ticket #139 probe fixtures. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.content.Context
import com.spectrafilm.app.masks.BlendMode
import com.spectrafilm.app.masks.LocalAdjustment
import com.spectrafilm.app.masks.Mask
import com.spectrafilm.app.masks.MaskComponent
import com.spectrafilm.app.masks.TierADelta
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.InputGamutCompress
import com.spectrafilm.engine.OutputGamutCompress
import com.spectrafilm.engine.Rgb2Raw
import com.spectrafilm.libraw.WhiteBalance
import kotlinx.coroutines.runBlocking

/**
 * The rich editor document and Params the #139 probe writes and checks against.
 * Split out of [Ticket139EditorProbe] (#273).
 */
internal object Ticket139Fixtures {
    internal const val EXPECTED_ROTATION = 270
    internal const val EXPECTED_MASK_INDEX = 2
    internal const val EXPECTED_UNDO = 1
    internal const val EXPECTED_REDO = 1

    internal fun richDocument(
        durableSource: Boolean,
        runningExport: Boolean,
    ): EditorSessionDocument {
        val baseline = richParams(exposure = -0.5f)
        val current = richParams(exposure = 1.25f)
        val forward = richParams(exposure = 2f)
        val currentSnapshot = EditSnapshot(Presets.toJsonString(current), EXPECTED_ROTATION)
        val document = EditorSessionDocument(
            source = if (durableSource) {
                EditorSourceState(
                    uri = "content://com.spectrafilm.ticket139/revoked/source.dng",
                    kind = SourceKind.RAW,
                    displayName = "ticket139-source.dng",
                    authorizationRequired = false,
                )
            } else {
                EditorSourceState(null, SourceKind.DEMO, "synthetic demo image", false)
            },
            current = currentSnapshot,
            committed = currentSnapshot,
            history = EditHistoryState(
                undo = listOf(EditSnapshot(Presets.toJsonString(baseline), 90)),
                redo = listOf(EditSnapshot(Presets.toJsonString(forward), 180)),
            ),
            tool = if (durableSource) {
                EditorToolState(
                    Category.MASKS,
                    EditorOverlayTool.MASK_GEOMETRY,
                    EXPECTED_MASK_INDEX,
                )
            } else {
                EditorToolState(Category.INPUT, EditorOverlayTool.CROP, EXPECTED_MASK_INDEX)
            },
            preset = EditorPresetState(
                baseJson = Presets.toJsonString(baseline),
                fullJson = Presets.toJsonString(forward),
                amount = 0.4f,
                clipboardJson = Presets.toJsonString(current),
                selectedPreset = "ticket139 look",
                presetName = "ticket139 preset",
            ),
            export = EditorExportState(
                sheetOpen = runningExport,
                options = ExportOptions(ExportFormat.PNG16, 93, ExportSize.FULL, 2048, "ticket139"),
                keepGps = false,
                phase = if (runningExport) EditorExportPhase.RUNNING else EditorExportPhase.IDLE,
                runtimeRunId = if (runningExport) 139L else null,
            ),
        )
        // Freeze the oracle in the exact canonical representation the durable reader returns.
        // Comparing a pretty Params JSON producer string to its compact persisted equivalent would
        // test formatting rather than cursor identity and could make the pre-launch oracle fail
        // even though every parameter is identical.
        return EditorSessionDocumentCodec.decode(EditorSessionDocumentCodec.encode(document))
    }

    internal fun richParams(exposure: Float): ParamsState = ParamsState().apply {
        filmProfile = "kodak_ektar_100"
        printProfile = "kodak_supra_endura"

        // Native preview ingress is linear ProPhoto-only. Exercise the rest of the persisted
        // input family with non-default sentinels without making the real Activity fixture
        // unrenderable before its restored overlay can enter composition.
        inputColorSpace = "ProPhoto RGB"
        inputCctfDecoding = true
        spectralUpsampling = Rgb2Raw.MALLETT2019
        adaptationWindow = false
        adaptationSurface = true
        spectralGaussianBlur = 0.35f
        filterUv = Triple(0.1f, 405f, 7f)
        filterIr = Triple(0.2f, 680f, 14f)
        upscaleFactor = 1.25f
        crop = true
        cropCenter = 0.43f to 0.57f
        cropSize = 0.62f to 0.38f
        straightenDegrees = -2.5f

        rawWhiteBalance = WhiteBalance.CUSTOM
        rawTemperature = 4_875f
        rawTint = 0.92f
        rawHotPixels = true
        rawChromaNoise = 12f
        creativeWbTemp = 7f
        creativeWbTint = -4f
        balanceToFilmStock = true
        contrast = 8f
        saturation = 6f
        vibrance = 4f
        gamutCompress = 3f
        localAdjustments = List(3) { index -> localAdjustment(index) }

        exposureCompensationEv = exposure
        autoExposure = false
        autoExposureMethod = "matrix"
        filmFormatMm = 33f
        cameraLensBlurUm = 0.15f
        sceneLatitudeActive = true
        sceneLatitudeHighlightKnee = 1.75f
        sceneLatitudeHighlightRoom = 2.5f
        sceneLatitudeShadowKnee = -1.5f
        sceneLatitudeShadowRoom = 1.25f
        sceneLatitudeRolloff = 2.5f
        sceneLatitudeMaxLift = 3.5f
        dateStampActive = true
        dateStampText = "'26 11 07"
        dateStampCorner = 2
        dateStampHeight = 0.0625f
        dateStampMargin = 0.0625f
        dateStampExposureEv = 1.75f
        dateStampColor = Triple(0.75f, 0.25f, 0.125f)
        cameraDiffusionState.apply {
            family = "glimmerglass"
            strength = 0.41f
            spatialScale = 1.1f
            haloWarmth = 0.05f
            coreIntensity = 0.91f
            coreSize = 0.85f
            haloIntensity = 0.82f
            haloSize = 0.75f
            bloomIntensity = 0.73f
            bloomSize = 1.2f
        }

        printIlluminant = "TH-KG3"
        printExposure = 1.05f
        printExposureCompensation = false
        printYFilterShift = 0.03f
        printMFilterShift = -0.02f
        enlargerLensBlur = 0.12f
        printDiffusionState.apply {
            family = "cinebloom"
            strength = 0.37f
            spatialScale = 0.9f
            haloWarmth = -0.04f
            coreIntensity = 0.88f
            coreSize = 0.84f
            haloIntensity = 0.79f
            haloSize = 0.76f
            bloomIntensity = 0.71f
            bloomSize = 1.2f
        }
        preflashExposure = 0.04f
        preflashYFilterShift = -0.01f
        preflashMFilterShift = 0.02f
        paperGrade = "4"

        scanLensBlur = 0.08f
        scanWhiteCorrection = true
        scanWhiteLevel = 0.97f
        scanBlackCorrection = true
        scanBlackLevel = 0.015f
        scanUnsharpMask = 0.55f to 0.82f

        outputColorSpace = ColorSpace.ADOBE_RGB
        savingCctfEncoding = false
        outputGamutCompress = OutputGamutCompress.ACES_RGC
        inputGamutCompress = InputGamutCompress.XY
        scanFilm = true

        // Keep the expensive stock-character stages inactive for deterministic device time while
        // retaining distinctive non-default parameter sentinels in each serialized family.
        grainActive = false
        grainSublayersActive = false
        grainParticleAreaUm2 = 0.3f
        grainParticleScale = Triple(0.9f, 1.1f, 1.9f)
        grainParticleScaleLayers = Triple(2.4f, 1.1f, 0.6f)
        grainDensityMin = Triple(0.08f, 0.09f, 0.13f)
        grainUniformity = Triple(0.96f, 0.95f, 0.98f)
        grainBlur = 0.61f
        grainBlurDyeCloudsUm = 0.9f
        grainMicroStructure = 0.24f to 28f
        grainNSubLayers = 2
        halationActive = false
        halScatterAmount = 0.83f
        halScatterSpatialScale = 0.91f
        halHalationAmount = 0.77f
        halHalationSpatialScale = 0.87f
        halBoostEv = 0.12f
        halProtectEv = 3.8f
        halBoostRange = 0.27f
        halScatterCoreUm = Triple(2.1f, 1.9f, 1.5f)
        halScatterTailUm = Triple(9.2f, 9.6f, 9f)
        halScatterTailWeightPct = Triple(77f, 64f, 66f)
        halHalationStrengthPct = Triple(4.9f, 1.4f, 0.1f)
        halFirstSigmaUm = Triple(64f, 63f, 62f)
        halNBounces = 2
        halBounceDecay = 0.47f
        halRenormalize = false
        couplersActive = false
        couplersAmount = 0.81f
        couplersInhibitionSamelayer = 0.91f
        couplersInhibitionInterlayer = 0.89f
        couplersGammaSamelayer = Triple(0.33f, 0.31f, 0.26f)
        couplersGammaRtoGb = 0.34f to 0.29f
        couplersGammaGtoRb = 0.14f to 0.35f
        couplersGammaBtoRg = 0.16f to 0.21f
        couplersDiffusionSizeUm = 21f
        couplersDiffusionTailUm = 195f
        couplersDiffusionTailWeight = 0.055f
        glareActive = false
        glarePercent = 0.04f
        glareRoughness = 0.62f
        glareBlur = 0.45f
        contrastMaskActive = false
        contrastMaskHighlights = 1.25f
        contrastMaskShadows = 0.75f
        contrastMaskCore = 0.8f
        contrastMaskScale = 0.04f

        filmGammaFactor = 1.03f
        printGammaFactor = 0.97f
        morphGammaFactor = 1.02f
        morphGammaFactorFast = 1.01f
        morphGammaFactorSlow = 0.99f
        morphGammaFactorRed = 1.01f
        morphGammaFactorGreen = 0.98f
        morphGammaFactorBlue = 1.02f
        morphDeveloperExhaustion = 0.03f
        toneCurveActive = true
        toneCurveMaster = listOf(0f to 0f, 0.4f to 0.43f, 1f to 1f)
        toneCurveRed = listOf(0f to 0f, 1f to 0.98f)
        toneCurveGreen = listOf(0f to 0.01f, 1f to 1f)
        toneCurveBlue = listOf(0f to 0f, 0.7f to 0.72f, 1f to 1f)
    }

    private fun localAdjustment(index: Int): LocalAdjustment = LocalAdjustment(
        mask = Mask(
            components = listOf(
                Mask.Component(
                    mode = BlendMode.ADD,
                    shape = MaskComponent.Radial(
                        cx = 0.25f + index * 0.2f,
                        cy = 0.5f,
                        rx = 0.18f,
                        ry = 0.18f,
                    ),
                ),
            ),
        ),
        delta = TierADelta(exposureEv = index * 0.25f),
    )

    internal fun clearDurableSource(context: Context) = runBlocking {
        val runtime = sourceAccessRuntime(context)
        val generation = runtime.mutations.begin()
        check(runtime.submit(generation) { runtime.coordinator.clear() }.await() != null) {
            "source clear was superseded"
        }
    }

    internal fun verifyRichCursor(document: EditorSessionDocument, expectedTool: EditorToolState) {
        check(document.current.rotationDegrees == EXPECTED_ROTATION) { "rotation was not restored" }
        check(document.history.undo.size == EXPECTED_UNDO) { "undo branch was not restored" }
        check(document.history.redo.size == EXPECTED_REDO) { "redo branch was not restored" }
        check(document.tool == expectedTool) { "complete editor tool cursor was not restored" }
        check(document.preset.baseJson != null && document.preset.fullJson != null) {
            "preset amount anchors were not restored"
        }
        check(document.preset.clipboardJson != null) { "settings clipboard was not restored" }
        val params = ParamsState()
        Presets.decode(AtomicJsonStore.parseObject(document.current.paramsJson), params)
        check(params.localAdjustments.size == 3) { "mask collection was not restored" }
        check(params.exposureCompensationEv == 1.25f) { "current edit cursor was not restored" }
        check(params.crop) { "enabled crop was not restored" }
        check(params.cropCenter == (0.43f to 0.57f)) { "asymmetric crop center was not restored" }
        check(params.cropSize == (0.62f to 0.38f)) { "asymmetric crop size was not restored" }

        Ticket139ParamsOracle.verifyPersistedParamsSentinels(document.current.paramsJson)
    }
}
