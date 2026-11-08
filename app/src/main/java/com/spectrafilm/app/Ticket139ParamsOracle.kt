/*
 * Spektrafilm for Android — ticket #139 typed persisted-Params oracle. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The independent field-level oracle the #139 probe and its unit tests check persisted
 * ParamsState JSON against. Split out of [Ticket139EditorProbe] (#273).
 */
internal object Ticket139ParamsOracle {
    private val EXPECTED_PERSISTED_PARAMS_FIELDS = setOf(
        "filmProfile",
        "printProfile",
        "input.inputColorSpace",
        "input.inputCctfDecoding",
        "input.spectralUpsampling",
        "input.adaptationWindow",
        "input.adaptationSurface",
        "input.spectralGaussianBlur",
        "input.filterUv",
        "input.filterIr",
        "input.upscaleFactor",
        "input.crop",
        "input.cropCenter",
        "input.cropSize",
        "input.straighten",
        "raw.whiteBalance",
        "raw.temperature",
        "raw.tint",
        "raw.hotPixels",
        "raw.chromaNoise",
        "creativeWb.temp",
        "creativeWb.tint",
        "creativeWb.balanceToFilmStock",
        "grade.contrast",
        "grade.saturation",
        "grade.vibrance",
        "grade.gamutCompress",
        "masks",
        "camera.exposureCompensationEv",
        "camera.autoExposure",
        "camera.autoExposureMethod",
        "camera.filmFormatMm",
        "camera.lensBlurUm",
        "camera.diffusion.active",
        "camera.diffusion.family",
        "camera.diffusion.strength",
        "camera.diffusion.spatialScale",
        "camera.diffusion.haloWarmth",
        "camera.diffusion.coreIntensity",
        "camera.diffusion.coreSize",
        "camera.diffusion.haloIntensity",
        "camera.diffusion.haloSize",
        "camera.diffusion.bloomIntensity",
        "camera.diffusion.bloomSize",
        "camera.sceneLatitude.active",
        "camera.sceneLatitude.highlightKnee",
        "camera.sceneLatitude.highlightRoom",
        "camera.sceneLatitude.shadowKnee",
        "camera.sceneLatitude.shadowRoom",
        "camera.sceneLatitude.rolloff",
        "camera.sceneLatitude.maxLift",
        "camera.dateStamp.active",
        "camera.dateStamp.text",
        "camera.dateStamp.corner",
        "camera.dateStamp.height",
        "camera.dateStamp.margin",
        "camera.dateStamp.exposureEv",
        "camera.dateStamp.color",
        "enlarger.illuminant",
        "enlarger.printExposure",
        "enlarger.printExposureCompensation",
        "enlarger.yFilterShift",
        "enlarger.mFilterShift",
        "enlarger.lensBlur",
        "enlarger.diffusion.active",
        "enlarger.diffusion.family",
        "enlarger.diffusion.strength",
        "enlarger.diffusion.spatialScale",
        "enlarger.diffusion.haloWarmth",
        "enlarger.diffusion.coreIntensity",
        "enlarger.diffusion.coreSize",
        "enlarger.diffusion.haloIntensity",
        "enlarger.diffusion.haloSize",
        "enlarger.diffusion.bloomIntensity",
        "enlarger.diffusion.bloomSize",
        "enlarger.preflashExposure",
        "enlarger.preflashYFilterShift",
        "enlarger.preflashMFilterShift",
        "enlarger.paperGrade",
        "scanner.lensBlur",
        "scanner.whiteCorrection",
        "scanner.whiteLevel",
        "scanner.blackCorrection",
        "scanner.blackLevel",
        "scanner.unsharpMask",
        "output.outputColorSpace",
        "output.savingCctfEncoding",
        "output.outputGamutCompress",
        "output.inputGamutCompress",
        "output.scanFilm",
        "grain.active",
        "grain.sublayersActive",
        "grain.particleAreaUm2",
        "grain.particleScale",
        "grain.particleScaleLayers",
        "grain.densityMin",
        "grain.uniformity",
        "grain.blur",
        "grain.blurDyeCloudsUm",
        "grain.microStructure",
        "grain.nSubLayers",
        "halation.active",
        "halation.scatterAmount",
        "halation.scatterSpatialScale",
        "halation.halationAmount",
        "halation.halationSpatialScale",
        "halation.boostEv",
        "halation.protectEv",
        "halation.boostRange",
        "halation.scatterCoreUm",
        "halation.scatterTailUm",
        "halation.scatterTailWeightPct",
        "halation.halationStrengthPct",
        "halation.firstSigmaUm",
        "halation.nBounces",
        "halation.bounceDecay",
        "halation.renormalize",
        "couplers.active",
        "couplers.amount",
        "couplers.inhibitionSamelayer",
        "couplers.inhibitionInterlayer",
        "couplers.gammaSamelayer",
        "couplers.gammaRtoGb",
        "couplers.gammaGtoRb",
        "couplers.gammaBtoRg",
        "couplers.diffusionSizeUm",
        "couplers.diffusionTailUm",
        "couplers.diffusionTailWeight",
        "glare.active",
        "glare.percent",
        "glare.roughness",
        "glare.blur",
        "contrastMask.active",
        "contrastMask.highlights",
        "contrastMask.shadows",
        "contrastMask.core",
        "contrastMask.scale",
        "experimental.filmGammaFactor",
        "experimental.printGammaFactor",
        "experimental.morphActive",
        "experimental.morphGammaFactor",
        "experimental.morphGammaFactorFast",
        "experimental.morphGammaFactorSlow",
        "experimental.morphGammaFactorRed",
        "experimental.morphGammaFactorGreen",
        "experimental.morphGammaFactorBlue",
        "experimental.morphDeveloperExhaustion",
        "toneCurve.active",
        "toneCurve.master",
        "toneCurve.red",
        "toneCurve.green",
        "toneCurve.blue",
    )

