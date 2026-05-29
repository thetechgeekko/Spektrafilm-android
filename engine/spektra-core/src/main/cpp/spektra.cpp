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
 * The print (enlarger) route is M4 and returns SPK_ERR_INTERNAL with a TODO.
 *
 * Honoured spk_params for the scan_film parity case (scan_portra defaults):
 *   - film_profile          -> profile JSON loaded from <asset_dir>/profiles/<id>.json
 *   - scan_film             -> selects this route (false => print route, TODO M4)
 *   - exposure_compensation_ev -> FilmingParams.exposure_compensation_ev
 *   - density_curve_gamma   -> FilmingParams.density_curve_gamma (broadcast to CMY)
 *   - output_color_space    -> only SPK_CS_SRGB is wired today (scanning stage emits
 *                              sRGB via the baked D50 matrix); other spaces TODO.
 *   - output_cctf_encoding  -> ScanningParams.output_cctf_encoding
 *   - preview_max_size      -> used by spk_simulate_preview for the downscale target.
 *
 * Internally defaulted (matching the scan_portra goldens' toggles):
 *   - auto_exposure off, spatial + stochastic effects off, grain off, glare off.
 *     These are baked into FilmingParams/ScanningParams defaults; the corresponding
 *     spk_params toggles (grain_active/halation_active/glare_active/...) are not yet
 *     wired through and are treated as "off" for the scan_film parity route.
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
#include "runtime/stages/filming.h"
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

    // 2) Digested filming params (auto-exposure off; spatial + stochastic off).
    spk::FilmingParams fparams = spk::digest_filming_params(film.is_negative());
    fparams.exposure_compensation_ev = p->exposure_compensation_ev;
    const float g = p->density_curve_gamma != 0.0f ? p->density_curve_gamma : 1.0f;
    fparams.density_curve_gamma[0] = g;
    fparams.density_curve_gamma[1] = g;
    fparams.density_curve_gamma[2] = g;

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
    spk::expose(rgb.data(), npix, fparams, tc_lut, log_raw.data());
    if (tap_log_raw) *tap_log_raw = log_raw;

    // 5) develop(): log_raw -> density_cmy (+ DIR couplers).
    std::vector<float> density_cmy(static_cast<size_t>(npix) * 3);
    spk::develop(log_raw.data(), npix, film, fparams, density_cmy.data());
    if (tap_density_cmy) *tap_density_cmy = density_cmy;

    if (!final_rgb) return SPK_OK;  // caller only wanted an earlier tap

    // 6) scan(): density_cmy -> display RGB (sRGB, CCTF per params).
    spk::ScanningParams sparams;
    sparams.scan_film = true;
    sparams.output_cctf_encoding = (p->output_cctf_encoding != 0);

    final_rgb->assign(static_cast<size_t>(npix) * 3, 0.0f);
    spk::scan(film, sparams, density_cmy.data(), width, height, final_rgb->data());
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
    if (!p->scan_film) {
        // TODO(M4): print (enlarger) route — expose negative onto print stock,
        // develop print, scan the print. Not yet ported.
        return SPK_ERR_INTERNAL;
    }
    std::vector<float> rgb;
    spk_status st = run_scan_film(eng, in, p, &rgb, nullptr, nullptr);
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
    if (!p->scan_film) return SPK_ERR_INTERNAL;  // print taps are M4.

    std::string tap = tap_name;
    std::vector<float> log_raw, density_cmy, final_rgb;

    spk_status st;
    if (tap == "film_log_raw") {
        st = run_scan_film(eng, in, p, nullptr, &log_raw, nullptr);
        if (st != SPK_OK) return st;
        return fill_out_image(out, log_raw, in->width, in->height, in->color_space);
    } else if (tap == "film_density_cmy") {
        st = run_scan_film(eng, in, p, nullptr, &log_raw, &density_cmy);
        if (st != SPK_OK) return st;
        return fill_out_image(out, density_cmy, in->width, in->height, in->color_space);
    } else if (tap == "final_rgb") {
        st = run_scan_film(eng, in, p, &final_rgb, nullptr, nullptr);
        if (st != SPK_OK) return st;
        return fill_out_image(out, final_rgb, in->width, in->height,
                              static_cast<int>(p->output_color_space));
    }
    return SPK_ERR_BAD_ARGS;  // unknown tap
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
