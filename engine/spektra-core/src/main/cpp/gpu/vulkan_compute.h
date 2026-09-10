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

// FRAME RESIDENCY (#220).
//
// Every pass in this header is otherwise self-contained: convert f64 -> f32,
// upload, dispatch, read back, convert back. A 12.5 MP export does that eight
// times, and the movement is measurable -- filming_expose alone spends 12 ms up
// and 25 ms down around an 8-32 ms kernel.
//
// The cost is not why residency matters, though. ~300 ms of an ~8600 ms export
// is movement, against 4742 ms in the diffusion transform. What residency
// changes is WHICH STAGES CAN GO ON THE GPU AT ALL: filming_develop measures
// 29.5 ms on the CPU and ~45 ms on the GPU, with a 16-35 ms kernel, purely
// because of its round trip. A cheap pointwise stage moved to the GPU on its own
// gets SLOWER. It only becomes free once the frame is already there.
//
// While a frame is open, a participating pass reads the resident plane and
// writes the other half of a device-local ping-pong, and does no transfer at
// all. Passes that do not participate are unaffected: they see a closed frame
// and behave exactly as before.
struct FrameDiagnostics {
    bool attempted = false;
    bool engaged = false;
    const char* reason = "";   // process-lifetime literal
    double ms = 0.0;
};

// Uploads width*height*3 f64 components once and leaves them device-resident.
// Fails closed: on any refusal no frame is open and every pass behaves as it
// does today, so a caller may always proceed without checking.
bool frame_open(const double* rgb, int width, int height,
                FrameDiagnostics* diagnostics);

// Reads the resident plane back into `rgb` and closes the frame. Returns false
// if no frame was open or the readback failed -- in which case the caller's
// buffer is untouched and the CPU result it already holds is still valid.
bool frame_close(double* rgb, FrameDiagnostics* diagnostics);

// Closes the frame WITHOUT reading back. For the abort path: a caller that has
// fallen back to the CPU mid-pipeline must not leave a stale plane resident for
// the next frame to pick up.
void frame_discard();

// True when a frame of exactly this geometry is resident. Passes use it to
// decide whether to transfer; callers use it to decide whether the GPU route is
// still live after a pass refused.
bool frame_active(int width, int height);

// Filming pointwise stages on the GPU (#218; shader gpu/filming_stage.comp).
//
// These are the two halves of the filming stage that render_pointwise_chain
// already knows how to compute but never gets to, because that route is
// all-or-nothing: spektra.cpp's pointwise_route_ineligibility refuses it the
// moment grain, halation, camera diffusion, a lens blur or a scan spatial
// effect is active, which is every render a user actually makes. Splitting the
// kernel at the log10 makes each half a NODE the pipeline can call on its own,
// with the spatial passes running between them exactly as they do on the CPU.
//
// FAST GPU. f32 against the CPU's f64, tolerance-bounded, never parity
// evidence -- the same contract as every other pass in this header.

struct FilmingStageDiagnostics {
    bool attempted = false;
    bool engaged = false;
    const char* reason = "";     // process-lifetime literal
    // True when the pass ran against the resident frame and moved nothing.
    bool resident = false;
    double upload_ms = 0.0;
    double gpu_ms = 0.0;
    double readback_ms = 0.0;
};

// EXPOSE: ProPhoto RGB -> camera raw irradiance, via the Hanatos2025 tc_lut
// (tc_edge*tc_edge*3 doubles, the NdArray the profile loader produces).
//
// Exactly one of `rgb_f64` and `rgb_f32` is the source, matching the engine's
// two entry points: expose() takes a float64 plane, expose_f32_gain() takes the
// float32 preprocess output plus the auto-exposure gain. `gain` multiplies the
// source and is 1.0 for the f64 path.
//
// Exactly one of `out_raw` (the linear irradiance the spatial stages consume)
// and `out_log_raw` (the log10 folded in) must be non-null -- that mirrors
// expose_impl's `pointwise_fused` split, which exists so a 12 MP render need
// not materialise a 288 MB f64 plane it will not read.
//
// `width`/`height` are what let the pass notice a RESIDENT frame of this
// geometry (see frame_open above). When one is open every host pointer here is
// ignored and may be null: the source is the resident plane and the result stays
// resident.
bool filming_expose(const double* rgb_f64, const float* rgb_f32, double gain,
                    uint32_t npix, int width, int height, const double* tc_lut,
                    uint32_t tc_edge, double exposure_multiplier, double* out_raw,
                    float* out_log_raw, FilmingStageDiagnostics* diagnostics);

// DEVELOP: density_curves.py::interpolate_exposure_to_density. `axis` is the
// per-channel axis that function builds (log_exposure[k] / gamma[c], points*3)
// and `curve` the NORMALIZED density curves (points*3); both are the host's to
// compute, as everywhere else here. The POINTWISE DIR couplers are deliberately
// not folded in -- on a real render the coupler correction is diffused, which is
// a spatial pass with its own kernel.
bool filming_develop(const float* log_raw, uint32_t npix, int width, int height,
                     const float* axis, const float* curve, uint32_t points,
                     float* out_density, FilmingStageDiagnostics* diagnostics);

