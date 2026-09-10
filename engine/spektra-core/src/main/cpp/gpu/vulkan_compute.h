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

// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
// Resident resource/DAG interface concepts adapted from
// chaert-s/spektrafilm-ofx src/SpektraVulkanRenderer.{h,cpp}, pinned at
// 86476afc5b077de77e2278e3658d1ba9309892a1. Android modifications replace
// OpenFX/desktop types with a bounded pointwise-only request and fail-closed
// diagnostics; no OFX shader math or binary resources cross this interface.

struct PointwiseTableSpan {
    const float* data = nullptr;
    size_t count = 0;
};

struct PointwiseFilmRequest {
    PointwiseTableSpan tc_lut{};          // tc_edge * tc_edge * 3
    uint32_t tc_edge = 0;
    PointwiseTableSpan develop_axis{};    // curve_points * 3
    PointwiseTableSpan develop_curve{};   // curve_points * 3
    PointwiseTableSpan dir_axis{};        // curve_points * 3
    PointwiseTableSpan dir_curve{};       // curve_points * 3
    uint32_t curve_points = 0;
    float exposure_multiplier = 1.0f;
    float coupler_shift = 0.0f;
    float coupler_matrix[9]{};
};

struct PointwisePrintRequest {
    PointwiseTableSpan dye{};                     // 81 * 3, band-major
    PointwiseTableSpan illuminant_sensitivity{};  // 81 * 3, band-major
    PointwiseTableSpan paper_axis{};              // curve_points * 3
    PointwiseTableSpan paper_curve{};             // curve_points * 3
    uint32_t curve_points = 0;
    float midgray = 1.0f;
    float exposure_multiplier = 1.0f;
    float preflash[3]{};
};

struct PointwiseScanRequest {
    PointwiseTableSpan dye{};             // 81 * 3, band-major
    PointwiseTableSpan illuminant_cmf{};  // 81 * 3, band-major
    float xyz_to_rgb[9]{};                // row-major 3x3
};

struct PointwiseDispatchGrid {
    uint32_t groups_x = 0;
    uint32_t groups_y = 0;
    uint32_t total_groups = 0;
    uint64_t group_row_stride_pixels = 0;
    bool valid = false;
};

// Pure, allocation-free planner shared by validation/tests and the Vulkan host.
// It decomposes a frame into one 2D vkCmdDispatch while preserving a flat pixel
// index. Device maxComputeWorkGroupCount[0..1] are passed in explicitly.
PointwiseDispatchGrid plan_pointwise_dispatch(uint32_t pixel_count,
                                              uint32_t max_groups_x,
                                              uint32_t max_groups_y) noexcept;

// One request is one whole-frame pointwise DAG operation. All allocation sizes
// and dispatch dimensions are checked against the selected Vulkan device limits.
struct PointwiseChainRequest {
    const float* input_rgb = nullptr;
    size_t input_component_count = 0;
    uint32_t pixel_count = 0;
    // Opaque nonzero generation owned by the engine's prepared-table cache (not
    // a truncated content digest). It must change whenever any table or shape
    // changes; the GPU cache additionally verifies the complete table layout.
    uint64_t static_table_key = 0;
    PointwiseFilmRequest film{};
    PointwisePrintRequest print{};
    PointwiseScanRequest scan{};
};

struct PointwiseChainOutput {
    float* rgb = nullptr;
    size_t component_capacity = 0;
};

enum class PointwiseFallbackReason : uint8_t {
    none = 0,
    vulkan_disabled,
    unavailable,
    invalid_request,
    request_too_large,
    allocation_failed,
    pipeline_failed,
    upload_failed,
    dispatch_failed,
    readback_failed,
};

struct PointwiseChainDiagnostics {
    bool engaged = false;
    // Frame-operation counters are completion counters: a failed call reports
    // zero even when command recording or submission had already been attempted.
    uint32_t dispatches = 0;
    uint32_t input_uploads = 0;
    uint32_t final_readbacks = 0;
    uint64_t interstage_host_bytes = 0;
    uint32_t pipeline_creates = 0;
    uint32_t buffer_allocations = 0;
    // Host-staged static bytes attempted by this call. Unlike the frame counters
    // above, this may remain nonzero if a later command submission/readback fails.
    uint64_t static_upload_bytes = 0;
    PointwiseFallbackReason fallback_reason = PointwiseFallbackReason::none;
};

// Pointwise filming -> printing -> scan in one Vulkan command buffer. On
// success, exactly three compute dispatches operate on shared device-local
// ping-pong buffers between one input upload and one final readback. Persistent
// pipelines, grow-only buffers, and keyed static f32 tables are reused on warm
// calls. Returns false without modifying the caller's output on any failure.
bool render_pointwise_chain(const PointwiseChainRequest& request,
                            PointwiseChainOutput* output,
                            PointwiseChainDiagnostics* diagnostics) noexcept;

