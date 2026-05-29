/*
 * SpectraFilm for Android — JNI bridge.
 * GPLv3. Bridges com.spectrafilm.engine.SpektraEngine (Kotlin) to the spektra C API.
 *
 * Design notes:
 *  - Image buffers cross the boundary as direct java.nio.ByteBuffer (float32 RGB) to
 *    avoid per-pixel JNI traffic; only width/height/colorspace are passed as ints.
 *  - The native engine handle is stored as a Kotlin Long and passed back in.
 *  - SpektraParams (a nested Kotlin data class tree) is marshalled into the flat
 *    spk_params struct by reading the high-traffic fields the scan_film route needs.
 *    Field/method IDs are cached on first use.
 *  - The output is wrapped into a com.spectrafilm.engine.SimResult holding a direct
 *    float ByteBuffer plus width/height/ColorSpace.
 */
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "spektra.h"

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL \
    Java_com_spectrafilm_engine_SpektraEngine_##name

namespace {

// Read a jstring into a std::string (empty on null).
std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Call a no-arg getter on `obj`, returning the (Object) result, or null.
jobject call_obj(JNIEnv* env, jobject obj, const char* getter, const char* sig) {
    if (!obj) return nullptr;
    jclass cls = env->GetObjectClass(obj);
    jmethodID m = env->GetMethodID(cls, getter, sig);
    env->DeleteLocalRef(cls);
    if (!m) { env->ExceptionClear(); return nullptr; }
    return env->CallObjectMethod(obj, m);
}

float call_float(JNIEnv* env, jobject obj, const char* getter) {
    if (!obj) return 0.0f;
    jclass cls = env->GetObjectClass(obj);
    jmethodID m = env->GetMethodID(cls, getter, "()F");
    env->DeleteLocalRef(cls);
    if (!m) { env->ExceptionClear(); return 0.0f; }
    return env->CallFloatMethod(obj, m);
}

bool call_bool(JNIEnv* env, jobject obj, const char* getter) {
    if (!obj) return false;
    jclass cls = env->GetObjectClass(obj);
    jmethodID m = env->GetMethodID(cls, getter, "()Z");
    env->DeleteLocalRef(cls);
    if (!m) { env->ExceptionClear(); return false; }
    return env->CallBooleanMethod(obj, m) == JNI_TRUE;
}

int call_int(JNIEnv* env, jobject obj, const char* getter) {
    if (!obj) return 0;
    jclass cls = env->GetObjectClass(obj);
    jmethodID m = env->GetMethodID(cls, getter, "()I");
    env->DeleteLocalRef(cls);
    if (!m) { env->ExceptionClear(); return 0; }
    return env->CallIntMethod(obj, m);
}

// Unbox a java.lang.Float (or Number) into a float.
float unbox_float(JNIEnv* env, jobject boxed) {
    if (!boxed) return 0.0f;
    jclass cls = env->GetObjectClass(boxed);
    jmethodID m = env->GetMethodID(cls, "floatValue", "()F");
    env->DeleteLocalRef(cls);
    if (!m) { env->ExceptionClear(); return 0.0f; }
    return env->CallFloatMethod(boxed, m);
}

// Read a kotlin.Triple<Float,Float,Float> via getFirst/getSecond/getThird (each
// returns java.lang.Object -> java.lang.Float). Writes the three components to
// out[3]. Leaves out untouched on null (keeps the default already there).
void read_triple_f(JNIEnv* env, jobject obj, const char* getter, float out[3]) {
    jobject t = call_obj(env, obj, getter, "()Lkotlin/Triple;");
    if (!t) return;
    jobject a = call_obj(env, t, "getFirst", "()Ljava/lang/Object;");
    jobject b = call_obj(env, t, "getSecond", "()Ljava/lang/Object;");
    jobject c = call_obj(env, t, "getThird", "()Ljava/lang/Object;");
    out[0] = unbox_float(env, a);
    out[1] = unbox_float(env, b);
    out[2] = unbox_float(env, c);
    if (a) env->DeleteLocalRef(a);
    if (b) env->DeleteLocalRef(b);
    if (c) env->DeleteLocalRef(c);
    env->DeleteLocalRef(t);
}

// Read a kotlin.Pair<Float,Float> via getFirst/getSecond. Writes to out[2].
void read_pair_f(JNIEnv* env, jobject obj, const char* getter, float out[2]) {
    jobject t = call_obj(env, obj, getter, "()Lkotlin/Pair;");
    if (!t) return;
    jobject a = call_obj(env, t, "getFirst", "()Ljava/lang/Object;");
    jobject b = call_obj(env, t, "getSecond", "()Ljava/lang/Object;");
    out[0] = unbox_float(env, a);
    out[1] = unbox_float(env, b);
    if (a) env->DeleteLocalRef(a);
    if (b) env->DeleteLocalRef(b);
    env->DeleteLocalRef(t);
}

// Map a Kotlin ColorSpace enum (com.spectrafilm.engine.ColorSpace) to spk_color_space
// via its ordinal(). Ordinals match: SRGB=0, ADOBE_RGB=1, PROPHOTO=2, ... LINEAR_SRGB=5.
spk_color_space enum_ordinal_cs(JNIEnv* env, jobject e) {
    if (!e) return SPK_CS_SRGB;
    jclass cls = env->GetObjectClass(e);
    jmethodID m = env->GetMethodID(cls, "ordinal", "()I");
    env->DeleteLocalRef(cls);
    if (!m) { env->ExceptionClear(); return SPK_CS_SRGB; }
    return static_cast<spk_color_space>(env->CallIntMethod(e, m));
}

spk_rgb2raw enum_ordinal_rgb2raw(JNIEnv* env, jobject e) {
    if (!e) return SPK_RGB2RAW_HANATOS2025;
    jclass cls = env->GetObjectClass(e);
    jmethodID m = env->GetMethodID(cls, "ordinal", "()I");
    env->DeleteLocalRef(cls);
    if (!m) { env->ExceptionClear(); return SPK_RGB2RAW_HANATOS2025; }
    return static_cast<spk_rgb2raw>(env->CallIntMethod(e, m));
}

// Marshal a SpektraParams jobject into a flat spk_params. The returned struct
// holds pointers into the std::string storage parameters, which must outlive use.
struct ParamStorage {
    std::string film_profile;
    std::string print_profile;
};

// Read a DiffusionFilterParams jobject into the spk_params diffusion-filter
// fields (camera/enlarger share the same struct). `set` writes through a small
// lambda so the same reader serves both prefixes.
void read_diffusion_filter(JNIEnv* env, jobject df,
                           int32_t* active, float* strength, float* spatial_scale,
                           float* halo_warmth, float* core_intensity,
                           float* core_size, float* halo_intensity, float* halo_size,
                           float* bloom_intensity, float* bloom_size) {
    if (!df) return;
    *active = call_bool(env, df, "getActive") ? 1 : 0;
    *strength = call_float(env, df, "getStrength");
    *spatial_scale = call_float(env, df, "getSpatialScale");
    *halo_warmth = call_float(env, df, "getHaloWarmth");
    *core_intensity = call_float(env, df, "getCoreIntensity");
    *core_size = call_float(env, df, "getCoreSize");
    *halo_intensity = call_float(env, df, "getHaloIntensity");
    *halo_size = call_float(env, df, "getHaloSize");
    *bloom_intensity = call_float(env, df, "getBloomIntensity");
    *bloom_size = call_float(env, df, "getBloomSize");
}

bool marshal_params(JNIEnv* env, jobject params, spk_params* out, ParamStorage* store) {
    // Top-level: filmProfile / printProfile (String), and the nested objects.
    store->film_profile = jstr(env,
        static_cast<jstring>(call_obj(env, params, "getFilmProfile",
                                      "()Ljava/lang/String;")));
    store->print_profile = jstr(env,
        static_cast<jstring>(call_obj(env, params, "getPrintProfile",
                                      "()Ljava/lang/String;")));
    out->film_profile = store->film_profile.c_str();
    out->print_profile = store->print_profile.c_str();

    // Seed every field with the physical defaults so unread/absent fields keep
    // the parity-reproducing defaults; the jobject getters override below.
    spk_default_params(out);
    if (!params) return true;

    jobject camera   = call_obj(env, params, "getCamera",
        "()Lcom/spectrafilm/engine/CameraParams;");
    jobject enlarger = call_obj(env, params, "getEnlarger",
        "()Lcom/spectrafilm/engine/EnlargerParams;");
    jobject scanner  = call_obj(env, params, "getScanner",
        "()Lcom/spectrafilm/engine/ScannerParams;");
    jobject filmR    = call_obj(env, params, "getFilmRender",
        "()Lcom/spectrafilm/engine/FilmRenderingParams;");
    jobject printR   = call_obj(env, params, "getPrintRender",
        "()Lcom/spectrafilm/engine/PrintRenderingParams;");
    jobject io       = call_obj(env, params, "getIo",
        "()Lcom/spectrafilm/engine/IoParams;");
    jobject settings = call_obj(env, params, "getSettings",
        "()Lcom/spectrafilm/engine/SettingsParams;");

    // ---- camera ----
    if (camera) {
        out->exposure_compensation_ev = call_float(env, camera, "getExposureCompensationEv");
        out->auto_exposure = call_bool(env, camera, "getAutoExposure") ? 1 : 0;
        out->lens_blur_um = call_float(env, camera, "getLensBlurUm");
        out->film_format_mm = call_float(env, camera, "getFilmFormatMm");
        read_triple_f(env, camera, "getFilterUv", out->camera_filter_uv);
        read_triple_f(env, camera, "getFilterIr", out->camera_filter_ir);
        jobject df = call_obj(env, camera, "getDiffusionFilter",
            "()Lcom/spectrafilm/engine/DiffusionFilterParams;");
        read_diffusion_filter(env, df, &out->camera_diffusion_active,
            &out->camera_diffusion_strength, &out->camera_diffusion_spatial_scale,
            &out->camera_diffusion_halo_warmth, &out->camera_diffusion_core_intensity,
            &out->camera_diffusion_core_size, &out->camera_diffusion_halo_intensity,
            &out->camera_diffusion_halo_size, &out->camera_diffusion_bloom_intensity,
            &out->camera_diffusion_bloom_size);
        if (df) env->DeleteLocalRef(df);
    }

    // ---- enlarger ----
    if (enlarger) {
        out->print_exposure = call_float(env, enlarger, "getPrintExposure");
        out->print_exposure_compensation =
            call_bool(env, enlarger, "getPrintExposureCompensation") ? 1 : 0;
        out->normalize_print_exposure =
            call_bool(env, enlarger, "getNormalizePrintExposure") ? 1 : 0;
        out->y_filter_shift = call_float(env, enlarger, "getYFilterShift");
        out->m_filter_shift = call_float(env, enlarger, "getMFilterShift");
        out->y_filter_neutral = call_float(env, enlarger, "getYFilterNeutral");
        out->m_filter_neutral = call_float(env, enlarger, "getMFilterNeutral");
        out->c_filter_neutral = call_float(env, enlarger, "getCFilterNeutral");
        out->enlarger_lens_blur = call_float(env, enlarger, "getLensBlur");
        out->preflash_exposure = call_float(env, enlarger, "getPreflashExposure");
        out->preflash_y_filter_shift = call_float(env, enlarger, "getPreflashYFilterShift");
        out->preflash_m_filter_shift = call_float(env, enlarger, "getPreflashMFilterShift");
        jobject df = call_obj(env, enlarger, "getDiffusionFilter",
            "()Lcom/spectrafilm/engine/DiffusionFilterParams;");
        read_diffusion_filter(env, df, &out->enlarger_diffusion_active,
            &out->enlarger_diffusion_strength, &out->enlarger_diffusion_spatial_scale,
            &out->enlarger_diffusion_halo_warmth, &out->enlarger_diffusion_core_intensity,
            &out->enlarger_diffusion_core_size, &out->enlarger_diffusion_halo_intensity,
            &out->enlarger_diffusion_halo_size, &out->enlarger_diffusion_bloom_intensity,
            &out->enlarger_diffusion_bloom_size);
        if (df) env->DeleteLocalRef(df);
    }

    // ---- scanner ----
    if (scanner) {
        out->scanner_lens_blur = call_float(env, scanner, "getLensBlur");
        out->scanner_white_correction = call_bool(env, scanner, "getWhiteCorrection") ? 1 : 0;
        out->scanner_black_correction = call_bool(env, scanner, "getBlackCorrection") ? 1 : 0;
        out->scanner_white_level = call_float(env, scanner, "getWhiteLevel");
        out->scanner_black_level = call_float(env, scanner, "getBlackLevel");
        read_pair_f(env, scanner, "getUnsharpMask", out->scanner_unsharp);
    }

    // ---- film rendering ----
    if (filmR) {
        out->density_curve_gamma = call_float(env, filmR, "getDensityCurveGamma");
        jobject grain = call_obj(env, filmR, "getGrain",
            "()Lcom/spectrafilm/engine/GrainParams;");
        jobject halation = call_obj(env, filmR, "getHalation",
            "()Lcom/spectrafilm/engine/HalationParams;");
        jobject dir = call_obj(env, filmR, "getDirCouplers",
            "()Lcom/spectrafilm/engine/DirCouplersParams;");
        jobject glare = call_obj(env, filmR, "getGlare",
            "()Lcom/spectrafilm/engine/GlareParams;");

        if (grain) {
            out->grain_active = call_bool(env, grain, "getActive") ? 1 : 0;
            out->grain_sublayers_active = call_bool(env, grain, "getSublayersActive") ? 1 : 0;
            out->grain_particle_area_um2 = call_float(env, grain, "getAgxParticleAreaUm2");
            read_triple_f(env, grain, "getAgxParticleScale", out->grain_particle_scale);
            read_triple_f(env, grain, "getAgxParticleScaleLayers", out->grain_particle_scale_layers);
            read_triple_f(env, grain, "getDensityMin", out->grain_density_min);
            read_triple_f(env, grain, "getUniformity", out->grain_uniformity);
            out->grain_blur = call_float(env, grain, "getBlur");
            out->grain_blur_dye_clouds_um = call_float(env, grain, "getBlurDyeCloudsUm");
            read_pair_f(env, grain, "getMicroStructure", out->grain_micro_structure);
            out->grain_n_sub_layers = call_int(env, grain, "getNSubLayers");
            env->DeleteLocalRef(grain);
        }
        if (halation) {
            out->halation_active = call_bool(env, halation, "getActive") ? 1 : 0;
            out->halation_scatter_amount = call_float(env, halation, "getScatterAmount");
            out->halation_scatter_spatial_scale = call_float(env, halation, "getScatterSpatialScale");
            out->halation_halation_amount = call_float(env, halation, "getHalationAmount");
            out->halation_halation_spatial_scale = call_float(env, halation, "getHalationSpatialScale");
            read_triple_f(env, halation, "getScatterCoreUm", out->halation_scatter_core_um);
            read_triple_f(env, halation, "getScatterTailUm", out->halation_scatter_tail_um);
            read_triple_f(env, halation, "getScatterTailWeight", out->halation_scatter_tail_weight);
            out->halation_boost_ev = call_float(env, halation, "getBoostEv");
            out->halation_boost_range = call_float(env, halation, "getBoostRange");
            out->halation_protect_ev = call_float(env, halation, "getProtectEv");
            read_triple_f(env, halation, "getHalationStrength", out->halation_strength);
            read_triple_f(env, halation, "getHalationFirstSigmaUm", out->halation_first_sigma_um);
            out->halation_n_bounces = call_int(env, halation, "getHalationNBounces");
            out->halation_bounce_decay = call_float(env, halation, "getHalationBounceDecay");
            out->halation_renormalize = call_bool(env, halation, "getHalationRenormalize") ? 1 : 0;
            env->DeleteLocalRef(halation);
        }
        if (dir) {
            out->dir_couplers_active = call_bool(env, dir, "getActive") ? 1 : 0;
            out->dir_amount = call_float(env, dir, "getAmount");
            out->dir_inhibition_samelayer = call_float(env, dir, "getInhibitionSamelayer");
            out->dir_inhibition_interlayer = call_float(env, dir, "getInhibitionInterlayer");
            read_triple_f(env, dir, "getGammaSamelayerRgb", out->dir_gamma_samelayer_rgb);
            read_pair_f(env, dir, "getGammaInterlayerRToGb", out->dir_gamma_interlayer_r_to_gb);
            read_pair_f(env, dir, "getGammaInterlayerGToRb", out->dir_gamma_interlayer_g_to_rb);
            read_pair_f(env, dir, "getGammaInterlayerBToRg", out->dir_gamma_interlayer_b_to_rg);
            out->dir_diffusion_size_um = call_float(env, dir, "getDiffusionSizeUm");
            out->dir_diffusion_tail_um = call_float(env, dir, "getDiffusionTailUm");
            out->dir_diffusion_tail_weight = call_float(env, dir, "getDiffusionTailWeight");
            env->DeleteLocalRef(dir);
        }
        if (glare) {
            out->glare_active = call_bool(env, glare, "getActive") ? 1 : 0;
            out->glare_percent = call_float(env, glare, "getPercent");
            out->glare_roughness = call_float(env, glare, "getRoughness");
            out->glare_blur = call_float(env, glare, "getBlur");
            env->DeleteLocalRef(glare);
        }
    }

    // ---- print rendering ----
    if (printR) {
        out->print_density_curve_gamma = call_float(env, printR, "getDensityCurveGamma");
        jobject pglare = call_obj(env, printR, "getGlare",
            "()Lcom/spectrafilm/engine/GlareParams;");
        if (pglare) {
            out->print_glare_active = call_bool(env, pglare, "getActive") ? 1 : 0;
            out->print_glare_percent = call_float(env, pglare, "getPercent");
            out->print_glare_roughness = call_float(env, pglare, "getRoughness");
            out->print_glare_blur = call_float(env, pglare, "getBlur");
            env->DeleteLocalRef(pglare);
        }
    }

    // ---- io ----
    if (io) {
        out->scan_film = call_bool(env, io, "getScanFilm") ? 1 : 0;
        out->output_cctf_encoding = call_bool(env, io, "getOutputCctfEncoding") ? 1 : 0;
        out->input_cctf_decoding = call_bool(env, io, "getInputCctfDecoding") ? 1 : 0;
        out->crop = call_bool(env, io, "getCrop") ? 1 : 0;
        out->upscale_factor = call_float(env, io, "getUpscaleFactor");
        read_pair_f(env, io, "getCropCenter", out->crop_center);
        read_pair_f(env, io, "getCropSize", out->crop_size);
        jobject ocs = call_obj(env, io, "getOutputColorSpace",
            "()Lcom/spectrafilm/engine/ColorSpace;");
        out->output_color_space = enum_ordinal_cs(env, ocs);
        if (ocs) env->DeleteLocalRef(ocs);
    }

    // ---- settings ----
    if (settings) {
        jobject m = call_obj(env, settings, "getRgbToRawMethod",
            "()Lcom/spectrafilm/engine/Rgb2Raw;");
        out->rgb_to_raw_method = enum_ordinal_rgb2raw(env, m);
        if (m) env->DeleteLocalRef(m);
        out->apply_hanatos_window =
            call_bool(env, settings, "getApplyHanatos2025AdaptationWindow") ? 1 : 0;
        out->apply_hanatos_surface =
            call_bool(env, settings, "getApplyHanatos2025AdaptationSurface") ? 1 : 0;
        out->spectral_gaussian_blur = call_float(env, settings, "getSpectralGaussianBlur");
        out->use_enlarger_lut = call_bool(env, settings, "getUseEnlargerLut") ? 1 : 0;
        out->use_scanner_lut = call_bool(env, settings, "getUseScannerLut") ? 1 : 0;
        out->lut_resolution = call_int(env, settings, "getLutResolution");
        out->preview_max_size = call_int(env, settings, "getPreviewMaxSize");
        out->neutral_print_filters_from_database =
            call_bool(env, settings, "getNeutralPrintFiltersFromDatabase") ? 1 : 0;
    }

    if (camera) env->DeleteLocalRef(camera);
    if (enlarger) env->DeleteLocalRef(enlarger);
    if (scanner) env->DeleteLocalRef(scanner);
    if (filmR) env->DeleteLocalRef(filmR);
    if (printR) env->DeleteLocalRef(printR);
    if (io) env->DeleteLocalRef(io);
    if (settings) env->DeleteLocalRef(settings);
    return true;
}

}  // namespace

