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
#include <cstdint>

namespace spk::gpu {

// True when SPK_ENABLE_VULKAN is compiled in AND a usable Vulkan device + compute
// queue were found at runtime. When false, callers must use the CPU path.
bool available();

// Apply the sRGB CCTF encode + clip to [0,1] to `data` (length `n` interleaved float
// RGB components) on the GPU, in place. Returns false if the GPU path is unavailable
// or any Vulkan call failed (caller then falls back to the CPU path). Never throws.
bool cctf_encode_srgb(float* data, size_t n);

// GPU 81-band spectral SCAN integral (the bottleneck-class kernel, preview-only):
// density_cmy[npix*3] -> output RGB[npix*3], via per-pixel spectral transmittance
// (10^-D over 81 bands) -> XYZ -> output RGB + sRGB CCTF. Spectral tables:
//   dye     : NB*3 per-channel dye densities D_c(lambda)  (band-major c,m,y)
//   icmf    : NB*3 illuminant-premultiplied CMFs          (band-major X,Y,Z)
//   xyz2rgb : 9 floats, row-major 3x3 XYZ->output-RGB matrix
// Returns false if the GPU path is unavailable or any Vulkan call failed (caller
// falls back to the CPU scan). NOT bit-exact vs the f64 oracle -> preview only; the
// export + parity-gated path never call this. On-GPU numeric validation vs the CPU
// reference is pending arm64 GPU hardware (docs/PERF_ROADMAP.md #1).
bool scan_spectral(const float* cmy, float* rgb, uint32_t npix,
                   const float* dye, const float* icmf, const float* xyz2rgb);

}  // namespace spk::gpu

#endif  // SPK_GPU_VULKAN_COMPUTE_H
