/*
 * SpectraFilm for Android — native engine entry (capstone wiring).
 * GPLv3. Port of spektrafilm (GPLv3) — film modeling powered by spektrafilm.
 *
 * Implements the C API for the scan_film route by orchestrating the already-ported,
 * bit-exact stages:
 *   spk_simulate(scan_film) :
 *     load film profile -> build_filming_tc_lut -> expose -> develop(+couplers)
 *     -> scan -> display RGB (output_color_space, CCTF per params).
 *
 * The print (enlarger) route works for ANY (film, paper) pair: the neutral
 * dichroic CC values are resolved natively from neutral_print_filters.json and
 * the midgray exposure factor is computed natively (runtime/print_digest).
 *
 * Honoured spk_params for the scan_film parity case (scan_portra defaults):
 *   - film_profile          -> profile JSON loaded from <asset_dir>/profiles/<id>.json
 *   - scan_film             -> selects this route (false => print route, TODO M4)
 *   - exposure_compensation_ev -> FilmingParams.exposure_compensation_ev
 *   - density_curve_gamma   -> FilmingParams.density_curve_gamma (broadcast to CMY)
 *   - output_color_space    -> ScanningParams.output_color_space (all six spaces:
 *                              sRGB, Adobe RGB, ProPhoto, Rec.2020, ACES2065-1,
 *                              linear sRGB; per-space XYZ->RGB matrix + CCTF).
 *   - output_cctf_encoding  -> ScanningParams.output_cctf_encoding
 *   - preview_max_size      -> used by spk_simulate_preview for the downscale target.
 *
 * Honoured stochastic/spatial toggles:
 *   - halation_active -> spatial-effects branch (in-emulsion scatter + halation +
 *     DIR-coupler diffusion + scanner unsharp).
 *   - grain_active    -> stochastic AgX particle grain (model/grain.cpp), applied
 *     to the post-coupler film density. Off by default (the deterministic goldens).
 *   - auto_exposure / glare remain off (baked defaults) for the parity routes.
 */
#include "spektra.h"

#include <cmath>
#include <cstring>
#include <dirent.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "io/npy_lut.h"
#include "profiles/profile.h"
#include "runtime/params.h"
#include "runtime/print_digest.h"
#include "runtime/stages/filming.h"
#include "runtime/stages/printing.h"
#include "runtime/stages/scanning.h"

namespace {

// D55 standard illuminant (colour SDS_ILLUMINANTS['D55']) aligned to the
// 380..780 @5nm working shape, normalised by mean — the film reference
// illuminant for the bundled negative profiles. Baked at full double precision
// from the Python oracle, identical to tests/test_filming.cpp.
const double kD55Illuminant[SPK_SPECTRAL_SAMPLES] = {
    0.3792826592081565,0.41130471283820924,0.4433384066186183,0.5763969653409493,
    0.7094555240632804,0.7537113757177554,0.7979788675225865,0.8155671347108852,
    0.8331670420495402,0.8118539267472403,0.7905291712945844,0.8934979413460009,
    0.996455071247061,1.0685541625536938,1.1406532538603265,1.1550288395502992,
    1.169404425240272,1.1662033838923025,1.1630023425443328,1.1794498749977185,
    1.1958974074511046,1.1687758571210345,1.141642666640608,1.1567865022540935,
    1.1719303378675792,1.172023459070429,1.1721049401229227,1.167984326896809,
    1.1638637136706955,1.1884360710727462,1.213020068625153,1.2007513501496623,
    1.1884826316741712,1.1935228167784289,1.1985630018826865,1.1812890187540066,
    1.1640150356253267,1.1478119463294223,1.1316088570335177,1.134705137028281,
    1.1378130571734006,1.1010418221979967,1.0642822273729489,1.0816726120051912,
    1.0990513564870772,1.1032534507656848,1.107443904893936,1.1020894357300595,
    1.096734966566183,1.0747816429942894,1.0528283194223955,1.0637817009076298,
    1.0747350823928643,1.054504501073696,1.0342739197545279,1.0427945098153053,
    1.0513034597257263,1.0724419727726824,1.0935921259699946,1.0703467457085567,
    1.047101365447119,0.9872826327663333,0.9274522599351918,0.945855337648428,
    0.9642700555120207,0.9759334861689865,0.9875969168259522,0.9025656184735221,
    0.8175459602714483,0.8703107618363444,0.9230755634012404,0.9562034313151373,
    0.989331299229034,0.9130184734934376,0.8366940076074848,0.72561205275776,
    0.6145184577576788,0.7491600769284603,0.883801696099242,0.8598811871171415,
    0.8359723182853972};

std::string join_path(const std::string& a, const std::string& b) {
    if (a.empty()) return b;
    if (a.back() == '/') return a + b;
    return a + "/" + b;
}

bool ends_with(const std::string& s, const std::string& suf) {
    return s.size() >= suf.size() &&
           s.compare(s.size() - suf.size(), suf.size(), suf) == 0;
}

}  // namespace

// Engine: holds asset paths and lazily caches the spectra LUT (shared across calls).
struct spk_engine {
    std::string asset_dir;        // root containing profiles/ and luts/
    std::string profiles_dir;     // <asset_dir>/profiles
    std::string spectra_lut_path; // <asset_dir>/luts/spectral_upsampling/irradiance_xy_tc.npy
    std::string neutral_filters_path; // <asset_dir>/filters/neutral_print_filters.json

    std::mutex lut_mutex;
    bool lut_loaded = false;
    spk::NdArray spectra_lut;

    const spk::NdArray& spectra() {
        std::lock_guard<std::mutex> g(lut_mutex);
        if (!lut_loaded) {
            spectra_lut = spk::load_npy(spectra_lut_path);
            lut_loaded = true;
        }
        return spectra_lut;
    }
};