    private sealed interface PersistedSentinel {
        data class Text(val value: String) : PersistedSentinel
        data class Flag(val value: Boolean) : PersistedSentinel
        data class Number(val canonical: String) : PersistedSentinel
        data class Json(val canonical: String) : PersistedSentinel
    }

    private fun text(value: String) = PersistedSentinel.Text(value)
    private fun flag(value: Boolean) = PersistedSentinel.Flag(value)
    private fun number(value: String) = PersistedSentinel.Number(value)
    private fun json(value: String) = PersistedSentinel.Json(
        canonicalJson(
            value.trimStart().let { encoded ->
                if (encoded.startsWith("{")) JSONObject(encoded) else JSONArray(encoded)
            },
        ),
    )

    /**
     * Independent typed oracle: no value is read from the probe's rich params or from the preset encoder.
     * Each persisted property has one exact sentinel, including the complete mask document.
     */
    private val EXPECTED_PERSISTED_PARAMS_SENTINELS: Map<String, PersistedSentinel> = linkedMapOf(
        "filmProfile" to text("kodak_ektar_100"),
        "printProfile" to text("kodak_supra_endura"),
        "input.inputColorSpace" to text("ProPhoto RGB"),
        "input.inputCctfDecoding" to flag(true),
        "input.spectralUpsampling" to text("MALLETT2019"),
        "input.adaptationWindow" to flag(false),
        "input.adaptationSurface" to flag(true),
        "input.spectralGaussianBlur" to number("0.35"),
        "input.filterUv" to json("[0.1,405,7]"),
        "input.filterIr" to json("[0.2,680,14]"),
        "input.upscaleFactor" to number("1.25"),
        "input.crop" to flag(true),
        "input.cropCenter" to json("[0.43,0.57]"),
        "input.cropSize" to json("[0.62,0.38]"),
        "input.straighten" to number("-2.5"),
        "raw.whiteBalance" to text("CUSTOM"),
        "raw.temperature" to number("4875"),
        "raw.tint" to number("0.92"),
        "raw.hotPixels" to flag(true),
        "raw.chromaNoise" to number("12"),
        "creativeWb.temp" to number("7"),
        "creativeWb.tint" to number("-4"),
        "creativeWb.balanceToFilmStock" to flag(true),
        "grade.contrast" to number("8"),
        "grade.saturation" to number("6"),
        "grade.vibrance" to number("4"),
        "grade.gamutCompress" to number("3"),
        "masks" to json(
            """
            {
              "schema":"org.spektrafilm.mask-set","version":1,"adjustments":[
                {"delta":{"temp":0,"texture":0,"tint":0,"blacks":0,"exposureEv":0,"saturation":0,"whites":0,"highlights":0,"clarity":0,"contrast":0,"hue":0,"shadows":0,"sharpness":0},"mask":{"components":[{"mode":"ADD","invert":false,"shape":{"angleDeg":0,"feather":0.5,"cx":0.25,"cy":0.5,"rx":0.18,"ry":0.18,"type":"radial"},"value":1}],"invert":false,"opacity":1}},
                {"delta":{"temp":0,"texture":0,"tint":0,"blacks":0,"exposureEv":0.25,"saturation":0,"whites":0,"highlights":0,"clarity":0,"contrast":0,"hue":0,"shadows":0,"sharpness":0},"mask":{"components":[{"mode":"ADD","invert":false,"shape":{"angleDeg":0,"feather":0.5,"cx":0.45,"cy":0.5,"rx":0.18,"ry":0.18,"type":"radial"},"value":1}],"invert":false,"opacity":1}},
                {"delta":{"temp":0,"texture":0,"tint":0,"blacks":0,"exposureEv":0.5,"saturation":0,"whites":0,"highlights":0,"clarity":0,"contrast":0,"hue":0,"shadows":0,"sharpness":0},"mask":{"components":[{"mode":"ADD","invert":false,"shape":{"angleDeg":0,"feather":0.5,"cx":0.65,"cy":0.5,"rx":0.18,"ry":0.18,"type":"radial"},"value":1}],"invert":false,"opacity":1}}
              ]
            }
            """.trimIndent(),
        ),
        "camera.exposureCompensationEv" to number("1.25"),
        "camera.autoExposure" to flag(false),
        "camera.autoExposureMethod" to text("matrix"),
        "camera.filmFormatMm" to number("33"),
        "camera.lensBlurUm" to number("0.15"),
        "camera.diffusion.active" to flag(false),
        "camera.diffusion.family" to text("glimmerglass"),
        "camera.diffusion.strength" to number("0.41"),
        "camera.diffusion.spatialScale" to number("1.1"),
        "camera.diffusion.haloWarmth" to number("0.05"),
        "camera.diffusion.coreIntensity" to number("0.91"),
        "camera.diffusion.coreSize" to number("0.85"),
        "camera.diffusion.haloIntensity" to number("0.82"),
        "camera.diffusion.haloSize" to number("0.75"),
        "camera.diffusion.bloomIntensity" to number("0.73"),
        "camera.diffusion.bloomSize" to number("1.2"),
        "camera.sceneLatitude.active" to flag(true),
        "camera.sceneLatitude.highlightKnee" to number("1.75"),
        "camera.sceneLatitude.highlightRoom" to number("2.5"),
        "camera.sceneLatitude.shadowKnee" to number("-1.5"),
        "camera.sceneLatitude.shadowRoom" to number("1.25"),
        "camera.sceneLatitude.rolloff" to number("2.5"),
        "camera.sceneLatitude.maxLift" to number("3.5"),
        "camera.dateStamp.active" to flag(true),
        "camera.dateStamp.text" to text("'26 11 07"),
        "camera.dateStamp.corner" to number("2"),
        "camera.dateStamp.height" to number("0.0625"),
        "camera.dateStamp.margin" to number("0.0625"),
        "camera.dateStamp.exposureEv" to number("1.75"),
        "camera.dateStamp.color" to json("[0.75,0.25,0.125]"),
        "enlarger.illuminant" to text("TH-KG3"),
        "enlarger.printExposure" to number("1.05"),
        "enlarger.printExposureCompensation" to flag(false),
        "enlarger.yFilterShift" to number("0.03"),
        "enlarger.mFilterShift" to number("-0.02"),
        "enlarger.lensBlur" to number("0.12"),
        "enlarger.diffusion.active" to flag(false),
        "enlarger.diffusion.family" to text("cinebloom"),
        "enlarger.diffusion.strength" to number("0.37"),
        "enlarger.diffusion.spatialScale" to number("0.9"),
        "enlarger.diffusion.haloWarmth" to number("-0.04"),
        "enlarger.diffusion.coreIntensity" to number("0.88"),
        "enlarger.diffusion.coreSize" to number("0.84"),
        "enlarger.diffusion.haloIntensity" to number("0.79"),
        "enlarger.diffusion.haloSize" to number("0.76"),
        "enlarger.diffusion.bloomIntensity" to number("0.71"),
        "enlarger.diffusion.bloomSize" to number("1.2"),
        "enlarger.preflashExposure" to number("0.04"),
        "enlarger.preflashYFilterShift" to number("-0.01"),
        "enlarger.preflashMFilterShift" to number("0.02"),
        "enlarger.paperGrade" to text("4"),
        "scanner.lensBlur" to number("0.08"),
        "scanner.whiteCorrection" to flag(true),
        "scanner.whiteLevel" to number("0.97"),
        "scanner.blackCorrection" to flag(true),
        "scanner.blackLevel" to number("0.015"),
        "scanner.unsharpMask" to json("[0.55,0.82]"),
        "output.outputColorSpace" to text("ADOBE_RGB"),
        "output.savingCctfEncoding" to flag(false),
        "output.outputGamutCompress" to text("ACES_RGC"),
        "output.inputGamutCompress" to text("XY"),
        "output.scanFilm" to flag(true),
        "grain.active" to flag(false),
        "grain.sublayersActive" to flag(false),
        "grain.particleAreaUm2" to number("0.3"),
        "grain.particleScale" to json("[0.9,1.1,1.9]"),
        "grain.particleScaleLayers" to json("[2.4,1.1,0.6]"),
        "grain.densityMin" to json("[0.08,0.09,0.13]"),
        "grain.uniformity" to json("[0.96,0.95,0.98]"),
        "grain.blur" to number("0.61"),
        "grain.blurDyeCloudsUm" to number("0.9"),
        "grain.microStructure" to json("[0.24,28]"),
        "grain.nSubLayers" to number("2"),
        "halation.active" to flag(false),
        "halation.scatterAmount" to number("0.83"),
        "halation.scatterSpatialScale" to number("0.91"),
        "halation.halationAmount" to number("0.77"),
        "halation.halationSpatialScale" to number("0.87"),
        "halation.boostEv" to number("0.12"),
        "halation.protectEv" to number("3.8"),
        "halation.boostRange" to number("0.27"),
        "halation.scatterCoreUm" to json("[2.1,1.9,1.5]"),
        "halation.scatterTailUm" to json("[9.2,9.6,9]"),
        "halation.scatterTailWeightPct" to json("[77,64,66]"),
        "halation.halationStrengthPct" to json("[4.9,1.4,0.1]"),
        "halation.firstSigmaUm" to json("[64,63,62]"),
        "halation.nBounces" to number("2"),
        "halation.bounceDecay" to number("0.47"),
        "halation.renormalize" to flag(false),
        "couplers.active" to flag(false),
        "couplers.amount" to number("0.81"),
        "couplers.inhibitionSamelayer" to number("0.91"),
        "couplers.inhibitionInterlayer" to number("0.89"),
        "couplers.gammaSamelayer" to json("[0.33,0.31,0.26]"),
        "couplers.gammaRtoGb" to json("[0.34,0.29]"),
        "couplers.gammaGtoRb" to json("[0.14,0.35]"),
        "couplers.gammaBtoRg" to json("[0.16,0.21]"),
        "couplers.diffusionSizeUm" to number("21"),
        "couplers.diffusionTailUm" to number("195"),
        "couplers.diffusionTailWeight" to number("0.055"),
        "glare.active" to flag(false),
        "glare.percent" to number("0.04"),
        "glare.roughness" to number("0.62"),
        "glare.blur" to number("0.45"),
        "contrastMask.active" to flag(false),
        "contrastMask.highlights" to number("1.25"),
        "contrastMask.shadows" to number("0.75"),
        "contrastMask.core" to number("0.8"),
        "contrastMask.scale" to number("0.04"),
        "experimental.filmGammaFactor" to number("1.03"),
        "experimental.printGammaFactor" to number("0.97"),
        "experimental.morphActive" to flag(false),
        "experimental.morphGammaFactor" to number("1.02"),
        "experimental.morphGammaFactorFast" to number("1.01"),
        "experimental.morphGammaFactorSlow" to number("0.99"),
        "experimental.morphGammaFactorRed" to number("1.01"),
        "experimental.morphGammaFactorGreen" to number("0.98"),
        "experimental.morphGammaFactorBlue" to number("1.02"),
        "experimental.morphDeveloperExhaustion" to number("0.03"),
        "toneCurve.active" to flag(true),
        "toneCurve.master" to json("[[0,0],[0.4,0.43],[1,1]]"),
        "toneCurve.red" to json("[[0,0],[1,0.98]]"),
        "toneCurve.green" to json("[[0,0.01],[1,1]]"),
        "toneCurve.blue" to json("[[0,0],[0.7,0.72],[1,1]]"),
    )

