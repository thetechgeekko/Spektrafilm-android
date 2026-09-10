/*
 * Spektrafilm for Android — per-stage/per-filter render timing (DIAGNOSTIC).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Wall-clock accumulators for the render pipeline's stages and filters, surfaced
 * to logcat once per render so we can see WHERE the interactive latency goes
 * (issue #146/#152 — the device validation found the scan stage is not the
 * preview bottleneck; grain/halation/filming and per-source LUT bakes are).
 *
 * OBSERVATION ONLY — reading the clock and adding a double NEVER changes a
 * rendered pixel, so this is outside the parity contract (the parity suite is
 * unaffected). Slots are written on the ORCHESTRATOR thread only: each timer
 * brackets a whole stage/filter call, and the parallel_for worker fan-out lives
 * *inside* that call, so there is no cross-thread write and no synchronization.
 * Renders are synchronous on their caller/orchestrator thread, but different
 * callers may overlap (preview, export, magnifier, or LUT bake). Each outer C
 * API entry therefore owns a thread-local context and publishes one immutable
 * snapshot on completion; readers on that thread never observe partial spans.
 */
#ifndef SPK_RUNTIME_STAGE_TIMER_H
#define SPK_RUNTIME_STAGE_TIMER_H

#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>

#ifdef __ANDROID__
#include <android/trace.h>
#endif

