/*
 * SpectraFilm for Android — lib:pngwriter JNI bridge.
 * Copyright (C) 2026 SpectraFilm Android contributors. GPLv3.
 *
 * Bridges com.spectrafilm.pngwriter.PngWriter (Kotlin) to the native 16-bit PNG
 * writer (libsfpng.so). Mirrors the lib:tiffwriter bridge conventions:
 *
 *   - Pixel data crosses from Kotlin as a *direct* java.nio.ByteBuffer of 16-bit
 *     RGB samples (length = width*height*3*2 bytes), little-endian, so the engine's
 *     display-referred output can be quantised once and handed over with no
 *     per-pixel JNI traffic. A ShortArray overload is also provided.
 *   - Optional ICC bytes arrive as a byte[] (null/empty => no iCCP chunk).
 *   - On failure the native side throws IllegalStateException with the writer's
 *     error string; on success it returns the number of bytes written.
 */
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "png_writer.h"

#define JNI_PNG(ret, name) extern "C" JNIEXPORT ret JNICALL \
    Java_com_spectrafilm_pngwriter_PngWriter_##name

namespace {

void throwIse(JNIEnv* env, const std::string& msg) {
    jclass ise = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(ise, msg.c_str());
}

// Read a (possibly null) jbyteArray into meta.iccProfile.
void readIcc(JNIEnv* env, jbyteArray icc, spectrafilm::PngMetadata& meta) {
    if (icc == nullptr) return;
    const jsize n = env->GetArrayLength(icc);
    if (n <= 0) return;
    meta.iccProfile.resize(static_cast<size_t>(n));
    env->GetByteArrayRegion(icc, 0, n,
                            reinterpret_cast<jbyte*>(meta.iccProfile.data()));
}

spectrafilm::PngMetadata buildMeta(JNIEnv* env, jstring software, jbyteArray icc) {
    spectrafilm::PngMetadata meta;
    if (software != nullptr) {
        const char* s = env->GetStringUTFChars(software, nullptr);
        if (s) { meta.software = s; env->ReleaseStringUTFChars(software, s); }
    }
    readIcc(env, icc, meta);
    return meta;
}

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

/*
 * nativeWriteBuffer(directBuf, width, height, software, iccBytes, outPath)
 *   -> long bytesWritten
 *
 * `directBuf` is a direct ByteBuffer of width*height*3 little-endian uint16 RGB
 * samples (length width*height*3*2 bytes). Writes a 16-bit PNG to `outPath`.
 * Throws IllegalStateException on any failure.
 */
JNI_PNG(jlong, nativeWriteBuffer)(JNIEnv* env, jobject /*thiz*/, jobject directBuf,
                                   jint width, jint height,
                                   jstring software, jbyteArray iccBytes,
                                   jstring outPath) {
    void* addr = (directBuf != nullptr)
                 ? env->GetDirectBufferAddress(directBuf)
                 : nullptr;
    if (addr == nullptr) {
        jclass iae = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(iae, "expected a direct ByteBuffer of 16-bit RGB samples");
        return 0;
    }
    spectrafilm::PngMetadata meta = buildMeta(env, software, iccBytes);
    spectrafilm::PngWriteResult r = spectrafilm::writePng16ToFile(
        reinterpret_cast<const uint16_t*>(addr), width, height, meta,
        jstr(env, outPath));
    if (!r.ok) {
        throwIse(env, r.error.empty() ? "PNG write failed" : r.error);
        return 0;
    }
    return static_cast<jlong>(r.bytesWritten);
}

/*
 * nativeWriteShorts(short[] rgb16, width, height, software, iccBytes, outPath)
 *   -> long bytesWritten
 *
 * Same as nativeWriteBuffer but the pixel data is a Java short[] of
 * width*height*3 samples (interpreted as unsigned 16-bit / big-endian in the
 * output PNG; the writer handles the byte-swap).
 */
JNI_PNG(jlong, nativeWriteShorts)(JNIEnv* env, jobject /*thiz*/, jshortArray rgb16,
                                   jint width, jint height,
                                   jstring software, jbyteArray iccBytes,
                                   jstring outPath) {
    if (rgb16 == nullptr) {
        jclass iae = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(iae, "null short[] pixel buffer");
        return 0;
    }
    const jsize n = env->GetArrayLength(rgb16);
    const jlong need = static_cast<jlong>(width) * static_cast<jlong>(height) * 3;
    if (need <= 0 || n < need) {
        jclass iae = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(iae, "short[] too small for width*height*3 RGB samples");
        return 0;
    }
    jshort* ptr = env->GetShortArrayElements(rgb16, nullptr);
    // jshort is signed 16-bit; reinterpret bit-for-bit as uint16 (same bytes).
    spectrafilm::PngMetadata meta = buildMeta(env, software, iccBytes);
    spectrafilm::PngWriteResult r = spectrafilm::writePng16ToFile(
        reinterpret_cast<const uint16_t*>(ptr), width, height, meta,
        jstr(env, outPath));
    env->ReleaseShortArrayElements(rgb16, ptr, JNI_ABORT);
    if (!r.ok) {
        throwIse(env, r.error.empty() ? "PNG write failed" : r.error);
        return 0;
    }
    return static_cast<jlong>(r.bytesWritten);
}
