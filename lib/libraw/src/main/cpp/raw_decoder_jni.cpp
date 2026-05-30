/*
 * Spektrafilm for Android — lib:libraw JNI bridge.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Uses LibRaw (LGPL-2.1).
 *
 * Bridges com.spectrafilm.libraw.RawDecoder (Kotlin) to the native decoder.
 *
 * Design notes (mirrors engine:spektra-core's spektra_jni.cpp):
 *  - The decoded image crosses back to Kotlin as a *direct* java.nio.ByteBuffer of
 *    float32 RGB (length = width*height*3*4 bytes) so it can be handed straight to
 *    SpektraEngine.LinearImage with no per-pixel JNI traffic and no 8-bit round-trip.
 *  - Width / height / colorSpace are returned via a small Kotlin result holder
 *    (RawDecoder.NativeResult) constructed here through cached method/field IDs.
 *  - Input arrives as either a byte[] (SAF stream read fully) or a raw fd
 *    (ParcelFileDescriptor.detachFd()).
 */
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <vector>

#include "raw_decoder.h"

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL \
    Java_com_spectrafilm_libraw_RawDecoder_##name

namespace {

spectrafilm::DecodeOptions readOptions(jint wbMode, jdouble temperatureK, jdouble tint,
                                       jboolean halfSize) {
    spectrafilm::DecodeOptions opts;
    // Must match RawDecoder.WhiteBalance.nativeMode ordinals in Kotlin.
    switch (wbMode) {
        case 0:  opts.whiteBalance = spectrafilm::WhiteBalanceMode::AsShot;   break;
        case 1:  opts.whiteBalance = spectrafilm::WhiteBalanceMode::Daylight; break;
        case 2:  opts.whiteBalance = spectrafilm::WhiteBalanceMode::Tungsten; break;
        case 3:  opts.whiteBalance = spectrafilm::WhiteBalanceMode::Custom;   break;
        default: opts.whiteBalance = spectrafilm::WhiteBalanceMode::AsShot;   break;
    }
    opts.temperatureK = temperatureK;
    opts.tint = tint;
    // halfSize: when true, LibRaw decodes at half linear dimensions (quarter
    // pixels) using 2x2 Bayer averaging instead of full demosaic. Intended for
    // fast, low-memory proxy decodes of large RAW/DNG files.
    opts.halfSize = (halfSize != JNI_FALSE);
    return opts;
}

// Build a com.spectrafilm.libraw.RawDecoder$NativeResult from a DecodeResult.
// On failure returns null and the Kotlin facade throws with `error`.
// Throw com.spectrafilm.libraw.RawDecodeException(message, status, librawCode)
// so the Kotlin side can branch on the failure kind (e.g. a lossy-JPEG Expert
// RAW DNG -> platform ImageDecoder fallback). Falls back to
// IllegalStateException if the exception class is unavailable.
void throwDecodeException(JNIEnv* env, const spectrafilm::DecodeResult& r) {
    const char* msg = r.error.empty() ? "RAW decode failed" : r.error.c_str();
    jclass exClass = env->FindClass("com/spectrafilm/libraw/RawDecodeException");
    if (exClass != nullptr) {
        jmethodID ctor =
            env->GetMethodID(exClass, "<init>", "(Ljava/lang/String;II)V");
        if (ctor != nullptr) {
            jstring jmsg = env->NewStringUTF(msg);
            jobject ex = env->NewObject(exClass, ctor, jmsg,
                                        static_cast<jint>(r.status),
                                        static_cast<jint>(r.librawCode));
            if (ex != nullptr) {
                env->Throw(static_cast<jthrowable>(ex));
                return;
            }
        }
        env->ExceptionClear();
        env->ThrowNew(exClass, msg);  // (String) fallback
        return;
    }
    env->ExceptionClear();
    jclass ise = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(ise, msg);
}

jobject toJavaResult(JNIEnv* env, const spectrafilm::DecodeResult& r) {
    if (!r.ok) {
        throwDecodeException(env, r);
        return nullptr;
    }

    const jlong byteLen =
        static_cast<jlong>(r.rgb.size()) * static_cast<jlong>(sizeof(float));
    // Guard against >2 GiB: ByteBuffer.allocateDirect takes a jint, so a larger
    // length would truncate (and the full 64-bit memcpy below would then overflow
    // the undersized buffer). Reject before allocating. (Security review F1.)
    if (byteLen > static_cast<jlong>(INT32_MAX)) {
        jclass oom = env->FindClass("java/lang/OutOfMemoryError");
        env->ThrowNew(oom, "RAW decode result too large for a direct ByteBuffer (>2 GiB)");
        return nullptr;
    }
    // The decoded RGB lives in r.rgb (freed when this call returns), so we hand
    // Kotlin a direct ByteBuffer it *owns* (allocateDirect) and memcpy into it.
    // The buffer is direct so the engine can consume it as a LinearImage with no
    // further copy.
    jclass bbClass = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocDirect =
        env->GetStaticMethodID(bbClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    jobject owned = env->CallStaticObjectMethod(
        bbClass, allocDirect, static_cast<jint>(byteLen));
    if (owned == nullptr) {
        jclass oom = env->FindClass("java/lang/OutOfMemoryError");
        env->ThrowNew(oom, "failed to allocate direct buffer for RAW result");
        return nullptr;
    }
    void* dst = env->GetDirectBufferAddress(owned);
    if (dst != nullptr) {
        std::memcpy(dst, r.rgb.data(), static_cast<size_t>(byteLen));
    }

    jclass resClass = env->FindClass("com/spectrafilm/libraw/RawDecoder$NativeResult");
    jmethodID ctor = env->GetMethodID(
        resClass, "<init>",
        "(Ljava/nio/ByteBuffer;IILjava/lang/String;)V");
    jstring cs = env->NewStringUTF(r.colorSpace.c_str());
    return env->NewObject(
        resClass, ctor, owned, static_cast<jint>(r.width),
        static_cast<jint>(r.height), cs);
}

}  // namespace

/*
 * nativeDecodeBytes(bytes, wbMode, temperatureK, tint, halfSize) -> NativeResult
 * Decodes a fully-read RAW/DNG byte[] into linear float32 ACES RGB.
 * halfSize: if JNI_TRUE, LibRaw decodes at half linear dimensions (quarter pixels,
 * ~¼ memory) using 2x2 Bayer averaging instead of full demosaic. Default false.
 */
JNI(jobject, nativeDecodeBytes)(JNIEnv* env, jobject /*thiz*/, jbyteArray bytes,
                                jint wbMode, jdouble temperatureK, jdouble tint,
                                jboolean halfSize) {
    if (bytes == nullptr) {
        jclass ise = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(ise, "null RAW byte[]");
        return nullptr;
    }
    const jsize len = env->GetArrayLength(bytes);
    jbyte* ptr = env->GetByteArrayElements(bytes, nullptr);
    spectrafilm::DecodeResult result = spectrafilm::decodeFromBuffer(
        reinterpret_cast<const uint8_t*>(ptr), static_cast<size_t>(len),
        readOptions(wbMode, temperatureK, tint, halfSize));
    env->ReleaseByteArrayElements(bytes, ptr, JNI_ABORT);
    return toJavaResult(env, result);
}

/*
 * nativeDecodeBuffer(directBuf, len, wbMode, temperatureK, tint, halfSize) -> NativeResult
 * Same as above but reads directly from a direct ByteBuffer (zero input copy).
 * halfSize: if JNI_TRUE, decode at half linear dimensions (proxy mode).
 */
JNI(jobject, nativeDecodeBuffer)(JNIEnv* env, jobject /*thiz*/, jobject directBuf,
                                 jint len, jint wbMode, jdouble temperatureK, jdouble tint,
                                 jboolean halfSize) {
    void* addr = (directBuf != nullptr) ? env->GetDirectBufferAddress(directBuf) : nullptr;
    if (addr == nullptr) {
        jclass ise = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(ise, "expected a direct ByteBuffer for RAW input");
        return nullptr;
    }
    spectrafilm::DecodeResult result = spectrafilm::decodeFromBuffer(
        reinterpret_cast<const uint8_t*>(addr), static_cast<size_t>(len),
        readOptions(wbMode, temperatureK, tint, halfSize));
    return toJavaResult(env, result);
}

/*
 * nativeDecodeFd(fd, wbMode, temperatureK, tint, halfSize) -> NativeResult
 * Decodes from a file descriptor (e.g. ParcelFileDescriptor.detachFd()).
 * halfSize: if JNI_TRUE, decode at half linear dimensions (proxy mode).
 */
JNI(jobject, nativeDecodeFd)(JNIEnv* env, jobject /*thiz*/, jint fd,
                             jint wbMode, jdouble temperatureK, jdouble tint,
                             jboolean halfSize) {
    spectrafilm::DecodeResult result = spectrafilm::decodeFromFd(
        fd, readOptions(wbMode, temperatureK, tint, halfSize));
    return toJavaResult(env, result);
}