namespace spk {

enum StageSlot {
    STG_PREPROCESS = 0,   // geometry crop/rescale + AE metering
    STG_TC_LUT,           // filming Hanatos tc_lut build (cold-start heavy)
    STG_PRINT_DIGEST,     // print neutral-CC digest (print route)
    STG_FILMING_EXPOSE,   // expose(): spectral upsample -> camera raw -> tc_lut apply -> log_raw
    STG_HIGHLIGHT_BOOST,  // filming pre-clip highlight boost
    STG_DIFFUSION,        // camera optical diffusion filter (Black Pro-Mist)
    STG_LENS_BLUR,        // camera lens blur (Gaussian)
    STG_HALATION,         // in-emulsion scatter + back-reflection halation
    STG_DEVELOP,          // exposure->density curve interpolation
    STG_DIR_COUPLERS,     // DIR-coupler density correction (spatial)
    STG_GRAIN,            // stochastic AgX grain particle model
    STG_PRINT_EXPOSE,     // enlarger expose + print develop (print route)
    STG_SCAN,             // scan(): whole stage, density -> display RGB (CPU or GPU)
    STG_SCAN_SPATIAL,     // SUB-MEASURE of scan: gamut compress / lens blur / unsharp
    STG_GLARE,            // SUB-MEASURE of scan: viewing-glare FIELD build (print
                          // route). The per-pixel add is folded into the scan loops
                          // and is not separable from them.
    STG_COUNT
};

inline const char* stage_name(int s) {
    switch (s) {
        case STG_PREPROCESS:    return "preprocess";
        case STG_TC_LUT:        return "tc_lut_build";
        case STG_PRINT_DIGEST:  return "print_digest";
        case STG_FILMING_EXPOSE:return "filming_expose";
        case STG_HIGHLIGHT_BOOST:return "highlight_boost";
        case STG_DIFFUSION:     return "camera_diffusion";
        case STG_LENS_BLUR:     return "lens_blur";
        case STG_HALATION:      return "halation";
        case STG_DEVELOP:       return "develop";
        case STG_DIR_COUPLERS:  return "dir_couplers";
        case STG_GRAIN:         return "grain";
        case STG_PRINT_EXPOSE:  return "print_expose";
        case STG_SCAN:          return "scan";
        case STG_SCAN_SPATIAL:  return "scan_spatial";
        case STG_GLARE:         return "glare_field";
        default:                return "?";
    }
}

enum RenderTimingKind {
    RTK_UNKNOWN = 0,
    RTK_EXACT_RENDER,
    RTK_EXPORT,
    RTK_PREVIEW,
    RTK_MAGNIFIER,
    RTK_ROI,
    RTK_TAP,
    RTK_LUT_BAKE,
};

enum RenderTimingOutcome {
    RTO_NONE = 0,
    RTO_RUNNING,
    RTO_OK,
    RTO_ERROR,
};

enum AppRenderOutcome {
    ARO_CONSUMED = 1,
    ARO_CANCELLED,
    ARO_SUPERSEDED,
    ARO_FAILED,
};

inline const char* render_timing_kind_name(RenderTimingKind kind) {
    switch (kind) {
        case RTK_EXACT_RENDER: return "exact_render";
        case RTK_EXPORT:       return "export";
        case RTK_PREVIEW:      return "preview";
        case RTK_MAGNIFIER:    return "magnifier";
        case RTK_ROI:          return "roi";
        case RTK_TAP:          return "tap";
        case RTK_LUT_BAKE:     return "lut_bake";
        default:               return "unknown";
    }
}

inline const char* app_render_outcome_name(AppRenderOutcome outcome) {
    switch (outcome) {
        case ARO_CONSUMED:   return "consumed";
        case ARO_CANCELLED:  return "cancelled";
        case ARO_SUPERSEDED: return "superseded";
        case ARO_FAILED:     return "failed";
        default:             return "unknown";
    }
}

inline const char* render_timing_outcome_name(RenderTimingOutcome outcome) {
    switch (outcome) {
        case RTO_RUNNING: return "running";
        case RTO_OK:      return "ok";
        case RTO_ERROR:   return "error";
        default:          return "none";
    }
}

// Render-local observability for the resident filming -> printing -> scan GPU
// operation. The values mirror gpu::PointwiseChainDiagnostics without making
// this generic timing header depend on the Vulkan interface. `reason` always
// points at a process-lifetime string literal owned by the route/GPU module.
struct GpuPointwiseTimingSnapshot {
    bool requested = false;
    bool attempted = false;
    bool engaged = false;
    const char* reason = "not_requested";
    uint32_t dispatches = 0;
    uint32_t input_uploads = 0;
    uint32_t final_readbacks = 0;
    uint64_t interstage_host_bytes = 0;
    uint32_t pipeline_creates = 0;
    uint32_t buffer_allocations = 0;
    uint64_t static_upload_bytes = 0;
    // The keyed numeric capability gate is separate from the product-frame
    // counters above: a cold verdict runs the small chain more than once and
    // must not make the actual frame look like a multi-upload operation.
    const char* self_test_state = "not_run";
    double self_test_duration_ms = 0.0;
    uint32_t self_test_chain_runs = 0;
    uint32_t self_test_dispatches = 0;
    uint32_t self_test_input_uploads = 0;
    uint32_t self_test_final_readbacks = 0;
    uint64_t self_test_interstage_host_bytes = 0;
    uint32_t self_test_pipeline_creates = 0;
    uint32_t self_test_buffer_allocations = 0;
    uint64_t self_test_static_upload_bytes = 0;
};

// Render-local observability for the GPU halation/scatter pass (#206).
struct GpuHalationTimingSnapshot {
    bool attempted = false;
    bool engaged = false;
    const char* reason = "not_requested";
    uint32_t bands = 0;
    uint32_t dispatches = 0;
    double upload_ms = 0.0;
    double gpu_ms = 0.0;
    double readback_ms = 0.0;
};

// Render-local observability for the GPU spectral scan/print dispatches. One
// export drives dispatch_scan several times and each call slices the frame, so
// these accumulate across every call of a render. `guard_ms` is the upload
// loop, which is also the non-finite guard, and `readback_ms` the copy out:
// both are per-slice host work, and separating them from `gpu_ms` is what says
// whether this pass is bound by the device or by moving bytes.
struct GpuScanTimingSnapshot {
    uint32_t calls = 0;
    uint32_t slices = 0;
    double guard_ms = 0.0;
    double gpu_ms = 0.0;
    double readback_ms = 0.0;
    uint64_t bytes_up = 0;
    uint64_t bytes_down = 0;
};

// Immutable once published. `stages_ms` contains inclusive durations: the two
// documented sub-measures remain nested inside scan and must not be added to it.
struct StageTimingSnapshot {
    uint64_t render_id = 0;
    RenderTimingKind kind = RTK_UNKNOWN;
    RenderTimingOutcome outcome = RTO_NONE;
    int status_code = 0;
    double wall_ms = 0.0;
    unsigned long long fft_fallbacks = 0;
    // Which FFT the diffusion convolution actually got (#148). The transform
    // size swings this stage by 5x on a 12 MP frame, and reserve_fft_scratch
    // steps it DOWN whenever the memory budget cannot admit the scratch -- so
    // the same build can be fast or slow on the same image depending only on
    // what else is resident. None of that was observable: the only FFT signal
    // was a count of direct-loop fallbacks, which stays 0 in exactly the case
    // that hurts. Observability only, nothing gates on it.
    unsigned long long fft_convolutions = 0;   // convolutions that took the FFT
    unsigned long long fft_clamped = 0;        // ...of which the budget shrank
    int fft_min_n = 0;                         // smallest transform actually used
    int fft_max_ks = 0;                        // largest kernel that got here
    GpuPointwiseTimingSnapshot gpu_pointwise;
    GpuHalationTimingSnapshot gpu_halation;
    GpuHalationTimingSnapshot gpu_dir_diffusion;
    GpuScanTimingSnapshot gpu_scan;
    // Where the grain stage's time actually goes, accumulated over every
    // channel and sublayer of one render (#180). The stage is the largest item
    // in an export and a host profile put 94.6 % of it in the sampler; the
    // device did not agree, and there was no way to see which part disagreed.
    // These are wall-clock inside the stage, so they sum to about the stage
    // total; they are observability, nothing gates on them.
    double grain_sampler_ms = 0.0;      // the per-pixel Poisson + Binomial walk
    double grain_particle_blur_ms = 0.0;  // per-particle dye-cloud blur
    double grain_prep_ms = 0.0;         // density shift into the per-layer buffer
    double grain_accum_ms = 0.0;        // accumulate each layer into the total
    double grain_micro_ms = 0.0;        // micro-structure clumping
    double grain_final_ms = 0.0;        // final blur + density-min subtract
    // Materialising the per-sublayer density array: npix * 9 floats, which
    // is 450 MB at 12.5 MP, zero-initialised and then interpolated into.
    double grain_layers_ms = 0.0;
    // Which grain sampler actually ran (#180). Without this the export cannot
    // show whether the Fast GPU route's cheaper generator engaged, and a
    // measured non-improvement is indistinguishable from a latch that never
    // fired.
    bool grain_fast_sampler = false;
    bool grain_ran = false;
    // Every fenced submit-and-wait this render performed, across all GPU
    // passes. Each one is a full pipeline stall, so the count is the thing to
    // drive down when stages are made resident.
    uint32_t gpu_submissions = 0;
    double stages_ms[STG_COUNT] = {0};
};

struct StageTimingThreadState {
    StageTimingSnapshot current;
    StageTimingSnapshot completed;
    int depth = 0;
    int stage_suppression_depth = 0;
    bool failed = false;
    std::chrono::steady_clock::time_point started;
};

// All mutable spans are local to the synchronous caller/orchestrator thread.
// Only the monotonically increasing correlation id is shared across renders.
inline StageTimingThreadState& stage_timing_state() {
    static thread_local StageTimingThreadState state;
    return state;
}

inline uint64_t next_stage_timing_render_id() {
    static std::atomic<uint64_t> next{1};
    return next.fetch_add(1, std::memory_order_relaxed);
}

inline const StageTimingSnapshot& stage_timing_snapshot() {
    return stage_timing_state().completed;
}

inline uint64_t stage_timing_render_id() {
    return stage_timing_snapshot().render_id;
}

// One diffusion convolution's transform choice. `unclamped_n` is what the
// selector would have picked against the ceiling alone, so n < unclamped_n is
// exactly "the memory budget made this slower".
inline void stage_timing_note_fft_choice(int n, int unclamped_n, int ks) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    StageTimingSnapshot& c = state.current;
    ++c.fft_convolutions;
    if (n > 0 && unclamped_n > 0 && n < unclamped_n) ++c.fft_clamped;
    if (n > 0 && (c.fft_min_n == 0 || n < c.fft_min_n)) c.fft_min_n = n;
    if (ks > c.fft_max_ks) c.fft_max_ks = ks;
}