namespace {

// Apply the user-controllable DIR-coupler params from spk_params onto the
// digested struct. The per-channel gamma matrices stay film-specific (baked by
// digest_filming_params, mirroring _apply_film_specifics which overwrites them
// regardless of user input), so they are NOT taken from spk_params. The
// diffusion fields are only meaningful in the spatial branch (the digest already
// zeroes them when spatial is off, matching deactivate_spatial_effects). All
// values default to the schema defaults, so default params are bit-exact.
void apply_user_dir_couplers(spk::DirCouplersParams& dc, const spk_params* p,
                             bool spatial) {
    dc.active = (p->dir_couplers_active != 0);
    dc.amount = p->dir_amount;
    dc.inhibition_samelayer = p->dir_inhibition_samelayer;
    dc.inhibition_interlayer = p->dir_inhibition_interlayer;
    if (spatial) {
        dc.diffusion_size_um = p->dir_diffusion_size_um;
        dc.diffusion_tail_um = p->dir_diffusion_tail_um;
        dc.diffusion_tail_weight = p->dir_diffusion_tail_weight;
    }
}

// Apply the user-controllable halation params from spk_params. The preset-driven
// fields (halation_first_sigma_um and halation_strength) stay baked by
// digest_halation_params (mirroring _apply_halation_preset, which overwrites them
// from the film's use/antihalation tags regardless of user input). Everything
// else (scatter geometry, amounts, boost/protect, bounce model) is user-driven.
void apply_user_halation(spk::HalationParams& h, const spk_params* p) {
    h.boost_ev = p->halation_boost_ev;
    h.boost_range = p->halation_boost_range;
    h.protect_ev = p->halation_protect_ev;
    h.scatter_amount = p->halation_scatter_amount;
    h.scatter_spatial_scale = p->halation_scatter_spatial_scale;
    h.scatter_core_um[0] = p->halation_scatter_core_um[0];
    h.scatter_core_um[1] = p->halation_scatter_core_um[1];
    h.scatter_core_um[2] = p->halation_scatter_core_um[2];
    h.scatter_tail_um[0] = p->halation_scatter_tail_um[0];
    h.scatter_tail_um[1] = p->halation_scatter_tail_um[1];
    h.scatter_tail_um[2] = p->halation_scatter_tail_um[2];
    h.scatter_tail_weight[0] = p->halation_scatter_tail_weight[0];
    h.scatter_tail_weight[1] = p->halation_scatter_tail_weight[1];
    h.scatter_tail_weight[2] = p->halation_scatter_tail_weight[2];
    h.halation_amount = p->halation_halation_amount;
    h.halation_spatial_scale = p->halation_halation_spatial_scale;
    h.halation_n_bounces = p->halation_n_bounces;
    h.halation_bounce_decay = p->halation_bounce_decay;
    h.halation_renormalize = (p->halation_renormalize != 0);
}

// Apply the full user grain params from spk_params onto the digested struct.
// density_max_curves / density_max_layers stay film-derived (filled by develop()
// from the profile's density curves). All other fields are user-driven and equal
// the schema defaults for a default-constructed SpektraParams.
void apply_user_grain(spk::GrainParams& g, const spk_params* p) {
    g.sublayers_active = (p->grain_sublayers_active != 0);
    g.agx_particle_area_um2 = p->grain_particle_area_um2;
    g.agx_particle_scale[0] = p->grain_particle_scale[0];
    g.agx_particle_scale[1] = p->grain_particle_scale[1];
    g.agx_particle_scale[2] = p->grain_particle_scale[2];
    g.agx_particle_scale_layers[0] = p->grain_particle_scale_layers[0];
    g.agx_particle_scale_layers[1] = p->grain_particle_scale_layers[1];
    g.agx_particle_scale_layers[2] = p->grain_particle_scale_layers[2];
    g.density_min[0] = p->grain_density_min[0];
    g.density_min[1] = p->grain_density_min[1];
    g.density_min[2] = p->grain_density_min[2];
    g.uniformity[0] = p->grain_uniformity[0];
    g.uniformity[1] = p->grain_uniformity[1];
    g.uniformity[2] = p->grain_uniformity[2];
    g.blur = p->grain_blur;
    g.blur_dye_clouds_um = p->grain_blur_dye_clouds_um;
    g.micro_structure[0] = p->grain_micro_structure[0];
    g.micro_structure[1] = p->grain_micro_structure[1];
    g.n_sub_layers = p->grain_n_sub_layers > 0 ? p->grain_n_sub_layers : 1;
}

// Run the scan_film pipeline, producing display RGB plus the intermediate taps.
// `tap_*` pointers, when non-null, receive the corresponding intermediate.
spk_status run_scan_film(spk_engine* eng, const spk_image* in, const spk_params* p,
                         std::vector<float>* final_rgb,
                         std::vector<float>* tap_log_raw,
                         std::vector<float>* tap_density_cmy) {
    if (!eng || !in || !p || !in->data) return SPK_ERR_BAD_ARGS;
    if (in->width <= 0 || in->height <= 0) return SPK_ERR_BAD_ARGS;
    if (!p->film_profile) return SPK_ERR_BAD_ARGS;

    const int width = in->width;
    const int height = in->height;
    const int npix = width * height;

    // 1) Load the film profile.
    spk::Profile film;
    try {
        std::string path = join_path(eng->profiles_dir,
                                     std::string(p->film_profile) + ".json");
        film = spk::load_profile_file(path);
    } catch (const std::exception&) {
        return SPK_ERR_PROFILE_NOT_FOUND;
    }
    if (film.log_sensitivity.empty() || film.log_exposure.empty() ||
        film.window_params.size() < 4) {
        return SPK_ERR_INTERNAL;  // profile lacks filming fields
    }

    // 2) Digested filming params (auto-exposure off; stochastic/grain off). The
    //    spatial-effects branch (halation + in-emulsion scatter + DIR-coupler
    //    diffusion) is enabled when the case requests it via halation_active
    //    (mirroring deactivate_spatial_effects=False under scan_portra_spatial).
    const bool spatial = (p->halation_active != 0);
    const bool grain = (p->grain_active != 0);
    spk::FilmingParams fparams =
        spk::digest_filming_params(film.is_negative(), spatial);
    fparams.exposure_compensation_ev = p->exposure_compensation_ev;
    const float g = p->density_curve_gamma != 0.0f ? p->density_curve_gamma : 1.0f;
    fparams.density_curve_gamma[0] = g;
    fparams.density_curve_gamma[1] = g;
    fparams.density_curve_gamma[2] = g;
    // DIR-coupler user params (amount / inhibition / diffusion); the per-channel
    // gamma matrices stay film-specific (baked by the digest).
    apply_user_dir_couplers(fparams.dir_couplers, p, spatial);
    // pixel_size_um drives both the spatial kernels and the grain blur, so it
    // must be set whenever either spatial effects or grain are active.
    if (spatial || grain) {
        const double fmm = p->film_format_mm > 0.0f ? p->film_format_mm : 35.0;
        const int longest = width > height ? width : height;
        fparams.pixel_size_um = fmm * 1000.0 / static_cast<double>(longest);
    }
    if (grain) {
        // grain_active && stochastic effects on -> AgX particle grain. The
        // density_max_curves are filled inside develop() from the film's
        // normalized density curves.
        spk::digest_grain_params(fparams);
        apply_user_grain(fparams.grain, p);
    }
    if (spatial) {
        // pixel_size_um = film_format_mm * 1000 / max(width, height) for the
        // parity image (square, no crop). Matches resize_service.pixel_size_um.
        const double fmm = p->film_format_mm > 0.0f ? p->film_format_mm : 35.0;
        const int longest = width > height ? width : height;
        fparams.pixel_size_um = fmm * 1000.0 / static_cast<double>(longest);
        spk::digest_halation_params(fparams, film.use.c_str(),
                                    film.antihalation.c_str(), true);
        // halation user params (everything except the preset-baked sigma/strength).
        apply_user_halation(fparams.halation, p);
    }

    // 3) Build the Hanatos2025 filming tc_lut (D55 reference illuminant).
    spk::NdArray tc_lut;
    try {
        tc_lut = spk::build_filming_tc_lut(film, eng->spectra(), kD55Illuminant);
    } catch (const std::exception&) {
        return SPK_ERR_ASSET_IO;
    }

    // 4) expose(): the image runs as float64 (ProPhoto linear). Promote the
    //    incoming float32 RGB to double, matching the Python pipeline.
    std::vector<double> rgb(static_cast<size_t>(npix) * 3);
    for (size_t i = 0; i < rgb.size(); ++i)
        rgb[i] = static_cast<double>(in->data[i]);

    std::vector<float> log_raw(static_cast<size_t>(npix) * 3);
    spk::expose(rgb.data(), width, height, fparams, tc_lut, log_raw.data());
    if (tap_log_raw) *tap_log_raw = log_raw;

    // 5) develop(): log_raw -> density_cmy (+ DIR couplers, spatial diffusion if on).
    std::vector<float> density_cmy(static_cast<size_t>(npix) * 3);
    spk::develop(log_raw.data(), width, height, film, fparams, density_cmy.data());
    if (tap_density_cmy) *tap_density_cmy = density_cmy;

    if (!final_rgb) return SPK_OK;  // caller only wanted an earlier tap

    // 6) scan(): density_cmy -> display RGB (output_color_space, CCTF per params).
    spk::ScanningParams sparams;
    sparams.scan_film = true;
    sparams.output_color_space = p->output_color_space;
    sparams.output_cctf_encoding = (p->output_cctf_encoding != 0);
    if (spatial) {
        // scanner.unsharp_mask = (sigma, amount); default (0.7, 0.7). lens_blur
        // is not modelled here (0 under the goldens).
        sparams.unsharp_sigma = p->scanner_unsharp[0];
        sparams.unsharp_amount = p->scanner_unsharp[1];
    }

    final_rgb->assign(static_cast<size_t>(npix) * 3, 0.0f);
    spk::scan(film, sparams, density_cmy.data(), width, height, final_rgb->data());
    return SPK_OK;
}

// Run the negative -> print -> scan route, producing display RGB plus the
// intermediate taps. `tap_*` pointers, when non-null, receive the corresponding
// intermediate. Mirrors pipeline._pipeline_print under the print_portra toggles.
spk_status run_print(spk_engine* eng, const spk_image* in, const spk_params* p,
                     std::vector<float>* final_rgb,
                     std::vector<float>* tap_log_raw,
                     std::vector<float>* tap_film_density_cmy,
                     std::vector<float>* tap_print_density_cmy) {
    if (!eng || !in || !p || !in->data) return SPK_ERR_BAD_ARGS;
    if (in->width <= 0 || in->height <= 0) return SPK_ERR_BAD_ARGS;
    if (!p->film_profile || !p->print_profile) return SPK_ERR_BAD_ARGS;

    const int width = in->width, height = in->height, npix = width * height;

    // 1) Load film + print profiles.
    spk::Profile film, prnt;
    try {
        film = spk::load_profile_file(
            join_path(eng->profiles_dir, std::string(p->film_profile) + ".json"));
        prnt = spk::load_profile_file(
            join_path(eng->profiles_dir, std::string(p->print_profile) + ".json"));
    } catch (const std::exception&) {
        return SPK_ERR_PROFILE_NOT_FOUND;
    }
    if (film.log_sensitivity.empty() || film.log_exposure.empty() ||
        film.window_params.size() < 4) {
        return SPK_ERR_INTERNAL;  // film profile lacks filming fields
    }
    if (prnt.log_sensitivity.empty() || prnt.log_exposure.empty() ||
        prnt.density_curves.empty()) {
        return SPK_ERR_INTERNAL;  // print profile lacks printing fields
    }

    // 2) Build the Hanatos2025 filming tc_lut (D55 reference illuminant). Needed
    //    both by the filming expose and by the native midgray digest below.
    spk::NdArray tc_lut;
    try {
        tc_lut = spk::build_filming_tc_lut(film, eng->spectra(), kD55Illuminant);
    } catch (const std::exception&) {
        return SPK_ERR_ASSET_IO;
    }

    // 3) Native print digest for ANY (film, paper) pair:
    //    (a) neutral dichroic CC resolved from neutral_print_filters.json
    //        ([print_stock][illuminant][film_stock]); missing triples fall back
    //        to the schema defaults {0,0,0}, mirroring params_builder.py.
    //    (b) midgray exposure factor computed natively from the filming midgray
    //        balance + the print sensitivity/filtered illuminant.
    const double* enl = spk::enlarger_illuminant("TH-KG3");
    if (!enl) return SPK_ERR_INTERNAL;
    const std::string film_stock  = !film.stock.empty() ? film.stock : p->film_profile;
    const std::string print_stock = !prnt.stock.empty() ? prnt.stock : p->print_profile;
    double neutral_cc[3];
    if (p->neutral_print_filters_from_database) {
        spk::resolve_neutral_cc(eng->neutral_filters_path, print_stock, "TH-KG3",
                                film_stock, neutral_cc);
    } else {
        // Use the schema neutral CC values directly (filter_enlarger_source uses
        // [c_filter_neutral, m_filter_neutral, y_filter_neutral] in CMY order).
        neutral_cc[0] = p->c_filter_neutral;
        neutral_cc[1] = p->m_filter_neutral;
        neutral_cc[2] = p->y_filter_neutral;
    }
    // filtered_illuminant CC = [c_neutral, m_neutral + m_shift, y_neutral + y_shift]
    // (filter_enlarger_source.filtered_illuminant). Shifts default to 0, so the
    // resolved neutral CC is used unchanged under the parity defaults.
    neutral_cc[1] += static_cast<double>(p->m_filter_shift);
    neutral_cc[2] += static_cast<double>(p->y_filter_shift);

    // Print density-curve gamma is independent of the film gamma.
    const float pg = p->print_density_curve_gamma != 0.0f ? p->print_density_curve_gamma : 1.0f;
    // Build the filtered illuminant first (color_enlarger with neutral CC), then
    // use it for the native midgray factor.
    spk::PrintingParams pparams = spk::digest_printing_params(
        neutral_cc, enl, /*exposure_factor_midgray=*/1.0, pg);
    pparams.exposure_factor_midgray = spk::compute_midgray_exposure_factor(
        film, prnt, tc_lut, pparams.filtered_illuminant, pg);
    // enlarger.print_exposure (default 1.0) multiplies the print exposure.
    pparams.print_exposure = p->print_exposure;

    // 4) Filming stage (rgb -> film density_cmy), reusing the bit-exact port.
    //    The print route runs the negative with spatial+stochastic effects off
    //    (matching the print_portra parity toggles), so only the pointwise
    //    DIR-coupler user params apply here.
    spk::FilmingParams fparams = spk::digest_filming_params(film.is_negative());
    fparams.exposure_compensation_ev = p->exposure_compensation_ev;
    const float fg = p->density_curve_gamma != 0.0f ? p->density_curve_gamma : 1.0f;
    fparams.density_curve_gamma[0] = fg;
    fparams.density_curve_gamma[1] = fg;
    fparams.density_curve_gamma[2] = fg;
    apply_user_dir_couplers(fparams.dir_couplers, p, /*spatial=*/false);

    std::vector<double> rgb(static_cast<size_t>(npix) * 3);
    for (size_t i = 0; i < rgb.size(); ++i)
        rgb[i] = static_cast<double>(in->data[i]);

    std::vector<float> film_log_raw(static_cast<size_t>(npix) * 3);
    spk::expose(rgb.data(), width, height, fparams, tc_lut, film_log_raw.data());
    if (tap_log_raw) *tap_log_raw = film_log_raw;

    std::vector<float> film_density_cmy(static_cast<size_t>(npix) * 3);
    spk::develop(film_log_raw.data(), width, height, film, fparams,
                 film_density_cmy.data());
    if (tap_film_density_cmy) *tap_film_density_cmy = film_density_cmy;

    // 4) Printing stage (film density -> enlarger expose -> print develop).
    std::vector<float> print_log_raw(static_cast<size_t>(npix) * 3);
    spk::print_expose(film, prnt, pparams, film_density_cmy.data(), npix,
                      print_log_raw.data());
    std::vector<float> print_density_cmy(static_cast<size_t>(npix) * 3);
    spk::print_develop(prnt, pparams, print_log_raw.data(), npix,
                       print_density_cmy.data());
    if (tap_print_density_cmy) *tap_print_density_cmy = print_density_cmy;

    if (!final_rgb) return SPK_OK;  // caller only wanted an earlier tap

    // 5) Scan the print (D50 viewing illuminant, print profile's dyes).
    spk::ScanningParams sparams;
    sparams.scan_film = false;
    sparams.output_color_space = p->output_color_space;
    sparams.output_cctf_encoding = (p->output_cctf_encoding != 0);
    final_rgb->assign(static_cast<size_t>(npix) * 3, 0.0f);
    spk::scan(prnt, sparams, print_density_cmy.data(), width, height,
              final_rgb->data());
    return SPK_OK;
}

// Allocate an spk_image and copy `data` into it.
spk_status fill_out_image(spk_image* out, const std::vector<float>& data,
                          int width, int height, int color_space) {
    out->data = static_cast<float*>(std::malloc(data.size() * sizeof(float)));
    if (!out->data) return SPK_ERR_OOM;
    std::memcpy(out->data, data.data(), data.size() * sizeof(float));
    out->width = width;
    out->height = height;
    out->color_space = color_space;
    return SPK_OK;
}

// Simple bilinear downscale of an interleaved-RGB float image to fit within
// `max_size` on the longest edge. Used by spk_simulate_preview. Aspect ratio is
// preserved; if the image already fits, the input is copied unchanged.
void downscale_bilinear(const float* src, int sw, int sh,
                        int dw, int dh, std::vector<float>* dst) {
    dst->assign(static_cast<size_t>(dw) * dh * 3, 0.0f);
    const float sx = sw > 1 ? static_cast<float>(sw - 1) / (dw - 1 > 0 ? dw - 1 : 1) : 0.0f;
    const float sy = sh > 1 ? static_cast<float>(sh - 1) / (dh - 1 > 0 ? dh - 1 : 1) : 0.0f;
    for (int y = 0; y < dh; ++y) {
        float fy = y * sy;
        int y0 = static_cast<int>(fy);
        int y1 = y0 + 1 < sh ? y0 + 1 : y0;
        float wy = fy - y0;
        for (int x = 0; x < dw; ++x) {
            float fx = x * sx;
            int x0 = static_cast<int>(fx);
            int x1 = x0 + 1 < sw ? x0 + 1 : x0;
            float wx = fx - x0;
            for (int c = 0; c < 3; ++c) {
                float v00 = src[(static_cast<size_t>(y0) * sw + x0) * 3 + c];
                float v01 = src[(static_cast<size_t>(y0) * sw + x1) * 3 + c];
                float v10 = src[(static_cast<size_t>(y1) * sw + x0) * 3 + c];
                float v11 = src[(static_cast<size_t>(y1) * sw + x1) * 3 + c];
                float top = v00 + (v01 - v00) * wx;
                float bot = v10 + (v11 - v10) * wx;
                (*dst)[(static_cast<size_t>(y) * dw + x) * 3 + c] = top + (bot - top) * wy;
            }
        }
    }
}

}  // namespace

