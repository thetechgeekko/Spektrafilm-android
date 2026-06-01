/*
 * Spektrafilm for Android — LiteRT (TFLite) ML segmentation. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * OPT-IN, OFF BY DEFAULT (CMake `SPK_ENABLE_LITERT`). Hook for on-device ML masking
 * (subject / sky), mirroring Lightroom's LiteRT-backed Select Subject / Select Sky
 * (see docs/IMPROVEMENT_BACKLOG.md §A). This is a *feature* surface (local masking),
 * not part of the bit-exact render path, so it never affects the parity gate.
 *
 * When SPK_ENABLE_LITERT is defined the implementation runs a TFLite model via the
 * LiteRT C API (optionally GPU-delegated); otherwise it compiles to a stub that
 * reports unavailable. Shipping it ON additionally requires bundling a segmentation
 * model and linking libtensorflowlite_c (tracked; not vendored here).
 */
#ifndef SPK_ML_SEGMENTATION_H
#define SPK_ML_SEGMENTATION_H

#include <cstdint>
#include <vector>

namespace spk::ml {

enum class MaskKind { kSubject, kSky };

// True when SPK_ENABLE_LITERT is compiled in AND a model + runtime are available.
bool available();

// Run segmentation on an interleaved RGB float image, producing a [0,1] soft mask
// (length width*height). Returns false (and leaves `mask` untouched) when the ML path
// is unavailable — callers then fall back to manual masks. Never throws.
bool segment(const float* rgb, int width, int height, MaskKind kind,
             std::vector<float>* mask);

}  // namespace spk::ml

#endif  // SPK_ML_SEGMENTATION_H