inline void stage_timing_note_fft_fallback() {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth > 0) ++state.current.fft_fallbacks;
}

inline void stage_timing_note_gpu_halation(
    bool attempted, bool engaged, const char* reason, uint32_t bands,
    uint32_t dispatches, double upload_ms, double gpu_ms, double readback_ms) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    GpuHalationTimingSnapshot& h = state.current.gpu_halation;
    h.attempted = attempted;
    h.engaged = engaged;
    h.reason = reason ? reason : "unknown";
    h.bands = bands;
    h.dispatches = dispatches;
    h.upload_ms = upload_ms;
    h.gpu_ms = gpu_ms;
    h.readback_ms = readback_ms;
}

// Accumulating, unlike the halation notes: dispatch_scan is called several
// times per render and each call is one more piece of the same total.
// Each grain phase adds into the render's running total. Called from the
// stage itself, which runs many times per render (channel x sublayer).
enum class GrainPhase { Sampler, ParticleBlur, Prep, Accum, Micro, Final, Layers };

inline void stage_timing_note_grain_phase(GrainPhase phase, double ms) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    StageTimingSnapshot& c = state.current;
    switch (phase) {
        case GrainPhase::Sampler: c.grain_sampler_ms += ms; break;
        case GrainPhase::ParticleBlur: c.grain_particle_blur_ms += ms; break;
        case GrainPhase::Prep: c.grain_prep_ms += ms; break;
        case GrainPhase::Accum: c.grain_accum_ms += ms; break;
        case GrainPhase::Micro: c.grain_micro_ms += ms; break;
        case GrainPhase::Final: c.grain_final_ms += ms; break;
        case GrainPhase::Layers: c.grain_layers_ms += ms; break;
    }
}

