/*
 * Spektrafilm for Android -- banded vs WHOLE-frame seam sweep. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A banded export must match the whole-frame render of the same frame. #604
 * shipped seams at the film-pass core boundaries of 50 MP exports (a ramp over
 * a few rows, then a 70-90 level drop) because a filming-stage spatial filter's
 * reach exceeded the film margin; its own test compared film-density reuse with
 * recompute, which share the partition, so it could not see it. This sweep
 * renders each config whole and banded (film-density reuse on, so the film and
 * print passes use different partitions) across the reach space -- halation,
 * camera diffusion incl. the near/far split, highlight boost, DIR diffusion,
 * lens blur, date-back, engine crop -- and fails on any row whose mean
 * |banded - whole| exceeds the bar. Builds on the CPU route, and with
 * SPK_ENABLE_VULKAN on the GPU route (gpu_export); the run reports which.
 * One grain-on config records the grain realisation restart at band
 * boundaries as a measured bound, not a pass/fail.
 */
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "spektra.h"

namespace {

constexpr int kW = 960;
constexpr int kH = 1440;
constexpr int64_t kNeverBand = static_cast<int64_t>(1) << 40;

int g_failures = 0;

void expect(bool ok, const std::string& what) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) ++g_failures;
}

std::vector<float> make_frame() {
    std::vector<float> f(static_cast<size_t>(kW) * kH * 3);
    for (int y = 0; y < kH; ++y)
        for (int x = 0; x < kW; ++x) {
            const double u = static_cast<double>(x) / kW, v = static_cast<double>(y) / kH;
            double r = 0.05 + 0.25 * u + 0.05 * std::sin(0.11 * x) * std::cos(0.07 * y);
            double g = 0.05 + 0.25 * v + 0.04 * std::sin(0.05 * (x + y));
            double b = 0.08 + 0.15 * (1.0 - u) * v;
            if (((x / 120) + (y / 150)) % 2 == 0) { r *= 0.4; g *= 0.5; b *= 0.6; }
            // Bright lamps on and around likely band boundaries.
            for (int ly = 90; ly < kH; ly += 180) {
                const int lx = 120 + (ly * 7) % (kW - 240);
                const double dx = x - lx, dy = y - ly;
                if (dx * dx + dy * dy < 30.0 * 30.0) { r += 12.0; g += 10.0; b += 7.0; }
            }
            float* p = &f[(static_cast<size_t>(y) * kW + x) * 3];
            p[0] = static_cast<float>(r);
            p[1] = static_cast<float>(g);
            p[2] = static_cast<float>(b);
        }
    return f;
}

struct Config {
    const char* name;
    bool big_halation, camera_diffusion, boost, big_dir, big_lens, crop, near_far, grain;
};

void params_for(const Config& c, spk_params* p) {
    spk_default_params(p);
    p->film_profile = "kodak_portra_400";
    p->print_profile = "kodak_portra_endura";
    p->disable_buffer_memos = 1;
    p->gpu_export = 1;  // the GPU route where the build has one; ignored otherwise
    p->grain_active = c.grain ? 1 : 0;
    p->glare_active = 0;
    p->print_glare_active = 0;
    p->auto_exposure = 1;
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    p->film_format_mm = 30.0f;  // ~21 um pixels: several bands on this frame
    p->halation_active = 1;
    p->dir_couplers_active = 1;
    p->lens_blur_um = c.big_lens ? 200.0f : 40.0f;
    p->date_stamp_active = 1;
    p->date_stamp_text = "'26 11 10";
    p->date_stamp_corner = 1;
    p->date_stamp_height = 0.12f;
    // Mask + enlarger diffusion: the film pass and the print passes partition
    // the frame differently (film-density reuse).
    p->contrast_mask_active = 1;
    p->contrast_mask_highlights = 1.85f;
    p->contrast_mask_shadows = 0.5f;
    p->enlarger_diffusion_active = 1;
    p->enlarger_diffusion_strength = 0.5f;
    if (c.big_halation) {
        p->halation_scatter_spatial_scale *= 3.0f;
        p->halation_halation_spatial_scale *= 3.0f;
        for (int k = 0; k < 3; ++k) {
            p->halation_strength[k] = std::min(1.0f, p->halation_strength[k] * 3.0f + 0.05f);
            p->halation_first_sigma_um[k] *= 2.0f;
        }
    }
    if (c.camera_diffusion) {
        p->camera_diffusion_active = 1;
        p->camera_diffusion_strength = 1.0f;
        p->camera_diffusion_spatial_scale = 3.0f;
    }
    if (c.boost) p->halation_boost_ev = 4.0f;
    if (c.big_dir) {
        p->dir_diffusion_size_um *= 1.5f;
        p->dir_diffusion_tail_um *= 1.5f;
    }
    if (c.crop) {
        p->crop = 1;
        p->crop_center[0] = 0.47f; p->crop_center[1] = 0.53f;
        p->crop_size[0] = 0.9f; p->crop_size[1] = 0.9f;
    }
}