JNI(jlong, nativeCreate)(JNIEnv* env, jobject /*thiz*/, jstring assetDir) {
    std::string dir = jstr(env, assetDir);
    if (dir.empty()) return 0;  // AAssetManager path not yet wired; require an extracted dir.
    spk_engine* eng = nullptr;
    if (spk_engine_create(dir.c_str(), &eng) != SPK_OK) return 0;
    return reinterpret_cast<jlong>(eng);
}

JNI(void, nativeDestroy)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    spk_engine_destroy(reinterpret_cast<spk_engine*>(handle));
}

JNI(jstring, nativeListProfiles)(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    spk_engine* eng = reinterpret_cast<spk_engine*>(handle);
    if (!eng) return env->NewStringUTF("");
    size_t needed = 0;
    spk_engine_list_profiles(eng, nullptr, 0, &needed);
    if (needed == 0) return env->NewStringUTF("");
    std::vector<char> buf(needed);
    if (spk_engine_list_profiles(eng, buf.data(), buf.size(), &needed) != SPK_OK)
        return env->NewStringUTF("");
    return env->NewStringUTF(buf.data());
}

/*
 * nativeSimulate(handle, inBuf, w, h, inCs, paramsObj, preview) -> SimResult.
 * Reads a direct float ByteBuffer of w*h*3 floats, marshals the params, runs
 * spk_simulate(_preview), and wraps the result into a SimResult with a fresh
 * direct float ByteBuffer.
 */