extern "C" {

void spk_default_params(spk_params* p) {
    if (!p) return;
    const char* film = p->film_profile;
    const char* print = p->print_profile;
    std::memset(p, 0, sizeof(*p));
    p->film_profile = film;
    p->print_profile = print;

    // camera
    p->exposure_compensation_ev = 0.0f;
    p->auto_exposure = 1;
    p->lens_blur_um = 0.0f;
    p->film_format_mm = 35.0f;
    p->camera_filter_uv[0] = 0.0f; p->camera_filter_uv[1] = 410.0f; p->camera_filter_uv[2] = 8.0f;
    p->camera_filter_ir[0] = 0.0f; p->camera_filter_ir[1] = 675.0f; p->camera_filter_ir[2] = 15.0f;
    p->camera_diffusion_active = 0;
    p->camera_diffusion_strength = 0.5f;
    p->camera_diffusion_spatial_scale = 1.0f;
    p->camera_diffusion_halo_warmth = 0.0f;
    p->camera_diffusion_core_intensity = 1.0f;
    p->camera_diffusion_core_size = 1.0f;
    p->camera_diffusion_halo_intensity = 1.0f;
    p->camera_diffusion_halo_size = 1.0f;
    p->camera_diffusion_bloom_intensity = 1.0f;
    p->camera_diffusion_bloom_size = 1.0f;

    // enlarger
    p->y_filter_shift = 0.0f;
    p->m_filter_shift = 0.0f;
    p->preflash_exposure = 0.0f;
    p->normalize_print_exposure = 1;
    p->print_exposure = 1.0f;
    p->print_exposure_compensation = 1;
    p->y_filter_neutral = 55.0f;
    p->m_filter_neutral = 65.0f;
    p->c_filter_neutral = 0.0f;
    p->enlarger_lens_blur = 0.0f;
    p->preflash_y_filter_shift = 0.0f;
    p->preflash_m_filter_shift = 0.0f;
    p->enlarger_diffusion_active = 0;
    p->enlarger_diffusion_strength = 0.5f;
    p->enlarger_diffusion_spatial_scale = 1.0f;
    p->enlarger_diffusion_halo_warmth = 0.0f;
    p->enlarger_diffusion_core_intensity = 1.0f;
    p->enlarger_diffusion_core_size = 1.0f;
    p->enlarger_diffusion_halo_intensity = 1.0f;
    p->enlarger_diffusion_halo_size = 1.0f;
    p->enlarger_diffusion_bloom_intensity = 1.0f;
    p->enlarger_diffusion_bloom_size = 1.0f;

    // scanner
    p->scanner_lens_blur = 0.0f;
    p->scanner_unsharp[0] = 0.7f; p->scanner_unsharp[1] = 0.7f;
    p->scanner_white_correction = 0;
    p->scanner_black_correction = 0;
    p->scanner_white_level = 0.98f;
    p->scanner_black_level = 0.01f;

    // film rendering toggles
    p->density_curve_gamma = 1.0f;
    p->grain_active = 1;
    p->halation_active = 1;
    p->dir_couplers_active = 1;
    p->glare_active = 1;

    // grain
    p->grain_sublayers_active = 1;
    p->grain_particle_area_um2 = 0.2f;
    p->grain_particle_scale[0] = 0.8f; p->grain_particle_scale[1] = 1.0f; p->grain_particle_scale[2] = 2.0f;
    p->grain_particle_scale_layers[0] = 2.5f; p->grain_particle_scale_layers[1] = 1.0f; p->grain_particle_scale_layers[2] = 0.5f;
    p->grain_density_min[0] = 0.07f; p->grain_density_min[1] = 0.08f; p->grain_density_min[2] = 0.12f;
    p->grain_uniformity[0] = 0.97f; p->grain_uniformity[1] = 0.97f; p->grain_uniformity[2] = 0.99f;
    p->grain_blur = 0.65f;
    p->grain_blur_dye_clouds_um = 1.0f;
    p->grain_micro_structure[0] = 0.2f; p->grain_micro_structure[1] = 30.0f;
    p->grain_n_sub_layers = 1;

    // halation
    p->halation_scatter_amount = 1.0f;
    p->halation_scatter_spatial_scale = 1.0f;
    p->halation_halation_amount = 1.0f;
    p->halation_halation_spatial_scale = 1.0f;
    p->halation_scatter_core_um[0] = 2.2f; p->halation_scatter_core_um[1] = 2.0f; p->halation_scatter_core_um[2] = 1.6f;
    p->halation_scatter_tail_um[0] = 9.3f; p->halation_scatter_tail_um[1] = 9.7f; p->halation_scatter_tail_um[2] = 9.1f;
    p->halation_scatter_tail_weight[0] = 0.78f; p->halation_scatter_tail_weight[1] = 0.65f; p->halation_scatter_tail_weight[2] = 0.67f;
    p->halation_boost_ev = 0.0f;
    p->halation_boost_range = 0.3f;
    p->halation_protect_ev = 4.0f;
    p->halation_strength[0] = 0.05f; p->halation_strength[1] = 0.015f; p->halation_strength[2] = 0.0f;
    p->halation_first_sigma_um[0] = 65.0f; p->halation_first_sigma_um[1] = 65.0f; p->halation_first_sigma_um[2] = 65.0f;
    p->halation_n_bounces = 3;
    p->halation_bounce_decay = 0.5f;
    p->halation_renormalize = 1;

    // DIR couplers
    p->dir_amount = 1.0f;
    p->dir_inhibition_samelayer = 1.0f;
    p->dir_inhibition_interlayer = 1.0f;
    p->dir_gamma_samelayer_rgb[0] = 0.341f; p->dir_gamma_samelayer_rgb[1] = 0.324f; p->dir_gamma_samelayer_rgb[2] = 0.273f;
    p->dir_gamma_interlayer_r_to_gb[0] = 0.355f; p->dir_gamma_interlayer_r_to_gb[1] = 0.305f;
    p->dir_gamma_interlayer_g_to_rb[0] = 0.154f; p->dir_gamma_interlayer_g_to_rb[1] = 0.358f;
    p->dir_gamma_interlayer_b_to_rg[0] = 0.171f; p->dir_gamma_interlayer_b_to_rg[1] = 0.225f;
    p->dir_diffusion_size_um = 20.0f;
    p->dir_diffusion_tail_um = 200.0f;
    p->dir_diffusion_tail_weight = 0.06f;

    // glare
    p->glare_percent = 0.03f;
    p->glare_roughness = 0.7f;
    p->glare_blur = 0.5f;
    p->print_glare_active = 1;
    p->print_glare_percent = 0.03f;
    p->print_glare_roughness = 0.7f;
    p->print_glare_blur = 0.5f;
    p->print_density_curve_gamma = 1.0f;

    // io
    p->scan_film = 0;
    p->output_color_space = SPK_CS_SRGB;
    p->output_cctf_encoding = 1;
    p->input_cctf_decoding = 0;
    p->crop = 0;
    p->crop_center[0] = 0.5f; p->crop_center[1] = 0.5f;
    p->crop_size[0] = 0.1f; p->crop_size[1] = 0.1f;
    p->upscale_factor = 1.0f;

    // settings
    p->rgb_to_raw_method = SPK_RGB2RAW_HANATOS2025;
    p->apply_hanatos_window = 1;
    p->apply_hanatos_surface = 0;
    p->spectral_gaussian_blur = 0.0f;
    p->use_enlarger_lut = 0;
    p->use_scanner_lut = 0;
    p->lut_resolution = 17;
    p->preview_max_size = 640;
    p->neutral_print_filters_from_database = 1;
}

const char* spk_status_str(spk_status s) {
    switch (s) {
        case SPK_OK:                    return "ok";
        case SPK_ERR_BAD_ARGS:          return "bad arguments";
        case SPK_ERR_PROFILE_NOT_FOUND: return "profile not found";
        case SPK_ERR_ASSET_IO:          return "asset I/O error";
        case SPK_ERR_OOM:               return "out of memory";
        case SPK_ERR_INTERNAL:          return "internal error";
        default:                        return "unknown";
    }
}

spk_status spk_engine_create(const char* asset_dir, spk_engine** out) {
    if (!out) return SPK_ERR_BAD_ARGS;
    if (!asset_dir) return SPK_ERR_BAD_ARGS;  // AAssetManager path is wired via JNI (TODO).
    auto eng = std::make_unique<spk_engine>();
    eng->asset_dir = asset_dir;
    eng->profiles_dir = join_path(eng->asset_dir, "profiles");
    eng->spectra_lut_path =
        join_path(eng->asset_dir, "luts/spectral_upsampling/irradiance_xy_tc.npy");
    eng->neutral_filters_path =
        join_path(eng->asset_dir, "filters/neutral_print_filters.json");
    *out = eng.release();
    return SPK_OK;
}

void spk_engine_destroy(spk_engine* eng) { delete eng; }

spk_status spk_engine_list_profiles(spk_engine* eng, char* buf, size_t buf_len,
                                    size_t* needed) {
    if (!eng) return SPK_ERR_BAD_ARGS;
    DIR* d = opendir(eng->profiles_dir.c_str());
    if (!d) return SPK_ERR_ASSET_IO;
    std::string list;
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;
        if (ends_with(name, ".json")) {
            if (!list.empty()) list += '\n';
            list += name.substr(0, name.size() - 5);  // strip ".json"
        }
    }
    closedir(d);

    size_t req = list.size() + 1;  // include NUL
    if (needed) *needed = req;
    if (!buf || buf_len < req) return SPK_ERR_BAD_ARGS;  // caller resizes & retries
    std::memcpy(buf, list.c_str(), req);
    return SPK_OK;
}