bool render(spk_engine* eng, const std::vector<float>& frame, const spk_params& p,
            int64_t min_pixels, int64_t band_pixels, std::vector<float>* out, int* ow, int* oh) {
    spk_set_band_render_limits(min_pixels, band_pixels);
    spk_image in{const_cast<float*>(frame.data()), kW, kH, SPK_CS_PROPHOTO};
    spk_image o{};
    const spk_status st = spk_simulate(eng, &in, &p, &o);
    spk_set_band_render_limits(0, 0);
    if (st != SPK_OK) {
        std::printf("render failed: status %d\n", st);
        spk_image_free(&o);
        return false;
    }
    *ow = o.width;
    *oh = o.height;
    out->assign(o.data, o.data + static_cast<size_t>(o.width) * o.height * 3);
    spk_image_free(&o);
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    const char* assets = argc > 1 ? argv[1] : "../assets/spektra";
    spk_engine* eng = nullptr;
    if (spk_engine_create(assets, &eng) != SPK_OK) {
        std::printf("engine create failed\n");
        return 2;
    }
    const std::vector<float> frame = make_frame();
    const Config configs[] = {
        {"base (mask + enlarger, date-back)", false, false, false, false, false, false, false, false},
        {"big halation", true, false, false, false, false, false, false, false},
        {"camera diffusion", false, true, false, false, false, false, false, false},
        {"highlight boost 4 EV", false, false, true, false, false, false, false, false},
        {"big DIR diffusion", false, false, false, true, false, false, false, false},
        {"big lens blur", false, false, false, false, true, false, false, false},
        {"crop + halation + boost + lens blur", true, false, true, false, true, true, false, false},
        {"all, smaller bands", true, true, true, true, true, false, true, false},
        {"crop + halation + boost + lens blur + grain (measured)", true, false, true, false, true, true, false, true},
    };
    for (const Config& c : configs) {
        spk_params p;
        params_for(c, &p);
        // The reference renders whole (no frame limit). The banded render needs a
        // frame limit below the frame: with the enlarger filter on (which is what
        // splits the film and print partitions) the diffusion filters can only
        // band through their near/far split, so the bar is F2's bound for that
        // split -- three orders of magnitude below a seam.
        spk_set_band_film_reuse(1);
        std::vector<float> whole;
        int ww = 0, wh = 0;
        spk_set_whole_frame_pixel_limit(0);
        if (!render(eng, frame, p, kNeverBand, 0, &whole, &ww, &wh)) {
            expect(false, std::string(c.name) + ": whole-frame render succeeds");
            continue;
        }
        // Several band heights, so the band partitions (and
        // their core boundaries) land in different places; a height whose margin
        // does not fit is refused by the planner and skipped.
        int banded_runs = 0;
        for (const int band_rows : {450, 650, 900, 1200}) {
            std::vector<float> banded;
            int bw = 0, bh = 0;
            const uint64_t bands0 = spk_band_render_bands();
            const uint64_t print_raw0 = spk_band_print_raw_passes();
            spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH - 1);
            const bool okb = render(eng, frame, p, 1, static_cast<int64_t>(kW) * band_rows, &banded, &bw, &bh);
            const uint64_t print_raw_used = spk_band_print_raw_passes() - print_raw0;
            // Print-integral reuse must not move a byte: the same banded render
            // with it off recomputes the integral in every pass.
            std::vector<float> banded_off;
            int bw_off = 0, bh_off = 0;
            spk_set_band_print_raw_reuse(0);
            const bool okb_off = okb && render(eng, frame, p, 1, static_cast<int64_t>(kW) * band_rows,
                                               &banded_off, &bw_off, &bh_off);
            spk_set_band_print_raw_reuse(1);
            spk_set_whole_frame_pixel_limit(0);
            if (!okb) {
                std::printf("  %s: %d-row bands refused (margin does not fit), skipped\n", c.name, band_rows);
                continue;
            }
            const uint64_t final_bands = spk_band_render_bands() - bands0;
            if (bw != ww || bh != wh || final_bands == 0) {
                expect(false, std::string(c.name) + ": banded render has the whole render's size and bands");
                continue;
            }
            ++banded_runs;
            expect(okb_off && banded_off.size() == banded.size() &&
                       std::memcmp(banded_off.data(), banded.data(), banded.size() * sizeof(float)) == 0,
                   std::string(c.name) + " @" + std::to_string(band_rows) +
                       " rows: print-integral reuse is byte-identical to recomputing it (reuse " +
                       (print_raw_used ? "engaged" : "not engaged") + ")");
            // On the GPU route every config here has a film-side store pass, so
            // the reuse must have engaged.
            if (spk_gpu_print_state() == 1) {
                expect(print_raw_used > 0, std::string(c.name) + " @" + std::to_string(band_rows) +
                                               " rows: print-integral reuse engaged on the GPU route");
                // A band whose integral did not run on the GPU makes the pass
                // redo itself storing film density: same bytes, no reuse.
                if (band_rows == 650) {
                    std::vector<float> banded_fault;
                    int bwf = 0, bhf = 0;
                    const uint64_t fb0 = spk_band_print_raw_fallbacks();
                    const uint64_t pr1 = spk_band_print_raw_passes();
                    spk_test_set_print_raw_fault_band(2);
                    spk_set_whole_frame_pixel_limit(static_cast<int64_t>(kW) * kH - 1);
                    const bool okf = render(eng, frame, p, 1, static_cast<int64_t>(kW) * band_rows,
                                            &banded_fault, &bwf, &bhf);
                    spk_set_whole_frame_pixel_limit(0);
                    spk_test_set_print_raw_fault_band(0);
                    expect(okf && spk_band_print_raw_fallbacks() == fb0 + 1 &&
                               spk_band_print_raw_passes() == pr1 && banded_fault.size() == banded.size() &&
                               std::memcmp(banded_fault.data(), banded.data(),
                                           banded.size() * sizeof(float)) == 0,
                           std::string(c.name) + " @650 rows: a band off the GPU falls back, same bytes");
                }
            }
            // Test hook: a NaN in the banded frame must fail the gate.
            if (std::getenv("SPK_SEAM_SWEEP_INJECT_NAN")) banded[banded.size() / 2] = std::nanf("");
            // Non-finite samples are counted, not accumulated: std::max and the
            // row-mean comparison both pass NaN through silently.
            size_t nonfinite = 0;
            double worst_row = 0.0, sse = 0.0, max_abs = 0.0;
            int worst_y = -1;
            for (int y = 0; y < bh; ++y) {
                double sum = 0.0;
                for (int i = 0; i < bw * 3; ++i) {
                    const size_t k = static_cast<size_t>(y) * bw * 3 + i;
                    const double d = std::fabs(static_cast<double>(banded[k]) - whole[k]);
                    if (!std::isfinite(banded[k]) || !std::isfinite(whole[k]) || !std::isfinite(d)) {
                        ++nonfinite;
                        continue;
                    }
                    sum += d;
                    sse += d * d;
                    max_abs = std::max(max_abs, d);
                }
                sum /= bw * 3;
                if (!std::isfinite(sum)) ++nonfinite;
                else if (sum > worst_row) { worst_row = sum; worst_y = y; }
            }
            const double rms = std::sqrt(sse / static_cast<double>(banded.size()));
            if (!std::isfinite(rms)) ++nonfinite;
            std::printf("%s @%d rows: bands %llu; max_abs %.3e rms %.3e worst row %d mean %.3e nonfinite %zu\n",
                        c.name, band_rows, static_cast<unsigned long long>(final_bands), max_abs, rms,
                        worst_y, worst_row, nonfinite);
            expect(nonfinite == 0, std::string(c.name) + " @" + std::to_string(band_rows) +
                                       " rows: every sample finite in both renders");
            if (c.grain) {
                std::printf("  grain bound (measured, not gated): worst row mean %.3e\n", worst_row);
                continue;
            }
            // A seam moves a whole row by tens of levels; the bar is a row mean far
            // below one 8-bit level, at F2's bound for the near/far split.
            expect(worst_row <= 5e-4 && max_abs <= 2e-3,
                   std::string(c.name) + " @" + std::to_string(band_rows) + " rows: banded matches whole (no seam rows)");
        }
        expect(banded_runs > 0, std::string(c.name) + ": renders banded at least once");
    }
    // 1 = the GPU print/scan routes passed their self-checks and ran (engaged=1);
    // 0 = never engaged (a CPU build, or no device).
    std::printf("route: gpu print state %d, gpu scan state %d -> engaged=%d\n", spk_gpu_print_state(),
                spk_gpu_scan_state(), spk_gpu_print_state() == 1 ? 1 : 0);
    spk_engine_destroy(eng);
    std::printf(g_failures ? "test_band_seam_sweep: FAIL (%d)\n" : "test_band_seam_sweep: ALL OK\n",
                g_failures);
    return g_failures ? 1 : 0;
}