// Scoped helper so a phase cannot be left unclosed on an early return.
class ScopedGrainPhase {
   public:
    explicit ScopedGrainPhase(GrainPhase phase)
        : phase_(phase), started_(std::chrono::steady_clock::now()) {}
    ~ScopedGrainPhase() { stop(); }

    // Close early when the phase ends before the enclosing scope does.
    // Idempotent, so the destructor cannot double-count.
    void stop() {
        if (stopped_) return;
        stopped_ = true;
        stage_timing_note_grain_phase(
            phase_, std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - started_)
                        .count());
    }
    ScopedGrainPhase(const ScopedGrainPhase&) = delete;
    ScopedGrainPhase& operator=(const ScopedGrainPhase&) = delete;

   private:
    GrainPhase phase_;
    std::chrono::steady_clock::time_point started_;
    bool stopped_ = false;
};

inline void stage_timing_note_grain_sampler(bool fast) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    state.current.grain_ran = true;
    state.current.grain_fast_sampler = fast;
}

inline void stage_timing_note_gpu_scan_add(uint32_t slices, double guard_ms,
                                           double gpu_ms, double readback_ms,
                                           uint64_t bytes_up, uint64_t bytes_down) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    GpuScanTimingSnapshot& g = state.current.gpu_scan;
    ++g.calls;
    g.slices += slices;
    g.guard_ms += guard_ms;
    g.gpu_ms += gpu_ms;
    g.readback_ms += readback_ms;
    g.bytes_up += bytes_up;
    g.bytes_down += bytes_down;
}

inline void stage_timing_note_gpu_submissions(uint32_t n) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    state.current.gpu_submissions += n;
}

inline void stage_timing_note_gpu_dir_diffusion(
    bool attempted, bool engaged, const char* reason, uint32_t bands,
    uint32_t dispatches, double upload_ms, double gpu_ms, double readback_ms) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    GpuHalationTimingSnapshot& h = state.current.gpu_dir_diffusion;
    h.attempted = attempted;
    h.engaged = engaged;
    h.reason = reason ? reason : "unknown";
    h.bands = bands;
    h.dispatches = dispatches;
    h.upload_ms = upload_ms;
    h.gpu_ms = gpu_ms;
    h.readback_ms = readback_ms;
}

inline void stage_timing_note_gpu_pointwise(
    bool requested, bool attempted, bool engaged, const char* reason,
    uint32_t dispatches, uint32_t input_uploads, uint32_t final_readbacks,
    uint64_t interstage_host_bytes, uint32_t pipeline_creates,
    uint32_t buffer_allocations, uint64_t static_upload_bytes) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    GpuPointwiseTimingSnapshot& pointwise = state.current.gpu_pointwise;
    pointwise.requested = requested;
    pointwise.attempted = attempted;
    pointwise.engaged = engaged;
    pointwise.reason = reason ? reason : "unknown";
    pointwise.dispatches = dispatches;
    pointwise.input_uploads = input_uploads;
    pointwise.final_readbacks = final_readbacks;
    pointwise.interstage_host_bytes = interstage_host_bytes;
    pointwise.pipeline_creates = pipeline_creates;
    pointwise.buffer_allocations = buffer_allocations;
    pointwise.static_upload_bytes = static_upload_bytes;
}

inline void stage_timing_note_gpu_pointwise_self_test(
    const char* state_name, double duration_ms, uint32_t chain_runs,
    uint32_t dispatches, uint32_t input_uploads, uint32_t final_readbacks,
    uint64_t interstage_host_bytes, uint32_t pipeline_creates,
    uint32_t buffer_allocations, uint64_t static_upload_bytes) {
    StageTimingThreadState& state = stage_timing_state();
    if (state.depth <= 0) return;
    GpuPointwiseTimingSnapshot& pointwise = state.current.gpu_pointwise;
    pointwise.self_test_state = state_name ? state_name : "not_run";
    pointwise.self_test_duration_ms = duration_ms;
    pointwise.self_test_chain_runs = chain_runs;
    pointwise.self_test_dispatches = dispatches;
    pointwise.self_test_input_uploads = input_uploads;
    pointwise.self_test_final_readbacks = final_readbacks;
    pointwise.self_test_interstage_host_bytes = interstage_host_bytes;
    pointwise.self_test_pipeline_creates = pipeline_creates;
    pointwise.self_test_buffer_allocations = buffer_allocations;
    pointwise.self_test_static_upload_bytes = static_upload_bytes;
}

inline bool stage_slot_is_nested(int slot) {
    return slot == STG_SCAN_SPATIAL || slot == STG_GLARE;
}

inline double stage_timing_top_level_ms(const StageTimingSnapshot& snapshot) {
    double total = 0.0;
    for (int i = 0; i < STG_COUNT; ++i) {
        if (!stage_slot_is_nested(i)) total += snapshot.stages_ms[i];
    }
    return total;
}

