/*
 * Spektrafilm for Android — GPU (Vulkan compute) fast-path. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * OPT-IN, OFF BY DEFAULT (CMake `SPK_ENABLE_VULKAN`, default OFF). This is the
 * foundation of the GPU offload described in docs/PERF_ROADMAP.md (#1, the real
 * Lightroom-class lever). It runs a per-element engine op on the GPU via a Vulkan
 * compute shader. Under the adopted proxy-approximate / export-exact policy it is a
 * PREVIEW-only acceleration: GPU float math is not bit-identical to the CPU/oracle
 * path, so the export + parity-gated path never call this.
 *
 * Build: when SPK_ENABLE_VULKAN is defined the engine links libvulkan; otherwise this
 * whole module is compiled out and the library is byte-identical to today. The CPU
 * fallback (spk::gpu::available() == false) is always correct.
 */
#ifndef SPK_GPU_VULKAN_COMPUTE_H
#define SPK_GPU_VULKAN_COMPUTE_H

#include <cstddef>

namespace spk::gpu {

// True when SPK_ENABLE_VULKAN is compiled in AND a usable Vulkan device + compute
// queue were found at runtime. When false, callers must use the CPU path.
bool available();

// Apply the sRGB CCTF encode + clip to [0,1] to `data` (length `n` interleaved float
// RGB components) on the GPU, in place. Returns false if the GPU path is unavailable
// or any Vulkan call failed (caller then falls back to the CPU path). Never throws.
bool cctf_encode_srgb(float* data, size_t n);

}  // namespace spk::gpu

#endif  // SPK_GPU_VULKAN_COMPUTE_H