const char* pointwise_fallback_reason_name(PointwiseFallbackReason reason) noexcept;

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
//   xyz2rgb : 9 floats, row-major 3x3 XYZ->output-RGB matrix (pass Mc.M composed
//             to mirror the engine's full linear chain — see the PR #145 probe)
// Returns false if the GPU path is unavailable or any Vulkan call failed (caller
// falls back to the CPU scan). NOT bit-exact vs the f64 oracle -> preview only;
// the export + parity-gated path never call this (the #149 law revision opens
// oracle-verified GPU export as future M4 work). MEASURED ON DEVICE (PR #145,
// docs/research/gpu-device-probe.md): worst-case max_abs 2.15e-06 / rms 7.07e-08
// vs the f64 chain — 46x/141x inside the oracle tolerance — and byte-identical
// across repeated dispatches (Adreno 840, driver 512.842.19). The host guards
// non-finite densities at upload (NaN/Inf -> 1e4f -> black), so shader NaN
// behaviour never decides pixels.
bool scan_spectral(const float* cmy, float* rgb, uint32_t npix,
                   const float* dye, const float* icmf, const float* xyz2rgb);

// LINEAR variant (GPU M1, #146): the same 81-band integral, but the output is
// UNCLIPPED linear output-space RGB — no CAT02 fold, no CCTF, no clamp — so the
// CPU plane ops (unsharp / lens blur / gamut compression) and the standard
// encode tail run on it unchanged. `xyz2rgb` is the frame's plain XYZ->RGB
// matrix (Mc stays in the CPU encode). Same tables, same fallback contract,
// same preview-only law as scan_spectral.
bool scan_spectral_linear(const float* cmy, float* rgb, uint32_t npix,
                          const float* dye, const float* icmf, const float* xyz2rgb);

// Per-channel 2D Gaussian blur of an interleaved f64 RGB frame, on the GPU.
//
// This is the engine's three remaining CPU blurs -- the camera lens blur
// (filming.expose), the scanner lens blur and the unsharp mask's blur term
// (scanning's spatial branch) -- all of which call
// gaussian_blur_per_channel_d(rgb, w, h, 3, sigma). It needs NO NEW SHADER:
// gpu/halation_scatter.comp in mixture mode is already a weighted set of
// Gaussians with a resolve of (1 - amount) * src + amount * blurred, so a blur
// is that pass with amount = 1 and unit weight, and halation switched off. The
// DIR-coupler diffusion (runtime/stages/filming.cpp) uses the same reuse.
//
// FAST GPU, and not a candidate for Strict Exact even though the model is
// identical arithmetic on paper. The kernel stores intermediates in f32, sweeps
// X-then-Y where the CPU sweeps vertical-then-horizontal, and carries the
// Young-van Vliet state as a double-float pair rather than f64. Those are
// tolerance-bounded differences, not absences -- but they are differences, so
// this must never ride a latch that promises the CPU's bytes.
//
// Sigmas are in PIXELS and are the host's to compute in f64. Equal sigmas cost
// one blur; unequal sigmas cost three, one per channel, because the underlying
// mixture carries one sigma per component. Returns false without touching
// `rgb` on any refusal, and the caller runs the CPU blur.
//
// FIR-CLASS SIGMAS ONLY (< 3.0). Above that the CPU switches to an IIR that is
// O(1) in sigma while this pass still pays whole-frame residency and a
// transpose, and the device says the GPU then LOSES: 0.62x at 12.5 MP, 0.91x at
// 1080p, against 1.59x and 1.46x below the threshold. Measured, not assumed --
// tools/gpu_probe/probe_spatial_main.cpp.
bool gaussian_blur_rgb(double* rgb, int width, int height,
                       const double sigma_px[3]);

// Viewing-glare field on the GPU (#215; shader gpu/glare.comp).
//
// model/glare.cpp::compute_random_glare_amount in one dispatch: the per-pixel
// lognormal draw, its Gaussian blur and the /100. No scratch plane, because the
// RNG is counter-based and a pixel regenerates its neighbours' draws rather than
// reading them (the same trick gpu/grain.comp uses for the dye-cloud blur).
//
// It produces the FIELD, not the composited image. add_glare's
// `xyz += field * illuminant` is fused into scanning.cpp's per-pixel f64 lambda
// and is not separable from it; the field build is the expensive half and is
// what STG_GLARE measures. It also means this pass uploads NOTHING -- the field
// comes from its seed -- and reads back one plane rather than three.
//
// FAST GPU. The draw is a different realisation from the CPU's mt19937 stream,
// so this may only ride a latch that admits a different realisation, exactly as
// the grain sampler does. It is never parity evidence.
//
// Refuses rather than approximates: a blur sigma at or above the CPU's FIR/IIR
// switch (3.0) cannot be expressed by a recompute loop at all, and a radius past
// kGlareMaxBlurRadius would need a truncated kernel, which is a different filter.
// The schema default blur is 0.5 px, i.e. radius 2 and 25 draws per pixel.
constexpr int kGlareMaxBlurRadius = 4;