// One context is created for each outer C API render. Nested entry points (the
// preview path funnels through export) share it, so spans are published exactly
// once, after the complete outer call finishes. Unreported/exceptional exits are
// recorded as errors rather than silently reusing the previous successful set.
class ScopedRenderTiming {
public:
    explicit ScopedRenderTiming(RenderTimingKind kind)
        : owner_(stage_timing_state().depth == 0) {
        StageTimingThreadState& state = stage_timing_state();
        if (owner_) {
            state.current = StageTimingSnapshot{};
            state.current.render_id = next_stage_timing_render_id();
            state.current.kind = kind;
            state.current.outcome = RTO_RUNNING;
            state.current.status_code = -1;
            state.failed = false;
            state.started = std::chrono::steady_clock::now();
#ifdef __ANDROID__
            if (ATrace_isEnabled()) {
                std::snprintf(trace_name_, sizeof(trace_name_), "spk.render.%s#%llu",
                              render_timing_kind_name(kind),
                              static_cast<unsigned long long>(state.current.render_id));
                ATrace_beginSection(trace_name_);
                trace_open_ = true;
            }
#endif
        }
        render_id_ = state.current.render_id;
        ++state.depth;
    }

    ~ScopedRenderTiming() {
        StageTimingThreadState& state = stage_timing_state();
        if (!finished_) state.failed = true;
        if (state.depth > 0) --state.depth;
        if (!owner_) return;

        state.current.wall_ms = std::chrono::duration<double, std::milli>(
                                    std::chrono::steady_clock::now() - state.started)
                                    .count();
        state.current.outcome = state.failed ? RTO_ERROR : RTO_OK;
        state.completed = state.current;
#ifdef __ANDROID__
        if (trace_open_) ATrace_endSection();
#endif
    }

    template <typename Status>
    Status finish(Status status) {
        StageTimingThreadState& state = stage_timing_state();
        const int code = static_cast<int>(status);
        finished_ = true;
        if (code != 0) {
            state.failed = true;
            state.current.status_code = code;
        } else if (owner_ && !state.failed) {
            state.current.status_code = 0;
        }
        return status;
    }

    uint64_t render_id() const { return render_id_; }

    ScopedRenderTiming(const ScopedRenderTiming&) = delete;
    ScopedRenderTiming& operator=(const ScopedRenderTiming&) = delete;

private:
    bool owner_ = false;
    bool finished_ = false;
    uint64_t render_id_ = 0;
#ifdef __ANDROID__
    bool trace_open_ = false;
    char trace_name_[64] = {0};
#endif
};

