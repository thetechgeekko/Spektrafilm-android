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

bool marshal_params(JNIEnv* env, jobject params, spk_params* out, ParamStorage* store) {
    std::memset(out, 0, sizeof(*out));
    if (!params) return false;

    // Top-level: filmProfile / printProfile (String), and the nested objects.
    store->film_profile = jstr(env,
        static_cast<jstring>(call_obj(env, params, "getFilmProfile",
                                      "()Ljava/lang/String;")));
    store->print_profile = jstr(env,
        static_cast<jstring>(call_obj(env, params, "getPrintProfile",
                                      "()Ljava/lang/String;")));
    out->film_profile = store->film_profile.c_str();
    out->print_profile = store->print_profile.c_str();

    jobject camera  = call_obj(env, params, "getCamera",
        "()Lcom/spectrafilm/engine/CameraParams;");
    jobject filmR   = call_obj(env, params, "getFilmRender",
        "()Lcom/spectrafilm/engine/FilmRenderingParams;");
    jobject io      = call_obj(env, params, "getIo",
        "()Lcom/spectrafilm/engine/IoParams;");
    jobject settings = call_obj(env, params, "getSettings",
        "()Lcom/spectrafilm/engine/SettingsParams;");

    // camera
    if (camera) {
        out->exposure_compensation_ev = call_float(env, camera, "getExposureCompensationEv");
        out->auto_exposure = call_bool(env, camera, "getAutoExposure") ? 1 : 0;
        out->lens_blur_um = call_float(env, camera, "getLensBlurUm");
        out->film_format_mm = call_float(env, camera, "getFilmFormatMm");
    }

    // film rendering
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
        out->grain_active        = grain    && call_bool(env, grain, "getActive")    ? 1 : 0;
        out->halation_active     = halation && call_bool(env, halation, "getActive") ? 1 : 0;
        out->dir_couplers_active = dir      && call_bool(env, dir, "getActive")      ? 1 : 0;
        out->glare_active        = glare    && call_bool(env, glare, "getActive")    ? 1 : 0;
        if (grain) env->DeleteLocalRef(grain);
        if (halation) env->DeleteLocalRef(halation);
        if (dir) env->DeleteLocalRef(dir);
        if (glare) env->DeleteLocalRef(glare);
    } else {
        out->density_curve_gamma = 1.0f;
    }

    // io
    if (io) {
        out->scan_film = call_bool(env, io, "getScanFilm") ? 1 : 0;
        out->output_cctf_encoding = call_bool(env, io, "getOutputCctfEncoding") ? 1 : 0;
        jobject ocs = call_obj(env, io, "getOutputColorSpace",
            "()Lcom/spectrafilm/engine/ColorSpace;");
        out->output_color_space = enum_ordinal_cs(env, ocs);
        if (ocs) env->DeleteLocalRef(ocs);
    } else {
        out->output_cctf_encoding = 1;
        out->output_color_space = SPK_CS_SRGB;
    }

    // settings
    if (settings) {
        jobject m = call_obj(env, settings, "getRgbToRawMethod",
            "()Lcom/spectrafilm/engine/Rgb2Raw;");
        out->rgb_to_raw_method = enum_ordinal_rgb2raw(env, m);
        if (m) env->DeleteLocalRef(m);
        out->preview_max_size = call_int(env, settings, "getPreviewMaxSize");
    } else {
        out->preview_max_size = 640;
    }

    if (camera) env->DeleteLocalRef(camera);
    if (filmR) env->DeleteLocalRef(filmR);
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