struct GlareRequest {
    double amount = 0.0;        // glare_percent; the lognormal mean
    double roughness = 0.0;     // std = roughness * amount
    double blur_px = 0.0;
    uint32_t seed = 0;
};

struct GlareDiagnostics {
    bool attempted = false;
    bool engaged = false;
    const char* reason = "";
    uint32_t blur_radius = 0;
    double upload_ms = 0.0;
    double gpu_ms = 0.0;
    double readback_ms = 0.0;
};

// `field` is width*height floats, written only when the whole pass succeeded.
// On any refusal it is untouched and the caller runs the CPU path. Never throws.
bool glare_field(float* field, int width, int height, const GlareRequest& request,
                 GlareDiagnostics* diagnostics);

// AgX particle grain sampler on the GPU (#214; shader gpu/grain.comp).
//
// Ports the SAMPLER of model/grain.cpp::layer_particle_model -- the
// Poisson(lam) -> Binomial(seeds, p) draw that is 94.6% of the grain stage and
// the largest single item in a full-resolution export. The spatial parts of the
// stage (the per-particle dye-cloud blur, add_micro_structure, the final 0.65 px
// blur) stay on the host, as does the /n_sub and the -= density_min tail: those
// are frame-level parameters, and the kernel derives none of its own.
//
// FAST GPU, and an approximation with a MEASURED bound rather than a hoped-for
// one. The shader implements only the Normal-approximation branches of
// fast_poisson / fast_binomial, which is where the real workload already lives
// (tools/gpu_probe/census_grain_branches.cpp: 99.39% of samples at 1080p,
// 99.95% at preview, 90.05% at 12.5 MP export). Samples that fall outside those
// branches are COUNTED into diagnostics.out_of_branch, so the caller can apply a
// policy per frame instead of assuming the residue is small. The CPU sampler
// remains the reference and the fallback.
//
// The realisation differs from the CPU's by construction: the shader uses a
// counter-based RNG keyed on (seed_base, frame-wide pixel index, cell, frame
// offset) so the result is independent of dispatch order, workgroup size and
// slicing. That is the same class of change as fast_sampler (#180) -- same
// distributions, different draw -- and it is never parity evidence.
constexpr int kGrainMaxCells = 9;    // 3 sublayers x 3 channels
// Largest per-particle dye-cloud blur the shader will carry. The blur is
// evaluated by RECOMPUTING neighbours rather than storing a plane, so a cell of
// radius r costs (2r+1)^2 draws; 3 caps that at 49 and covers sigma up to
// (3 + 0.5)/3 = 1.167 px, well past the 0.173 px the coarsest cell reaches at
// 12.5 MP export pitch. Anything wider is refused to the CPU rather than
// truncated, because a truncated kernel is a different filter.
constexpr int kGrainMaxBlurRadius = 3;

// One (sublayer, channel) cell, every value computed on the host in f64.
struct GrainCell {
    double density_min = 0.0;              // added to the plane before p is formed
    double density_max = 1.0;
    double n_particles_per_pixel = 0.0;
    double uniformity = 0.0;
    int channel = 0;                       // 0..2: which output channel accumulates
    // seed_base[c] + sublayer*10, WITHOUT the per-frame offset. The offset is
    // passed separately and mixed in the shader: the CPU's scheme ADDS its three
    // terms, so an offset of 1 makes channel 1 collide with channel 2's previous
    // frame. Added seeds alias; mixed seeds do not.
    uint32_t seed_base = 0;
    // Per-particle dye-cloud blur for THIS cell, sigma in pixels
    // (blur_dye_clouds_um * sqrt(od_particle) in grain.py). The host reduces it
    // to a radius and taps; 0 means the cell is unblurred.
    double blur_sigma_px = 0.0;
};

struct GrainSampleRequest {
    // npix * cell_count floats, index p * cell_count + k, matching `cells`.
    // WHOLE FRAME: the dye-cloud blur reads neighbours, so this is not sliced.
    const float* density_cells = nullptr;
    const GrainCell* cells = nullptr;
    int cell_count = 0;
    uint32_t width = 0;
    uint32_t height = 0;    // npix == width * height
    uint32_t npix = 0;
    uint32_t seed_offset = 0;
};