// Format the non-zero slots as "stage=1.23 other=4.56" into buf; returns the
// count of bytes written (excluding the NUL).
//
// TWO THINGS THE OUTPUT DOES NOT SAY, and both have already misled a reading:
//
//  1. Zero slots are SKIPPED, so a gated-off filter is invisible rather than shown
//     as 0. An export profile taken at default settings therefore says nothing
//     about camera_diffusion (Black Pro-Mist), lens_blur or glare_field — all
//     three default off. Absence here is not evidence of cheapness.
//  2. SUB-MEASURE slots are NESTED inside their parent, so the printed numbers
//     MUST NOT simply be added up. scan_spatial and glare_field both sit inside
//     the scan bracket, so a naive total double-counts them. Summing the line and
//     comparing against the native call's wall clock produced an apparent
//     "timers exceed wall clock by 40-75 ms"; subtracting the nested
//     scan_spatial turns that into the ~363 ms of JNI entry, param marshalling
//     and result allocation that genuinely is outside every stage.
inline int stage_timings_format(char* buf, int cap) {
    const StageTimingSnapshot& snapshot = stage_timing_snapshot();
    const double* t = snapshot.stages_ms;
    int off = 0;
    for (int i = 0; i < STG_COUNT && off < cap - 1; ++i) {
        if (t[i] <= 0.0) continue;
        int n = std::snprintf(buf + off, static_cast<size_t>(cap - off),
                              "%s%s=%.1f", off ? " " : "", stage_name(i), t[i]);
        if (n < 0) break;
        off += n;
    }
    // The GPU halation pass (#206): where the halation stage's time went when
    // the pass was attempted, so a logcat line answers "did it engage, and was
    // the device or the f64<->f32 staging the cost".
    const GpuHalationTimingSnapshot* notes[2] = {&snapshot.gpu_halation, &snapshot.gpu_dir_diffusion};
    const char* labels[2] = {"gpu_halation", "gpu_dir"};
    for (int i = 0; i < 2; ++i) {
        if (!notes[i]->attempted || off >= cap - 1) continue;
        int n = std::snprintf(buf + off, static_cast<size_t>(cap - off),
                              "%s%s=%s/%s/bands%u/up%.1f/gpu%.1f/down%.1f",
                              off ? " " : "", labels[i],
                              notes[i]->engaged ? "engaged" : "fallback",
                              notes[i]->reason, notes[i]->bands,
                              notes[i]->upload_ms, notes[i]->gpu_ms,
                              notes[i]->readback_ms);
        if (n > 0) off += n;
    }
    if (snapshot.gpu_scan.calls && off < cap - 1) {
        int n = std::snprintf(buf + off, static_cast<size_t>(cap - off),
                              "%sgpu_scan=calls%u/slices%u/guard%.1f/gpu%.1f/down%.1f/up%lluMB/dn%lluMB",
                              off ? " " : "", snapshot.gpu_scan.calls,
                              snapshot.gpu_scan.slices, snapshot.gpu_scan.guard_ms,
                              snapshot.gpu_scan.gpu_ms, snapshot.gpu_scan.readback_ms,
                              static_cast<unsigned long long>(snapshot.gpu_scan.bytes_up >> 20),
                              static_cast<unsigned long long>(snapshot.gpu_scan.bytes_down >> 20));
        if (n > 0) off += n;
    }
    if (snapshot.grain_ran && off < cap - 1) {
        int n = std::snprintf(
            buf + off, static_cast<size_t>(cap - off),
            "%sgrain_sampler=%s grain_parts=samp%.0f/pblur%.0f/prep%.0f/acc%.0f/micro%.0f/fin%.0f/layers%.0f",
            off ? " " : "", snapshot.grain_fast_sampler ? "fast" : "exact",
            snapshot.grain_sampler_ms, snapshot.grain_particle_blur_ms,
            snapshot.grain_prep_ms, snapshot.grain_accum_ms,
            snapshot.grain_micro_ms, snapshot.grain_final_ms,
            snapshot.grain_layers_ms);
        if (n > 0) off += n;
    }
    if (snapshot.gpu_submissions && off < cap - 1) {
        int n = std::snprintf(buf + off, static_cast<size_t>(cap - off),
                              "%sgpu_submits=%u", off ? " " : "", snapshot.gpu_submissions);
        if (n > 0) off += n;
    }
    if (cap > 0) buf[off < cap ? off : cap - 1] = '\0';
    return off;
}

inline void stage_timing_append(char* buf, int cap, int* off,
                                const char* format, ...) {
    if (!buf || cap <= 0 || !off || *off >= cap - 1) return;
    va_list args;
    va_start(args, format);
    const int remaining = cap - *off;
    const int n = std::vsnprintf(buf + *off, static_cast<size_t>(remaining),
                                 format, args);
    va_end(args);
    if (n < 0) return;
    *off += n < remaining ? n : remaining - 1;
}

inline void stage_timing_append_json_string(char* buf, int cap, int* off,
                                            const char* value) {
    const unsigned char* cursor = reinterpret_cast<const unsigned char*>(
        value ? value : "unknown");
    for (; *cursor != 0 && *off < cap - 1; ++cursor) {
        switch (*cursor) {
            case '"': stage_timing_append(buf, cap, off, "\\\""); break;
            case '\\': stage_timing_append(buf, cap, off, "\\\\"); break;
            case '\b': stage_timing_append(buf, cap, off, "\\b"); break;
            case '\f': stage_timing_append(buf, cap, off, "\\f"); break;
            case '\n': stage_timing_append(buf, cap, off, "\\n"); break;
            case '\r': stage_timing_append(buf, cap, off, "\\r"); break;
            case '\t': stage_timing_append(buf, cap, off, "\\t"); break;
            default:
                if (*cursor < 0x20u)
                    stage_timing_append(buf, cap, off, "\\u%04x",
                                        static_cast<unsigned>(*cursor));
                else
                    stage_timing_append(buf, cap, off, "%c",
                                        static_cast<int>(*cursor));
                break;
        }
    }
}