spk_status spk_simulate(spk_engine* eng, const spk_image* in, const spk_params* p,
                        spk_image* out) {
    if (!out) return SPK_ERR_BAD_ARGS;
    if (!p) return SPK_ERR_BAD_ARGS;
    std::vector<float> rgb;
    spk_status st;
    if (p->scan_film) {
        st = run_scan_film(eng, in, p, &rgb, nullptr, nullptr);
    } else {
        // Print (enlarger) route: filming -> printing -> scan(print).
        st = run_print(eng, in, p, &rgb, nullptr, nullptr, nullptr);
    }
    if (st != SPK_OK) return st;
    return fill_out_image(out, rgb, in->width, in->height,
                          static_cast<int>(p->output_color_space));
}

spk_status spk_simulate_preview(spk_engine* eng, const spk_image* in,
                                const spk_params* p, spk_image* out) {
    if (!eng || !in || !p || !out || !in->data) return SPK_ERR_BAD_ARGS;
    int max_size = p->preview_max_size > 0 ? p->preview_max_size : 640;
    int longest = in->width > in->height ? in->width : in->height;
    if (longest <= max_size) {
        // Already small enough: run the full simulate on the original.
        return spk_simulate(eng, in, p, out);
    }
    // Downscale (bilinear) to the preview target, preserving aspect ratio, then
    // simulate. Done in input (linear ProPhoto) space, before the pipeline.
    double scale = static_cast<double>(max_size) / longest;
    int dw = in->width  > 1 ? static_cast<int>(std::lround(in->width  * scale)) : 1;
    int dh = in->height > 1 ? static_cast<int>(std::lround(in->height * scale)) : 1;
    if (dw < 1) dw = 1;
    if (dh < 1) dh = 1;

    std::vector<float> small;
    downscale_bilinear(in->data, in->width, in->height, dw, dh, &small);

    spk_image small_img{small.data(), dw, dh, in->color_space};
    return spk_simulate(eng, &small_img, p, out);
}