struct GrainSampleDiagnostics {
    bool attempted = false;
    bool engaged = false;
    const char* reason = "";
    uint32_t slices = 0;
    uint32_t max_blur_radius = 0;  // widest dye-cloud kernel carried this frame
    uint64_t samples = 0;          // npix * cell_count
    uint64_t out_of_branch = 0;   // samples the CPU would have drawn from another branch
    double upload_ms = 0.0;
    double gpu_ms = 0.0;
    double readback_ms = 0.0;
};

// Writes npix*3 accumulated grain floats to `out_rgb` only when the whole pass
// succeeded; on any failure `out_rgb` is untouched and the caller runs the CPU
// sampler. Never throws.
bool grain_sample(const GrainSampleRequest& request, float* out_rgb,
                  GrainSampleDiagnostics* diagnostics);

// In-emulsion scatter + back-reflection halation on the GPU (issue #206; shader
// gpu/halation_scatter.comp, adapted from spektrafilm OFX). Same model and
// parameters as model/diffusion.cpp::apply_halation_um, computed in f32 on the
// device over the whole frame, so the IIR sweeps see the complete image as the
// CPU does. Fast GPU output: tolerance-bounded against the f64 CPU pass,
// deterministic on one device, never identity evidence.
struct HalationScatterRequest {
    const double* raw_rgb = nullptr;  // interleaved RGB, width*height*3 doubles
    int width = 0;
    int height = 0;
    double pixel_size_um = 0.0;
    double scatter_amount = 0.0;
    double scatter_spatial_scale = 1.0;
    double scatter_core_um[3] = {0.0, 0.0, 0.0};
    double scatter_tail_um[3] = {0.0, 0.0, 0.0};
    double scatter_tail_weight[3] = {0.0, 0.0, 0.0};
    double halation_amount = 0.0;
    double halation_spatial_scale = 1.0;
    double halation_strength[3] = {0.0, 0.0, 0.0};
    double halation_first_sigma_um[3] = {0.0, 0.0, 0.0};
    int halation_n_bounces = 0;
    double halation_bounce_decay = 0.5;
    // MIXTURE MODE (#213). When mixture_count > 0 the three fixed
    // exponential-tail surrogates are replaced by an arbitrary weighted set of
    // Gaussians, and `scatter_amount` is the convex-combination fraction. That is
    // all the camera diffusion filter needs: its PSF decomposes into exactly this
    // (model/diffusion.cpp, build_diffusion_mixture) and its resolve,
    // (1 - p_s) * E_in + p_s * (K_s * E_in), is the SAME expression the scatter
    // resolve already computes. So no new shader: this pass generalises.
    //
    // sigmas are in PIXELS (already through spatial_scale / pixel_size_um, so
    // scatter_spatial_scale must be 1 and pixel_size_um is unused for them), and
    // weights are per component per channel, component-major: [comp * 3 + ch].
    const double* mixture_sigma_px = nullptr;
    const double* mixture_weight = nullptr;
    int mixture_count = 0;
    bool halation_renormalize = true;
    // 0 = the host's staging band (64 MiB of whole rows); a test sets it small
    // to prove that the upload/readback banding never touches the numbers.
    uint32_t staging_bytes_override = 0;
};

struct HalationScatterDiagnostics {
    bool attempted = false;
    bool engaged = false;
    const char* reason = "none";  // process-lifetime literal
    uint32_t bands = 0;       // staging bands uploaded (transfer granularity only)
    uint32_t dispatches = 0;
    // Host-side wall clock: f64 -> f32 staging + upload copies, the pass's
    // submit-to-fence, readback copies + f32 -> f64. Observability for the
    // device A/B, nothing gates on it.
    double upload_ms = 0.0;
    double gpu_ms = 0.0;
    double readback_ms = 0.0;
};

// Writes width*height*3 doubles to `out_rgb` (must not alias `raw_rgb`) only when
// the whole pass succeeded; on any failure `out_rgb` is untouched, the function
// returns false with the reason in `diagnostics`, and the caller runs the CPU
// pass. Returns true with engaged=false when the parameters make the pass a
// no-op (the CPU pass would also leave the image unchanged). Never throws.
bool halation_scatter(const HalationScatterRequest& request, double* out_rgb,
                      HalationScatterDiagnostics* diagnostics) noexcept;

}  // namespace spk::gpu

#endif  // SPK_GPU_VULKAN_COMPUTE_H