    /**
     * Exact, field-level oracle for every persisted ParamsState property. Arrays represent one
     * aggregate ParamsState property (pair/triple/curve), and the mask document represents the one
     * localAdjustments property. The static path set catches an encoder omission even if both the
     * default and rich encoders accidentally omit the same field.
     */
    internal fun verifyPersistedParamsSentinels(
        paramsJson: String,
        expectedExposure: String = "1.25",
    ) {
        val actual = persistedParamsFieldValues(AtomicJsonStore.parseObject(paramsJson))
        check(EXPECTED_PERSISTED_PARAMS_SENTINELS.keys == EXPECTED_PERSISTED_PARAMS_FIELDS) {
            "typed Params oracle drifted from the independent schema path inventory"
        }
        check(actual.keys == EXPECTED_PERSISTED_PARAMS_SENTINELS.keys) {
            "rich Params paths incomplete: missing=" +
                (EXPECTED_PERSISTED_PARAMS_SENTINELS.keys - actual.keys).sorted() +
                " unexpected=" + (actual.keys - EXPECTED_PERSISTED_PARAMS_SENTINELS.keys).sorted()
        }
        val expected = EXPECTED_PERSISTED_PARAMS_SENTINELS.toMutableMap().apply {
            this["camera.exposureCompensationEv"] = number(expectedExposure)
        }
        val mismatches = expected.keys.filter { path ->
            actual[path] != expected[path]
        }
        check(mismatches.isEmpty()) {
            "rich Params typed sentinel mismatch: " + mismatches.joinToString { path ->
                "$path expected=${expected[path]} actual=${actual[path]}"
            }
        }
    }