JNI(jobject, nativeSimulate)(JNIEnv* env, jobject /*thiz*/, jlong handle,
                             jobject inBuf, jint w, jint h, jstring /*inCs*/,
                             jobject paramsObj, jboolean preview) {
    spk_engine* eng = reinterpret_cast<spk_engine*>(handle);
    if (!eng || !inBuf) return nullptr;

    float* in_data = static_cast<float*>(env->GetDirectBufferAddress(inBuf));
    if (!in_data) return nullptr;

    spk_params params;
    ParamStorage store;
    if (!marshal_params(env, paramsObj, &params, &store)) return nullptr;

    spk_image in_img{in_data, w, h, static_cast<int>(SPK_CS_PROPHOTO)};
    spk_image out{};
    spk_status st = preview ? spk_simulate_preview(eng, &in_img, &params, &out)
                            : spk_simulate(eng, &in_img, &params, &out);
    if (st != SPK_OK || !out.data) return nullptr;

    // Wrap the output into a Java-managed direct ByteBuffer (allocateDirect), copy
    // the engine buffer into it, then free the C-side allocation. This keeps memory
    // ownership on the JVM heap so the GC reclaims it.
    const size_t n = static_cast<size_t>(out.width) * out.height * 3;
    jclass bbCls = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocDirect = env->GetStaticMethodID(bbCls, "allocateDirect",
                                                   "(I)Ljava/nio/ByteBuffer;");
    jobject outBuf = env->CallStaticObjectMethod(bbCls, allocDirect,
                                                 static_cast<jint>(n * sizeof(float)));
    env->DeleteLocalRef(bbCls);
    if (!outBuf) { spk_image_free(&out); return nullptr; }
    void* dst = env->GetDirectBufferAddress(outBuf);
    std::memcpy(dst, out.data, n * sizeof(float));

    int out_w = out.width, out_h = out.height, out_cs = out.color_space;
    spk_image_free(&out);

    // Build the SimResult(data: ByteBuffer, width: Int, height: Int, colorSpace: ColorSpace).
    jclass csCls = env->FindClass("com/spectrafilm/engine/ColorSpace");
    jmethodID csValues = env->GetStaticMethodID(csCls, "values",
        "()[Lcom/spectrafilm/engine/ColorSpace;");
    jobjectArray csArr = static_cast<jobjectArray>(
        env->CallStaticObjectMethod(csCls, csValues));
    int ord = out_cs;
    int len = csArr ? env->GetArrayLength(csArr) : 0;
    if (ord < 0 || ord >= len) ord = 0;
    jobject csObj = csArr ? env->GetObjectArrayElement(csArr, ord) : nullptr;

    jclass resCls = env->FindClass("com/spectrafilm/engine/SimResult");
    jmethodID resCtor = env->GetMethodID(resCls, "<init>",
        "(Ljava/nio/ByteBuffer;IILcom/spectrafilm/engine/ColorSpace;)V");
    jobject result = env->NewObject(resCls, resCtor, outBuf, out_w, out_h, csObj);

    if (csArr) env->DeleteLocalRef(csArr);
    if (csObj) env->DeleteLocalRef(csObj);
    env->DeleteLocalRef(csCls);
    env->DeleteLocalRef(resCls);
    env->DeleteLocalRef(outBuf);
    return result;
}