// HIGHLIGHT BOOST on the GPU (#219; shader gpu/filming_stage.comp modes 2-3).
//
// numba_boost_hightlights.boost_highlights, which is a per-element map over a
// scalar derived from the FRAME MAXIMUM. That maximum is why it is not a
// one-dispatch pass: the kernel reduces each workgroup to a float, the host
// takes the maximum of those IN f64 and derives raw_x0, a, k and the scale from
// it, and a second dispatch applies the map. The kernel still owns no parameter.
//
// `rgb` is width*height*3 f64, boosted IN PLACE, and may be null when a frame is
// resident. Returns false without touching it on any refusal -- including the
// cases the CPU treats as identities (boost_ev <= 0, raw_x0 == max_raw), which
// are left to the CPU rather than reproduced, because an identity is cheaper to
// skip than to dispatch.
bool highlight_boost(double* rgb, int width, int height, double boost_ev,
                     double boost_range, double protect_ev,
                     FilmingStageDiagnostics* diagnostics);

// f32 FFT convolution on the GPU (#216; shader gpu/fft_convolve.comp).
//
// Computes the SAME operator kernels/fft_convolve.h computes -- the direct
// diffusion loop, reassociated through a transform -- in f32 on the device. That
// operator is 6.9 s of a 10.9 s 12.5 MP export with Black Pro-Mist on, i.e. 63%
// of the engine, and the CPU runs its column pass on one core.
//
// f32 IS NOT THE RISK, and that is measured rather than assumed:
// tools/gpu_probe/probe_f32_fft_main.cpp ran the engine's own templated
// implementation at both precisions on the real Black Pro-Mist PSF and got
// max_abs 1.2e-06 against a scene peaking at 3.8 -- inside the 1e-4 parity band
// -- and 0.000 8-bit display codes.
//
// FAST GPU. The transform is f32 where the CPU's is f64, and it is a FULL
// complex transform where the CPU exploits Hermitian symmetry, so the rounding
// differs by construction. Tolerance-bounded, never parity evidence.
//
// SCRATCH IS WHAT CAPS THE TRANSFORM. Two complex ping-pong planes plus the
// kernel spectrum is 3 * n^2 * 8 bytes: 25 MB at n = 1024, 402 MB at n = 4096
// and 1.6 GB at n = 8192. The CPU path affords 8192 because its spectra are
// n x (n/2+1) real-to-complex; this one does not, so it refuses above
// kFftGpuMaxTransform and the caller runs the CPU path for kernels that need a
// larger transform.
constexpr int kFftGpuMaxTransform = 4096;

// Planes cross in f64, exactly as HalationScatterRequest does, and the f32
// conversion happens inside the mapped upload. The engine's diffusion stage
// holds f64 planes; taking f32 here would force a whole extra staging copy of
// the padded plane -- 176 MB per channel at a 12.5 MP Black Pro-Mist export --
// for no numerical gain, since the device rounds to f32 either way.
struct FftConvolveRequest {
    // The UNPADDED source image, width*height*3 interleaved. The reflect
    // padding happens in the shader.
    //
    // kernels/fft_convolve.h takes a pre-built (h+ks-1) x (w+ks-1) float64
    // padded plane, and building one is not cheap at export scale: ks reaches
    // ~2300 at 12.5 MP, so that plane is a 275 MB allocation, written once per
    // channel and then converted to f32 on upload, to carry an image whose
    // unpadded f32 plane is 50 MB. Reading the source through the same reflect
    // map costs one integer fold per texel and removes the allocation entirely.
    const double* src_rgb = nullptr;
    int width = 0;
    int height = 0;
    int channel = 0;                // which interleaved component to convolve
    const double* kern = nullptr;   // ks x ks row-major; ks odd and >= 1
    int ks = 0;
    // Output addressing, matching the CPU entry point: the component at (x, y)
    // is written to out[(y * width + x) * out_stride + out_offset], so an
    // interleaved RGB plane is filled one channel per call.
    int out_stride = 1;
    int out_offset = 0;
    int max_transform = kFftGpuMaxTransform;
};

struct FftConvolveDiagnostics {
    bool attempted = false;
    bool engaged = false;
    const char* reason = "";     // process-lifetime literal
    int transform_size = 0;      // n actually used
    uint32_t tiles = 0;          // overlap-save tiles, one submission each
    uint32_t dispatches = 0;
    // True when the ping-pong scratch ended up in HOST_VISIBLE memory anyway.
    // Not an error -- a unified-memory device may have no GPU-private type --
    // but it is the difference between the transform running at device
    // bandwidth and running at host-coherent bandwidth, so it is reported
    // rather than assumed.
    bool scratch_host_visible = false;
    double upload_ms = 0.0;
    double gpu_ms = 0.0;
    double readback_ms = 0.0;
};

// Writes width*height components through `out` only when the whole pass
// succeeded; on any refusal `out` is untouched and the caller runs the CPU
// convolution. Never throws.
bool fft_convolve(const FftConvolveRequest& request, double* out,
                  FftConvolveDiagnostics* diagnostics);

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
    // True when the pass read and wrote the resident frame (#220) and moved
    // nothing across the bus.
    bool resident = false;
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
