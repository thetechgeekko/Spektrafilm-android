/*
 * Spektrafilm for Android — LiteRT (TFLite) ML segmentation implementation. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Default build: stub (SPK_ENABLE_LITERT undefined) — available() == false, so the app
 * uses manual masks and nothing links TensorFlow Lite. The real path (SPK_ENABLE_LITERT)
 * loads a bundled .tflite segmentation model via the LiteRT C API and runs it
 * (optionally GPU-delegated); it is gated behind the flag because it needs the model
 * asset + libtensorflowlite_c, which are not vendored in this module yet.
 */
#include "ml/segmentation.h"

namespace spk::ml {

#ifndef SPK_ENABLE_LITERT

bool available() { return false; }

bool segment(const float*, int, int, MaskKind, std::vector<float>*) { return false; }

#else

// Real LiteRT path. Requires <tensorflow/lite/c/c_api.h> + a bundled model; wired when
// SPK_ENABLE_LITERT is enabled in CMake (links libtensorflowlite_c). The interpreter +
// model handle live in a process-wide lazily-initialised holder (see the header note).
// Implementation intentionally elided until the model asset + lib are vendored; the
// signature + contract above are the integration point the feature layer calls.
#error "SPK_ENABLE_LITERT requires the LiteRT runtime + model to be vendored (see ml/segmentation.h)."

#endif  // SPK_ENABLE_LITERT

}  // namespace spk::ml