/*
 * nativeBakeCubeLut(handle, paramsObj, size) -> String (.cube text) or null.
 * Marshals the params (reusing marshal_params), bakes a size^3 3D LUT of the
 * current film look, and returns the .cube text. The bake forces all spatial /
 * stochastic effects off (see spk_bake_cube_lut). Sized in two passes: first to
 * learn the required buffer length, then to fill it.
 */
JNI(jstring, nativeBakeCubeLut)(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                jobject paramsObj, jint size) {
    spk_engine* eng = reinterpret_cast<spk_engine*>(handle);
    if (!eng) return nullptr;

    spk_params params;
    ParamStorage store;
    if (!marshal_params(env, paramsObj, &params, &store)) return nullptr;

    size_t needed = 0;
    spk_status st = spk_bake_cube_lut(eng, &params, size, nullptr, 0, &needed);
    // The sizing pass returns SPK_ERR_BAD_ARGS (null buffer) but still sets
    // `needed`; any other failure (bad profile, internal) is fatal.
    if (needed == 0) return nullptr;

    std::vector<char> buf(needed);
    st = spk_bake_cube_lut(eng, &params, size, buf.data(), buf.size(), &needed);
    if (st != SPK_OK) return nullptr;
    return env->NewStringUTF(buf.data());
}