// Stable machine-readable schema for release benchmarks. All slots are emitted,
// including zero-valued gated-off effects. `trace_id` is the same id embedded in
// the Android ATrace/Perfetto render section name (`spk.render.<kind>#<id>`).
inline int stage_timings_json_format(char* buf, int cap) {
    constexpr int kRequiredCapacity = 2048;
    if (!buf || cap < kRequiredCapacity) {
        if (buf && cap > 0) buf[0] = '\0';
        return 0;
    }
    const StageTimingSnapshot& snapshot = stage_timing_snapshot();
    int off = 0;
    stage_timing_append(
        buf, cap, &off,
        "{\"schema\":\"spk.stage_timings.v1\",\"render_id\":%llu,"
        "\"trace_id\":%llu,\"kind\":\"%s\",\"native_outcome\":\"%s\","
        "\"status_code\":%d,\"wall_ms\":%.3f,\"top_level_ms\":%.3f,"
        "\"fft_fallbacks\":%llu,\"stages_ms\":{",
        static_cast<unsigned long long>(snapshot.render_id),
        static_cast<unsigned long long>(snapshot.render_id),
        render_timing_kind_name(snapshot.kind),
        render_timing_outcome_name(snapshot.outcome), snapshot.status_code,
        snapshot.wall_ms, stage_timing_top_level_ms(snapshot),
        snapshot.fft_fallbacks);
    for (int i = 0; i < STG_COUNT; ++i) {
        stage_timing_append(buf, cap, &off, "%s\"%s\":%.3f",
                            i ? "," : "", stage_name(i),
                            snapshot.stages_ms[i]);
    }
    stage_timing_append(
        buf, cap, &off,
        "},\"gpu_halation\":{\"attempted\":%s,\"engaged\":%s,\"reason\":\"",
        snapshot.gpu_halation.attempted ? "true" : "false",
        snapshot.gpu_halation.engaged ? "true" : "false");
    stage_timing_append_json_string(buf, cap, &off, snapshot.gpu_halation.reason);
    stage_timing_append(
        buf, cap, &off,
        "\",\"bands\":%u,\"dispatches\":%u,"
        "\"upload_ms\":%.1f,\"gpu_ms\":%.1f,\"readback_ms\":%.1f",
        snapshot.gpu_halation.bands, snapshot.gpu_halation.dispatches,
        snapshot.gpu_halation.upload_ms, snapshot.gpu_halation.gpu_ms,
        snapshot.gpu_halation.readback_ms);
    stage_timing_append(
        buf, cap, &off,
        "},\"gpu_dir_diffusion\":{\"attempted\":%s,\"engaged\":%s,\"reason\":\"",
        snapshot.gpu_dir_diffusion.attempted ? "true" : "false",
        snapshot.gpu_dir_diffusion.engaged ? "true" : "false");
    stage_timing_append_json_string(buf, cap, &off, snapshot.gpu_dir_diffusion.reason);
    stage_timing_append(
        buf, cap, &off,
        "\",\"bands\":%u,\"dispatches\":%u,"
        "\"upload_ms\":%.1f,\"gpu_ms\":%.1f,\"readback_ms\":%.1f",
        snapshot.gpu_dir_diffusion.bands, snapshot.gpu_dir_diffusion.dispatches,
        snapshot.gpu_dir_diffusion.upload_ms, snapshot.gpu_dir_diffusion.gpu_ms,
        snapshot.gpu_dir_diffusion.readback_ms);
    stage_timing_append(
        buf, cap, &off,
        "},\"gpu_scan\":{\"calls\":%u,\"slices\":%u,"
        "\"guard_ms\":%.1f,\"gpu_ms\":%.1f,\"readback_ms\":%.1f,"
        "\"bytes_up\":%llu,\"bytes_down\":%llu},"
        "\"gpu_submissions\":%u,",
        snapshot.gpu_scan.calls, snapshot.gpu_scan.slices,
        snapshot.gpu_scan.guard_ms, snapshot.gpu_scan.gpu_ms,
        snapshot.gpu_scan.readback_ms,
        static_cast<unsigned long long>(snapshot.gpu_scan.bytes_up),
        static_cast<unsigned long long>(snapshot.gpu_scan.bytes_down),
        snapshot.gpu_submissions);
    stage_timing_append(
        buf, cap, &off,
        "\"gpu_pointwise\":{\"requested\":%s,\"attempted\":%s,"
        "\"engaged\":%s,\"reason\":\"",
        snapshot.gpu_pointwise.requested ? "true" : "false",
        snapshot.gpu_pointwise.attempted ? "true" : "false",
        snapshot.gpu_pointwise.engaged ? "true" : "false");
    stage_timing_append_json_string(buf, cap, &off,
                                    snapshot.gpu_pointwise.reason);
    stage_timing_append(
        buf, cap, &off,
        "\",\"dispatches\":%u,"
        "\"input_uploads\":%u,\"final_readbacks\":%u,"
        "\"interstage_host_bytes\":%llu,\"pipeline_creates\":%u,"
        "\"buffer_allocations\":%u,\"static_upload_bytes\":%llu,"
        "\"self_test_state\":\"",
        snapshot.gpu_pointwise.dispatches,
        snapshot.gpu_pointwise.input_uploads,
        snapshot.gpu_pointwise.final_readbacks,
        static_cast<unsigned long long>(
            snapshot.gpu_pointwise.interstage_host_bytes),
        snapshot.gpu_pointwise.pipeline_creates,
        snapshot.gpu_pointwise.buffer_allocations,
        static_cast<unsigned long long>(snapshot.gpu_pointwise.static_upload_bytes));
    stage_timing_append_json_string(buf, cap, &off,
                                    snapshot.gpu_pointwise.self_test_state);
    stage_timing_append(
        buf, cap, &off,
        "\",\"self_test_duration_ms\":%.3f,"
        "\"self_test_chain_runs\":%u,\"self_test_dispatches\":%u,"
        "\"self_test_input_uploads\":%u,"
        "\"self_test_final_readbacks\":%u,"
        "\"self_test_interstage_host_bytes\":%llu,"
        "\"self_test_pipeline_creates\":%u,"
        "\"self_test_buffer_allocations\":%u,"
        "\"self_test_static_upload_bytes\":%llu},"
        "\"nested_stages\":{\"scan_spatial\":\"scan\","
        "\"glare_field\":\"scan\"}}}",
        snapshot.gpu_pointwise.self_test_duration_ms,
        snapshot.gpu_pointwise.self_test_chain_runs,
        snapshot.gpu_pointwise.self_test_dispatches,
        snapshot.gpu_pointwise.self_test_input_uploads,
        snapshot.gpu_pointwise.self_test_final_readbacks,
        static_cast<unsigned long long>(
            snapshot.gpu_pointwise.self_test_interstage_host_bytes),
        snapshot.gpu_pointwise.self_test_pipeline_creates,
        snapshot.gpu_pointwise.self_test_buffer_allocations,
        static_cast<unsigned long long>(
            snapshot.gpu_pointwise.self_test_static_upload_bytes));
    buf[off < cap ? off : cap - 1] = '\0';
    return off;
}

