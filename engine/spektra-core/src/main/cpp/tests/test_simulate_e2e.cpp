/*
 * Spektrafilm for Android — end-to-end host parity test for spk_simulate.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * Constructs an spk_params for the scan_portra case + an spk_image from the
 * deterministic scan_portra_input_rgb.f64 fixture, runs the WHOLE scan_film
 * pipeline through one spk_simulate() call, and compares the output to
 * final_rgb.spkvec — printing max_abs/rms and PASS/FAIL against the manifest
 * tolerances (max_abs <= 1e-4, rms <= 1e-5).
 *
 * It then exercises the PRINT (enlarger) route on the same fixture for TWO
 * (film, paper) pairs through the generalized native digest (neutral dichroic
 * CC resolved from neutral_print_filters.json + midgray exposure factor computed
 * natively — no baked per-pair constants):
 *   - print_portra: kodak_portra_400 -> kodak_portra_endura
 *   - print_ektar:  kodak_ektar_100  -> kodak_supra_endura
 * Each compares print_density_cmy (spk_simulate_tap) and final_rgb (spk_simulate)
 * to the committed goldens, proving the generalized print path is bit-exact for
 * an additional pair, not just the original portra case.
 *
 * Build (host) — full source set, run from the cpp root:
 *   g++ -std=c++17 -O2 -I <cpp_root> -I <tools/parity> \
 *     tests/test_simulate_e2e.cpp spektra.cpp \
 *     model/*.cpp kernels/*.cpp io/*.cpp profiles/*.cpp \
 *     runtime/params.cpp runtime/print_digest.cpp runtime/stages/*.cpp \
 *     -o /tmp/test_simulate_e2e
 * Run (golden dirs default to the repo-root /home/user/Spectrafilmandroid path;
 * argv[4] optionally overrides the goldens ROOT for a git worktree):
 *   /tmp/test_simulate_e2e <asset_dir> <scan_portra_golden_dir> <input.f64> \
 *     [goldens_root]
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "spkvec_io.h"
#include "spektra.h"

// Host-only accessors for the print-route film_density_cmy cache counters. Defined
// in spektra.cpp on the HOST build (#ifndef __ANDROID__); the host parity build
// compiles spektra.cpp directly into this binary, so they are available without any
// extra -D flag. Forward-declared here so the test can assert the cache engaged
// WITHOUT adding anything to spektra.h / the public ABI.
extern uint64_t spk_test_film_cache_hits(spk_engine* eng);
extern uint64_t spk_test_film_cache_misses(spk_engine* eng);

namespace {

const char* kAssetDir   = "/home/user/spektrafilm/src/spektrafilm/data";
const char* kGoldenDir  =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/scan_portra";
const char* kPrintGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_portra";
// Second (film, paper) pair exercising the GENERALIZED print path (native
// neutral-CC + midgray digest, no baked portra/ektar special-case): the
// kodak_ektar_100 negative printed on kodak_supra_endura paper. Driven by the
// SAME deterministic input fixture (scan_portra_input_rgb.f64 == make_test_image(64))
// the print_ektar golden was generated from.
const char* kPrintEktarGoldenDir =
    "/home/user/Spectrafilmandroid/tools/parity/goldens/print_ektar";
const char* kInputF64   =
    "/home/user/Spectrafilmandroid/engine/spektra-core/src/main/cpp/tests/"
    "scan_portra_input_rgb.f64";

// Compare a flat float buffer against a golden, print + return PASS/FAIL.
bool check(const char* label, const float* got, const std::vector<float>& gold) {
    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < gold.size(); ++i) {
        double d = std::fabs(static_cast<double>(got[i]) -
                             static_cast<double>(gold[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    double rms = std::sqrt(sse / static_cast<double>(gold.size()));
    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass = (max_abs <= tol_max_abs) && (rms <= tol_rms);
    std::printf("[%s] max_abs=%.6e (tol %.0e) rms=%.6e (tol %.0e) "
                "worst idx=%zu got=%.8f gold=%.8f -> %s\n",
                label, max_abs, tol_max_abs, rms, tol_rms, argmax,
                got[argmax], gold[argmax], pass ? "PASS" : "FAIL");
    return pass;
}

}  // namespace

int main(int argc, char** argv) {
    std::string asset_dir  = argc > 1 ? argv[1] : kAssetDir;
    std::string golden_dir = argc > 2 ? argv[2] : kGoldenDir;
    std::string input_path = argc > 3 ? argv[3] : kInputF64;
    // Optional argv[4] = goldens ROOT (the dir containing print_portra/,
    // print_ektar/, ...). When given, the print-route golden dirs are taken from
    // it; otherwise the repo-root defaults are used (CI / installed repo). This
    // lets the test run from a git worktree before the goldens land in the repo.
    std::string print_portra_dir = kPrintGoldenDir;
    std::string print_ektar_dir  = kPrintEktarGoldenDir;
    if (argc > 4) {
        std::string root = argv[4];
        print_portra_dir = root + "/print_portra";
        print_ektar_dir  = root + "/print_ektar";
    }

    spk_engine* eng = nullptr;
    spk_status st = spk_engine_create(asset_dir.c_str(), &eng);
    if (st != SPK_OK) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_status_str(st));
        return 2;
    }

    spkvec::Array gold = spkvec::read(golden_dir + "/final_rgb.spkvec");
    const int height = static_cast<int>(gold.shape[0]);
    const int width  = static_cast<int>(gold.shape[1]);
    const int npix   = width * height;
    std::printf("Image: %dx%dx3 (%d pixels)\n", width, height, npix);

    // Load the deterministic float64 input fixture, promote to float32 for the
    // C API's linear-RGB spk_image buffer.
    std::vector<double> rgb64(static_cast<size_t>(npix) * 3);
    {
        std::ifstream in(input_path, std::ios::binary);
        if (!in) { std::fprintf(stderr, "cannot open %s\n", input_path.c_str()); return 2; }
        in.read(reinterpret_cast<char*>(rgb64.data()),
                static_cast<std::streamsize>(rgb64.size() * sizeof(double)));
        if (in.gcount() != static_cast<std::streamsize>(rgb64.size() * sizeof(double))) {
            std::fprintf(stderr, "input size mismatch\n");
            return 2;
        }
    }
    std::vector<float> rgb32(rgb64.begin(), rgb64.end());

    spk_image in_img{rgb32.data(), width, height, /*color_space=*/SPK_CS_PROPHOTO};

    spk_params p{};
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    spk_default_params(&p);  // physical defaults; parity case overrides below.
    p.exposure_compensation_ev = 0.0f;
    p.auto_exposure = 0;
    p.density_curve_gamma = 1.0f;
    p.grain_active = 0;       // deterministic goldens: stochastic + spatial off.
    p.halation_active = 0;
    p.dir_couplers_active = 1;
    p.glare_active = 0;
    p.scan_film = 1;
    p.output_color_space = SPK_CS_SRGB;
    p.output_cctf_encoding = 1;
    p.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p.preview_max_size = 640;

    spk_image out{};
    st = spk_simulate(eng, &in_img, &p, &out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate failed: %s\n", spk_status_str(st));
        spk_engine_destroy(eng);
        return 2;
    }

    const size_t n = static_cast<size_t>(npix) * 3;
    if (n != gold.data.size()) {
        std::fprintf(stderr, "size mismatch: got %zu, golden %zu\n", n, gold.data.size());
        spk_image_free(&out);
        spk_engine_destroy(eng);
        return 2;
    }

    double max_abs = 0.0, sse = 0.0;
    size_t argmax = 0;
    for (size_t i = 0; i < n; ++i) {
        double d = std::fabs(static_cast<double>(out.data[i]) -
                             static_cast<double>(gold.data[i]));
        if (d > max_abs) { max_abs = d; argmax = i; }
        sse += d * d;
    }
    double rms = std::sqrt(sse / static_cast<double>(n));

    const double tol_max_abs = 1e-4, tol_rms = 1e-5;
    bool pass = (max_abs <= tol_max_abs) && (rms <= tol_rms);
    std::printf("[scan_portra final_rgb] max_abs = %.6e (tol %.0e)\n",
                max_abs, tol_max_abs);
    std::printf("[scan_portra final_rgb] rms     = %.6e (tol %.0e)\n",
                rms, tol_rms);
    std::printf("worst idx=%zu: got=%.8f golden=%.8f -> %s\n", argmax,
                out.data[argmax], gold.data[argmax], pass ? "PASS" : "FAIL");
    spk_image_free(&out);

    // --- Print (enlarger) route: same input fixture, scan_film off. ----------
    std::string print_dir = print_portra_dir;
    spkvec::Array gold_print_cmy = spkvec::read(print_dir + "/print_density_cmy.spkvec");
    spkvec::Array gold_print_rgb = spkvec::read(print_dir + "/final_rgb.spkvec");

    p.scan_film = 0;  // negative -> print -> scan route.

    bool pass_print_cmy = false, pass_print_rgb = false;

    spk_image tap_cmy{};
    st = spk_simulate_tap(eng, &in_img, &p, "print_density_cmy", &tap_cmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate_tap(print_density_cmy) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_print_cmy = check("print_portra print_density_cmy", tap_cmy.data,
                               gold_print_cmy.data);
        spk_image_free(&tap_cmy);
    }

    spk_image print_out{};
    st = spk_simulate(eng, &in_img, &p, &print_out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(print) failed: %s\n", spk_status_str(st));
    } else {
        pass_print_rgb = check("print_portra final_rgb", print_out.data,
                               gold_print_rgb.data);
        spk_image_free(&print_out);
    }

    // --- Generalized print path: a SECOND (film, paper) pair --------------
    // kodak_ektar_100 -> kodak_supra_endura through the SAME C API. The neutral
    // dichroic CC + midgray exposure factor are computed natively for this pair
    // (no baked constants), proving the generalized print route matches the
    // oracle bit-exact under the same 1e-4/1e-5 tolerances.
    std::string ektar_dir = print_ektar_dir;
    spkvec::Array gold_ektar_cmy = spkvec::read(ektar_dir + "/print_density_cmy.spkvec");
    spkvec::Array gold_ektar_rgb = spkvec::read(ektar_dir + "/final_rgb.spkvec");

    spk_params pe{};
    pe.film_profile = "kodak_ektar_100";
    pe.print_profile = "kodak_supra_endura";
    spk_default_params(&pe);  // preserves film_profile/print_profile set above.
    pe.exposure_compensation_ev = 0.0f;
    pe.auto_exposure = 0;
    pe.density_curve_gamma = 1.0f;
    pe.grain_active = 0;
    pe.halation_active = 0;
    pe.dir_couplers_active = 1;
    pe.glare_active = 0;
    pe.scan_film = 0;  // negative -> print -> scan route.
    pe.output_color_space = SPK_CS_SRGB;
    pe.output_cctf_encoding = 1;
    pe.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    pe.preview_max_size = 640;

    bool pass_ektar_cmy = false, pass_ektar_rgb = false;

    spk_image ektar_cmy{};
    st = spk_simulate_tap(eng, &in_img, &pe, "print_density_cmy", &ektar_cmy);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate_tap(print_ektar cmy) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_ektar_cmy = check("print_ektar print_density_cmy", ektar_cmy.data,
                               gold_ektar_cmy.data);
        spk_image_free(&ektar_cmy);
    }

    spk_image ektar_out{};
    st = spk_simulate(eng, &in_img, &pe, &ektar_out);
    if (st != SPK_OK) {
        std::fprintf(stderr, "spk_simulate(print_ektar) failed: %s\n",
                     spk_status_str(st));
    } else {
        pass_ektar_rgb = check("print_ektar final_rgb", ektar_out.data,
                               gold_ektar_rgb.data);
        spk_image_free(&ektar_out);
    }

    // --- Cache-hit correctness (engine profile/tc_lut PERF caches) -----------
    // A scan_portra render on the SAME, now warm-cached engine (kodak_portra_400
    // profile + tc_lut were loaded during the scan/print blocks above) must be
    // BYTE-IDENTICAL to a render on a FRESH engine (cold parse + build). This
    // guards the id-keyed caches against any cross-param staleness — exact
    // equality, not tolerance, since a cached entry is the same data as a fresh
    // parse/build (a memo, not an approximation).
    p.scan_film = 1;  // restore the scan_portra route (the print block set it 0).
    bool pass_cache = false;
    spk_image warm{}, cold{};
    spk_engine* eng_cold = nullptr;
    spk_status stw = spk_simulate(eng, &in_img, &p, &warm);
    spk_status stc = spk_engine_create(asset_dir.c_str(), &eng_cold);
    if (stc == SPK_OK) stc = spk_simulate(eng_cold, &in_img, &p, &cold);
    if (stw == SPK_OK && stc == SPK_OK && warm.data && cold.data) {
        pass_cache = (std::memcmp(warm.data, cold.data, n * sizeof(float)) == 0);
        std::printf("[cache warm==cold scan_portra] -> %s\n",
                    pass_cache ? "PASS (byte-identical)" : "FAIL");
    } else {
        std::fprintf(stderr, "cache-hit check setup failed (warm=%s cold=%s)\n",
                     spk_status_str(stw), spk_status_str(stc));
    }
    if (warm.data) spk_image_free(&warm);
    if (cold.data) spk_image_free(&cold);
    if (eng_cold) spk_engine_destroy(eng_cold);

    // --- Print-route film_density_cmy memo: HIT/MISS byte-identity ------------
    // The print route (run_print) memoizes the developed film_density_cmy in a
    // single content+param-hashed slot on the engine and skips expose+develop when
    // ONLY downstream (printing/scanning/tone-curve) params change. These scenarios
    // prove (1) a downstream-only edit on a WARM engine is BYTE-IDENTICAL to the
    // same edit on a FRESH (cold) engine AND that the cache HIT (filming reused),
    // and (2) a FILMING-side edit MISSES the cache but is still byte-identical.
    //
    // Base print-route params P0 (kodak_portra_400 -> kodak_portra_endura,
    // scan_film=0), warmed once on `eng` so the slot holds its film_density_cmy.
    auto make_p0 = []() {
        spk_params q{};
        q.film_profile = "kodak_portra_400";
        q.print_profile = "kodak_portra_endura";
        spk_default_params(&q);
        q.exposure_compensation_ev = 0.0f;
        q.auto_exposure = 0;
        q.density_curve_gamma = 1.0f;
        q.grain_active = 0;       // print route: spatial + stochastic OFF -> cache on
        q.halation_active = 0;
        q.dir_couplers_active = 1;
        q.glare_active = 0;
        q.scan_film = 0;          // negative -> print -> scan route
        q.output_color_space = SPK_CS_SRGB;
        q.output_cctf_encoding = 1;
        q.rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
        q.preview_max_size = 640;
        return q;
    };

    // Run `pp` on warm `eng`, compare byte-for-byte to a FRESH cold engine running
    // the SAME pp; returns true on exact equality (n is the scan_portra pixel count,
    // identical geometry for this fixture). Each call leaves `eng` warm.
    auto print_byte_identical = [&](const char* label, const spk_params* pp) -> bool {
        spk_image w{}, c{};
        spk_engine* ec = nullptr;
        spk_status sw = spk_simulate(eng, &in_img, pp, &w);
        spk_status sc = spk_engine_create(asset_dir.c_str(), &ec);
        if (sc == SPK_OK) sc = spk_simulate(ec, &in_img, pp, &c);
        bool ok = false;
        if (sw == SPK_OK && sc == SPK_OK && w.data && c.data) {
            ok = (std::memcmp(w.data, c.data, n * sizeof(float)) == 0);
            std::printf("[%s warm==cold] -> %s\n", label,
                        ok ? "PASS (byte-identical)" : "FAIL");
        } else {
            std::fprintf(stderr, "[%s] setup failed (warm=%s cold=%s)\n", label,
                         spk_status_str(sw), spk_status_str(sc));
        }
        if (w.data) spk_image_free(&w);
        if (c.data) spk_image_free(&c);
        if (ec) spk_engine_destroy(ec);
        return ok;
    };

    bool pass_film_cache = true;
    {
        // Warm the slot with P0 (this is a MISS that populates the cache).
        spk_params p0 = make_p0();
        spk_image warm0{};
        if (spk_simulate(eng, &in_img, &p0, &warm0) == SPK_OK && warm0.data) {
            spk_image_free(&warm0);
        }

        // Scenario A: downstream-only edits must HIT (filming reused) AND be
        // byte-identical to a fresh cold engine. Each edit changes nothing that
        // feeds filming, so the slot (still holding P0's film_density_cmy) must hit.
        struct DownEdit { const char* label; void (*apply)(spk_params*); };
        DownEdit downs[] = {
            {"film_cache A: y_filter_shift", [](spk_params* q){ q->y_filter_shift = 0.05f; }},
            {"film_cache A: output_color_space", [](spk_params* q){ q->output_color_space = SPK_CS_ADOBE_RGB; }},
            {"film_cache A: tone_curve S", [](spk_params* q){
                q->tone_curve_active = 1;
                q->tone_curve_master_n = 3;
                q->tone_curve_master_x[0] = 0.0f; q->tone_curve_master_y[0] = 0.0f;
                q->tone_curve_master_x[1] = 0.5f; q->tone_curve_master_y[1] = 0.45f;
                q->tone_curve_master_x[2] = 1.0f; q->tone_curve_master_y[2] = 1.0f;
            }},
        };
        for (const auto& d : downs) {
            // NOTE: re-warm the slot with P0 before each downstream edit so the
            // "hit" assertion is about THIS edit (the previous edit may itself have
            // populated the slot).
            spk_params pw = make_p0();
            spk_image rew{};
            if (spk_simulate(eng, &in_img, &pw, &rew) == SPK_OK && rew.data) spk_image_free(&rew);
            uint64_t hits0 = spk_test_film_cache_hits(eng);

            spk_params pe2 = make_p0();
            d.apply(&pe2);
            bool bi = print_byte_identical(d.label, &pe2);
            uint64_t hits1 = spk_test_film_cache_hits(eng);
            // These are genuine downstream-only edits: nothing feeding filming
            // changes, so the slot (still holding P0's film_density_cmy) must HIT.
            bool hit_ok = (hits1 > hits0);
            std::printf("[%s cache-hit] hits %llu->%llu -> %s\n", d.label,
                        (unsigned long long)hits0, (unsigned long long)hits1,
                        hit_ok ? "PASS" : "FAIL");
            pass_film_cache = pass_film_cache && bi && hit_ok;
        }

        // Scenario A (bypass): enabling the halation spatial master toggle trips the
        // spektra.cpp cache guard (bypass when `halation_active || grain_active`), so
        // the film_density_cmy cache machinery is NOT consulted at all. scanner_unsharp
        // only takes effect once the spatial branch is on, so this case necessarily
        // sets halation_active=1 — meaning it exercises the BYPASS path, not a cache
        // hit. On a true bypass the cache block is skipped entirely, so NEITHER the
        // hit NOR the miss counter moves; the output must still be byte-identical to a
        // fresh cold engine.
        {
            const char* label = "film_cache A: scanner_unsharp(+halation) cache-bypass";
            // Re-warm the slot with P0 so a hit would be possible if the guard were
            // absent; the bypass must leave both counters untouched regardless.
            spk_params pw = make_p0();
            spk_image rew{};
            if (spk_simulate(eng, &in_img, &pw, &rew) == SPK_OK && rew.data) spk_image_free(&rew);
            uint64_t hits0 = spk_test_film_cache_hits(eng);
            uint64_t miss0 = spk_test_film_cache_misses(eng);

            spk_params pe2 = make_p0();
            pe2.halation_active = 1;            // bypass trigger (spatial branch ON)
            pe2.scanner_unsharp[0] = 1.0f; pe2.scanner_unsharp[1] = 0.5f;
            bool bi = print_byte_identical(label, &pe2);
            uint64_t hits1 = spk_test_film_cache_hits(eng);
            uint64_t miss1 = spk_test_film_cache_misses(eng);
            // Bypass semantics: cache untouched (no hit AND no miss), byte-identical.
            bool bypass_ok = (hits1 == hits0) && (miss1 == miss0);
            std::printf("[%s] hits %llu->%llu misses %llu->%llu -> %s\n", label,
                        (unsigned long long)hits0, (unsigned long long)hits1,
                        (unsigned long long)miss0, (unsigned long long)miss1,
                        bypass_ok ? "PASS" : "FAIL");
            pass_film_cache = pass_film_cache && bi && bypass_ok;
        }

        // Scenario B: FILMING-side edits must MISS (filming recomputed) but still be
        // byte-identical to a fresh cold engine.
        struct FilmEdit { const char* label; void (*apply)(spk_params*); };
        FilmEdit films[] = {
            {"film_cache B: exposure_compensation_ev", [](spk_params* q){ q->exposure_compensation_ev = 0.7f; }},
            {"film_cache B: density_curve_gamma", [](spk_params* q){ q->density_curve_gamma = 0.9f; }},
            {"film_cache B: dir_amount", [](spk_params* q){ q->dir_amount = 0.5f; }},
            {"film_cache B: film_profile", [](spk_params* q){
                q->film_profile = "kodak_ektar_100";
                q->print_profile = "kodak_supra_endura";
            }},
        };
        for (const auto& f : films) {
            // Re-warm with P0 so the slot holds P0's filming output; the edit then
            // changes a filming input and must MISS.
            spk_params pw = make_p0();
            spk_image rew{};
            if (spk_simulate(eng, &in_img, &pw, &rew) == SPK_OK && rew.data) spk_image_free(&rew);
            uint64_t miss0 = spk_test_film_cache_misses(eng);

            spk_params pe2 = make_p0();
            f.apply(&pe2);
            bool bi = print_byte_identical(f.label, &pe2);
            uint64_t miss1 = spk_test_film_cache_misses(eng);
            bool miss_ok = (miss1 > miss0);
            std::printf("[%s cache-miss] misses %llu->%llu -> %s\n", f.label,
                        (unsigned long long)miss0, (unsigned long long)miss1,
                        miss_ok ? "PASS" : "FAIL");
            pass_film_cache = pass_film_cache && bi && miss_ok;
        }
    }

    spk_engine_destroy(eng);
    bool all = pass && pass_print_cmy && pass_print_rgb &&
               pass_ektar_cmy && pass_ektar_rgb && pass_cache && pass_film_cache;
    std::printf("%s\n", all ? "ALL PASS" : "FAIL");
    return all ? 0 : 1;
}
