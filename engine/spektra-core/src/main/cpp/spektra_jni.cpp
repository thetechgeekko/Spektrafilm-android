/*
 * SpectraFilm for Android — JNI bridge (M0 stub).
 * GPLv3. Bridges com.spectrafilm.engine.SpektraEngine (Kotlin) to the spektra C API.
 *
 * Design notes:
 *  - Image buffers cross the boundary as direct java.nio.ByteBuffer (float32 RGB) to
 *    avoid per-pixel JNI traffic; only width/height/colorspace are passed as ints.
 *  - The native engine handle is stored as a Kotlin Long and passed back in.
 *  - The full param set is read out of a SpektraParams via cached field IDs; M0 wires
 *    the high-traffic fields declared in spektra.h's spk_params.
 */
#include <jni.h>
#include "spektra.h"

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL \
    Java_com_spectrafilm_engine_SpektraEngine_##name

JNI(jlong, nativeCreate)(JNIEnv* env, jobject /*thiz*/, jstring /*assetDir*/) {
    // TODO(M1): resolve asset dir / AAssetManager, call spk_engine_create.
    return 0; // 0 == not yet created
}

JNI(void, nativeDestroy)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    spk_engine_destroy(reinterpret_cast<spk_engine*>(handle));
}

JNI(jstring, nativeListProfiles)(JNIEnv* env, jobject /*thiz*/, jlong /*handle*/) {
    // TODO(M3): call spk_engine_list_profiles into a buffer, return as String.
    return env->NewStringUTF("");
}

/*
 * nativeSimulate(handle, inBuf, w, h, inCs, paramsObj, preview) -> outBuf-backed result.
 * M0 stub returns null; M3+ marshals spk_params from paramsObj, runs spk_simulate(_preview),
 * and wraps the output buffer back into a Kotlin result object.
 */
JNI(jobject, nativeSimulate)(JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/,
                             jobject /*inBuf*/, jint /*w*/, jint /*h*/, jint /*inCs*/,
                             jobject /*paramsObj*/, jboolean /*preview*/) {
    return nullptr;
}