// App-level disposition is deliberately a separate keyed event: native work can
// complete successfully and still be discarded when a coroutine is superseded.
// Consumers fold the latest event for each render_id and exclude cancelled /
// superseded work from release baselines.
inline int stage_timing_outcome_json_format(char* buf, int cap,
                                            uint64_t render_id,
                                            AppRenderOutcome outcome) {
    constexpr int kRequiredCapacity = 256;
    if (!buf || cap < kRequiredCapacity || render_id == 0) {
        if (buf && cap > 0) buf[0] = '\0';
        return 0;
    }
    const int n = std::snprintf(
        buf, static_cast<size_t>(cap),
        "{\"schema\":\"spk.render_outcome.v1\",\"render_id\":%llu,"
        "\"app_outcome\":\"%s\"}",
        static_cast<unsigned long long>(render_id),
        app_render_outcome_name(outcome));
    if (n < 0 || n >= cap) {
        buf[0] = '\0';
        return 0;
    }
    return n;
}

// Nestable render-local bracket used by validation work which deliberately calls
// CPU stages but must not masquerade as product-frame stage time. It leaves wall
// time and the explicit self-test duration observable.
struct ScopedStageTimingSuppression {
    ScopedStageTimingSuppression() noexcept {
        ++stage_timing_state().stage_suppression_depth;
    }
    ~ScopedStageTimingSuppression() {
        StageTimingThreadState& state = stage_timing_state();
        if (state.stage_suppression_depth > 0)
            --state.stage_suppression_depth;
    }
    ScopedStageTimingSuppression(const ScopedStageTimingSuppression&) = delete;
    ScopedStageTimingSuppression& operator=(
        const ScopedStageTimingSuppression&) = delete;
};

// RAII bracket: adds its lifetime (ms) to `slot` on destruction. Bracket a whole
// stage/filter call on the orchestrator thread.
struct ScopedStage {
    int slot;
    bool active;
    std::chrono::steady_clock::time_point t0;
    explicit ScopedStage(int s)
        : slot(s),
          active(stage_timing_state().depth > 0 &&
                 stage_timing_state().stage_suppression_depth == 0 &&
                 s >= 0 && s < STG_COUNT),
          t0(std::chrono::steady_clock::now()) {
#ifdef __ANDROID__
        if (active && ATrace_isEnabled()) {
            ATrace_beginSection(stage_name(slot));
            trace_open = true;
        }
#endif
    }
    ~ScopedStage() {
        if (!active) return;
        const double dt = std::chrono::duration<double, std::milli>(
                              std::chrono::steady_clock::now() - t0)
                              .count();
        stage_timing_state().current.stages_ms[slot] += dt;
#ifdef __ANDROID__
        if (trace_open) ATrace_endSection();
#endif
    }
    ScopedStage(const ScopedStage&) = delete;
    ScopedStage& operator=(const ScopedStage&) = delete;
#ifdef __ANDROID__
    bool trace_open = false;
#endif
};

}  // namespace spk

#endif  // SPK_RUNTIME_STAGE_TIMER_H