    private fun persistedParamsFieldValues(root: JSONObject): Map<String, PersistedSentinel> {
        val fields = linkedMapOf<String, PersistedSentinel>()
        fun visit(value: Any?, path: String) {
            if (value is JSONObject && path != "masks") {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys += iterator.next()
                keys.sorted().forEach { key ->
                    if (path.isEmpty() && (key == "schema" || key == "version")) return@forEach
                    visit(value.get(key), if (path.isEmpty()) key else "$path.$key")
                }
            } else {
                fields[path] = when (value) {
                    is String -> text(value)
                    is Boolean -> flag(value)
                    is Number -> PersistedSentinel.Number(canonicalNumber(value))
                    is JSONObject, is JSONArray -> PersistedSentinel.Json(canonicalJson(value))
                    else -> error("unsupported persisted Params value at $path")
                }
            }
        }
        visit(root, "")
        return fields
    }

    private fun canonicalNumber(value: Number): String {
        val asDouble = value.toDouble()
        check(asDouble.isFinite()) { "non-finite persisted number" }
        return if (asDouble % 1.0 == 0.0) {
            java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        } else {
            asDouble.toFloat().toString()
        }
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iterator = value.keys()
            while (iterator.hasNext()) keys += iterator.next()
            keys.sorted().joinToString(prefix = "{", postfix = "}") { key ->
                JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
            canonicalJson(value.get(it))
        }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> canonicalNumber(value)
        JSONObject.NULL, null -> "null"
        else -> error("unsupported JSON sentinel type ${value.javaClass.simpleName}")
    }
}