spk_status spk_simulate_tap(spk_engine* eng, const spk_image* in,
                            const spk_params* p, const char* tap_name,
                            spk_image* out) {
    if (!out || !p || !tap_name) return SPK_ERR_BAD_ARGS;

    std::string tap = tap_name;
    std::vector<float> log_raw, film_density_cmy, print_density_cmy, final_rgb;
    spk_status st;

    if (p->scan_film) {
        // scan_film route: only the negative taps + final RGB exist.
        if (tap == "film_log_raw") {
            st = run_scan_film(eng, in, p, nullptr, &log_raw, nullptr);
            if (st != SPK_OK) return st;
            return fill_out_image(out, log_raw, in->width, in->height, in->color_space);
        } else if (tap == "film_density_cmy") {
            st = run_scan_film(eng, in, p, nullptr, &log_raw, &film_density_cmy);
            if (st != SPK_OK) return st;
            return fill_out_image(out, film_density_cmy, in->width, in->height,
                                  in->color_space);
        } else if (tap == "final_rgb") {
            st = run_scan_film(eng, in, p, &final_rgb, nullptr, nullptr);
            if (st != SPK_OK) return st;
            return fill_out_image(out, final_rgb, in->width, in->height,
                                  static_cast<int>(p->output_color_space));
        }
        return SPK_ERR_BAD_ARGS;  // unknown / print-only tap on scan_film route
    }

    // Print route taps (filming -> printing -> scan).
    if (tap == "film_log_raw") {
        st = run_print(eng, in, p, nullptr, &log_raw, nullptr, nullptr);
        if (st != SPK_OK) return st;
        return fill_out_image(out, log_raw, in->width, in->height, in->color_space);
    } else if (tap == "film_density_cmy") {
        st = run_print(eng, in, p, nullptr, nullptr, &film_density_cmy, nullptr);
        if (st != SPK_OK) return st;
        return fill_out_image(out, film_density_cmy, in->width, in->height,
                              in->color_space);
    } else if (tap == "print_density_cmy") {
        st = run_print(eng, in, p, nullptr, nullptr, nullptr, &print_density_cmy);
        if (st != SPK_OK) return st;
        return fill_out_image(out, print_density_cmy, in->width, in->height,
                              in->color_space);
    } else if (tap == "final_rgb") {
        st = run_print(eng, in, p, &final_rgb, nullptr, nullptr, nullptr);
        if (st != SPK_OK) return st;
        return fill_out_image(out, final_rgb, in->width, in->height,
                              static_cast<int>(p->output_color_space));
    }
    return SPK_ERR_BAD_ARGS;  // unknown tap
}

spk_status spk_bake_cube_lut(spk_engine* eng, const spk_params* p, int lut_size,
                             char* out_text, size_t out_cap, size_t* needed) {
    if (needed) *needed = 0;
    if (!eng || !p) return SPK_ERR_BAD_ARGS;

    // Clamp the lattice size to a sane range. 33 is the default; below 2 a 3D LUT
    // is meaningless, and >256 explodes the bake cost (N^3 lattice points).
    int n = lut_size > 0 ? lut_size : 33;
    if (n < 2) n = 2;
    if (n > 256) n = 256;

    // --- Force spatial/stochastic effects OFF for the bake -------------------
    // A 3D LUT is a pure pointwise RGB->RGB map; any spatial or stochastic
    // effect (grain, halation + its in-emulsion scatter, camera/enlarger
    // diffusion glare, DIR-coupler spatial diffusion, scanner unsharp) cannot be
    // represented and would make the lattice irreproducible. We copy the params
    // and disable those, keeping the pointwise color science intact: spectral
    // upsampling, density curves, pointwise DIR couplers, printing, scanning,
    // and the output color-space transform. (Note: run_scan_film/run_print only
    // enable the spatial branch when halation_active is set, and only read the
    // scanner-unsharp / spatial diffusion fields in that branch; grain only runs
    // when grain_active is set. Disabling those two toggles deactivates every
    // spatial/stochastic path. We also clear the glare toggles for clarity.)
    spk_params bp = *p;
    bp.grain_active = 0;
    bp.halation_active = 0;
    bp.glare_active = 0;
    bp.print_glare_active = 0;
    bp.camera_diffusion_active = 0;
    bp.enlarger_diffusion_active = 0;

    // --- Build the identity lattice as an spk_image --------------------------
    // Lattice axes span [0,1] in the engine's linear working space (treated as
    // ProPhoto-linear by the filming expose). The image is laid out so that BLUE
    // varies fastest, then GREEN, then RED (blue-fastest / red-slowest), matching
    // the .cube data ordering emitted below. We pack the whole lattice into a
    // single image row (width = n^3, height = 1) and run it through the exact
    // same per-pixel pipeline spk_simulate uses.
    const size_t count = static_cast<size_t>(n) * n * n;
    std::vector<float> lattice(count * 3);
    const float denom = static_cast<float>(n - 1);
    size_t idx = 0;
    for (int r = 0; r < n; ++r) {
        const float rv = static_cast<float>(r) / denom;
        for (int g = 0; g < n; ++g) {
            const float gv = static_cast<float>(g) / denom;
            for (int b = 0; b < n; ++b) {
                const float bv = static_cast<float>(b) / denom;
                lattice[idx * 3 + 0] = rv;
                lattice[idx * 3 + 1] = gv;
                lattice[idx * 3 + 2] = bv;
                ++idx;
            }
        }
    }

    spk_image in_img{lattice.data(), static_cast<int32_t>(count), 1,
                     static_cast<int32_t>(SPK_CS_PROPHOTO)};

    // --- Run the pipeline (same route spk_simulate would take) ---------------
    std::vector<float> rgb;
    spk_status st;
    if (bp.scan_film) {
        st = run_scan_film(eng, &in_img, &bp, &rgb, nullptr, nullptr);
    } else {
        st = run_print(eng, &in_img, &bp, &rgb, nullptr, nullptr, nullptr);
    }
    if (st != SPK_OK) return st;
    if (rgb.size() != count * 3) return SPK_ERR_INTERNAL;

    // --- Emit the .cube text -------------------------------------------------
    const char* cs_name = "sRGB";
    switch (bp.output_color_space) {
        case SPK_CS_SRGB:        cs_name = "sRGB"; break;
        case SPK_CS_ADOBE_RGB:   cs_name = "Adobe RGB (1998)"; break;
        case SPK_CS_PROPHOTO:    cs_name = "ProPhoto RGB"; break;
        case SPK_CS_REC2020:     cs_name = "Rec.2020"; break;
        case SPK_CS_ACES2065_1:  cs_name = "ACES2065-1"; break;
        case SPK_CS_LINEAR_SRGB: cs_name = "linear sRGB"; break;
    }
    const char* route = bp.scan_film ? "scan negative" : "negative -> print -> scan";

    std::string out;
    out.reserve(count * 33 + 1024);
    char line[256];

    out += "# SpectraFilm for Android — baked film-look 3D LUT (.cube)\n";
    out += "# Film modeling powered by spektrafilm (GPLv3).\n";
    std::snprintf(line, sizeof(line), "# Film profile: %s\n",
                  p->film_profile ? p->film_profile : "(none)");
    out += line;
    if (!bp.scan_film) {
        std::snprintf(line, sizeof(line), "# Print profile: %s\n",
                      p->print_profile ? p->print_profile : "(none)");
        out += line;
    }
    std::snprintf(line, sizeof(line), "# Pipeline route: %s\n", route);
    out += line;
    out += "# INPUT domain:  linear ProPhoto RGB in [0,1] (engine working space,\n";
    out += "#                the lattice axis fed to the filming expose).\n";
    std::snprintf(line, sizeof(line),
                  "# OUTPUT domain: %s, CCTF encoding %s.\n", cs_name,
                  bp.output_cctf_encoding ? "ON" : "OFF");
    out += line;
    out += "# EXCLUDED (cannot be captured by a 3D LUT, forced OFF for bake):\n";
    out += "#   grain, halation (+ in-emulsion scatter), camera/enlarger diffusion\n";
    out += "#   glare, DIR-coupler spatial diffusion, scanner unsharp mask.\n";
    out += "# KEPT: spectral upsampling, density curves, pointwise DIR couplers,\n";
    out += "#   printing, scanning, output color-space transform.\n";
    out += "# Data order: BLUE fastest, then GREEN, then RED.\n";

    std::snprintf(line, sizeof(line), "TITLE \"SpectraFilm %s\"\n",
                  p->film_profile ? p->film_profile : "look");
    out += line;
    std::snprintf(line, sizeof(line), "LUT_3D_SIZE %d\n", n);
    out += line;
    out += "DOMAIN_MIN 0.0 0.0 0.0\n";
    out += "DOMAIN_MAX 1.0 1.0 1.0\n";

    for (size_t i = 0; i < count; ++i) {
        std::snprintf(line, sizeof(line), "%.6f %.6f %.6f\n",
                      static_cast<double>(rgb[i * 3 + 0]),
                      static_cast<double>(rgb[i * 3 + 1]),
                      static_cast<double>(rgb[i * 3 + 2]));
        out += line;
    }

    const size_t req = out.size() + 1;  // include NUL terminator
    if (needed) *needed = req;
    if (!out_text || out_cap < req) return SPK_ERR_BAD_ARGS;  // caller resizes & retries
    std::memcpy(out_text, out.c_str(), req);
    return SPK_OK;
}

void spk_image_free(spk_image* img) {
    if (img && img->data) {
        std::free(img->data);
        img->data = nullptr;
        img->width = 0;
        img->height = 0;
    }
}

}  // extern "C"
