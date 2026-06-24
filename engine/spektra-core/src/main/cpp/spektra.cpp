/*
 * Spektrafilm for Android — native engine entry (capstone wiring).
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
 *   - scan_film             -> selects this route (false => print route, implemented + parity-gated)
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
#include <cstdint>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/asset_manager.h>
#endif

#include "io/npy_lut.h"
#include "model/color_filters.h"
#include "profiles/profile.h"
#include "runtime/color_reference.h"
#include "runtime/params.h"
#include "runtime/print_digest.h"
#include "runtime/stages/autoexposure.h"
#include "runtime/stages/crop_resize.h"
#include "runtime/stages/filming.h"
#include "runtime/stages/printing.h"
#include "runtime/stages/scanning.h"

namespace {

// Build the scanning-stage tone curve from the flat spk_params control points.
// Inactive (the default) => an inactive set whose apply() is a strict no-op. Point
// counts are clamped to [0, SPK_TONE_MAX_PTS]; a count < 2 yields an identity curve.
spk::ToneCurveSet build_tone_curve_set(const spk_params* p) {
    spk::ToneCurveSet set;
    if (p->tone_curve_active == 0) { set.active = false; return set; }
    set.active = true;
    auto clampN = [](int n) {
        if (n < 0) return 0;
        return n > SPK_TONE_MAX_PTS ? SPK_TONE_MAX_PTS : n;
    };
    set.master = spk::build_tone_curve_1d(p->tone_curve_master_x, p->tone_curve_master_y,
                                          clampN(p->tone_curve_master_n));
    for (int c = 0; c < 3; ++c) {
        set.rgb[c] = spk::build_tone_curve_1d(p->tone_curve_rgb_x[c], p->tone_curve_rgb_y[c],
                                              clampN(p->tone_curve_rgb_n[c]));
    }
    return set;
}

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

// Relative (to the spektra/ asset root) paths of the bundled assets. The
// filesystem mode joins these onto asset_dir; the AAssetManager mode passes them
// (prefixed by asset_base) to AAssetManager_open.
namespace {
constexpr char kSpectraLutRel[] = "luts/spectral_upsampling/irradiance_xy_tc.npy";
constexpr char kNeutralFiltersRel[] = "filters/neutral_print_filters.json";
}  // namespace

// Engine: holds asset paths and lazily caches the spectra LUT (shared across calls).
//
// Two asset-I/O modes, both reading the SAME relative paths (relative to the
// spektra/ asset root, e.g. "profiles/foo.json"):
//   - Filesystem mode (default, used by the host parity tests): assets live under
//     `asset_dir` on disk; spk_read_asset() opens asset_dir/<rel> with ifstream.
//     This path is byte-for-byte the historical behavior and is the parity gate.
//   - AAssetManager mode (Android only): assets live in the APK; spk_read_asset()
//     opens <asset_base>/<rel> via AAssetManager_open. Selected only when the
//     engine is created with spk_engine_create_asset_manager.
struct spk_engine {
    std::string asset_dir;        // root containing profiles/ and luts/ (FS mode)
    std::string profiles_dir;     // <asset_dir>/profiles (FS mode)

#ifdef __ANDROID__
    // AAssetManager mode (null in filesystem mode). Not owned: the Java
    // AssetManager (and thus this pointer) must outlive the engine — the Kotlin
    // side keeps it referenced (see SpektraEngine). When non-null, spk_read_asset
    // and spk_engine_list_profiles take the AAsset path instead of the FS path.
    AAssetManager* asset_mgr = nullptr;
    // Subdir inside the APK assets/ where the bundled tree lives (the app stores
    // its assets under assets/spektra/...), prepended to every relative path for
    // the AAsset case. AAssetManager paths are relative to assets/.
    std::string asset_base = "spektra";
    bool use_asset_mgr() const { return asset_mgr != nullptr; }
#else
    bool use_asset_mgr() const { return false; }
#endif

    std::mutex lut_mutex;
    bool lut_loaded = false;
    spk::NdArray spectra_lut;

    // Per-render setup caches (PERF). Every simulate() otherwise re-parses the
    // film/print profile JSON and rebuilds the filming tc_lut from scratch, even
    // on an interactive slider drag that changed nothing about the profile. Both
    // are keyed PURELY by the profile id, which maps to an immutable bundled
    // asset, so a cache entry can never go stale across param changes — the
    // returned value is byte-identical to a fresh parse/build (a memo, not an
    // approximation). build_filming_tc_lut depends only on (film profile, the
    // immutable spectra LUT, the D55 constant); the hanatos window/surface/blur
    // toggles are hardcoded constants, not params, so it too is a pure function
    // of the film id. Never evicted (28 bundled profiles, bounded), so node
    // references stay valid. Guarded by cache_mutex.
    std::mutex cache_mutex;
    std::map<std::string, spk::Profile> profile_cache;   // id -> parsed Profile
    std::map<std::string, spk::NdArray> tc_lut_cache;    // film id -> filming tc_lut

    // PRINT-ROUTE film_density_cmy memo (PERF). Unlike profile_cache/tc_lut_cache
    // above — which are keyed by an IMMUTABLE bundled-asset id and so can never go
    // stale — this single-slot cache is keyed by a CONTENT+PARAM DIGEST
    // (compute_film_cache_key): a 64-bit FNV-1a hash folding the post-preprocess
    // expose-input buffer (image content + auto-exposure + crop/rescale results)
    // plus every filming-side input param. The key is therefore NOT an id; it is a
    // fingerprint of the exact inputs to expose()+develop(). On the PRINT route
    // (run_print) the filming step runs spatial-OFF and grain-OFF, so its output is
    // a PURE deterministic function of those inputs with NO stochastic/grain state —
    // making the memo byte-identical to a fresh expose+develop (a memo, not an
    // approximation), exactly like the 54d4d3d tc_lut memo. It lets a downstream-only
    // edit (printing/scanning/tone-curve params) reuse filming and skip expose+develop.
    // Single-slot (only the most recent inputs); replaced on every miss. Scope is the
    // print route ONLY — run_scan_film never consults it. Guarded by film_cache_mutex.
    struct FilmCacheEntry {
        int width = 0;
        int height = 0;
        std::vector<float> film_density_cmy;
    };
    std::mutex film_cache_mutex;
    uint64_t film_cache_key = 0;        // 0 == empty slot (no valid entry yet)
    bool film_cache_valid = false;      // guards the all-zero-key edge case
    FilmCacheEntry film_cache;
    uint64_t film_cache_hits = 0;       // host-test observability (NOT public ABI)
    uint64_t film_cache_misses = 0;
};

// Host-only accessors for the print-route film_density_cmy cache counters. The host
// parity tests compile spektra.cpp directly into their binary and read these to
// assert the cache actually engaged, WITHOUT touching spektra.h / the public ABI /
// JNI. Gated on the HOST build (!__ANDROID__) so they never enter the shipped
// libspektra.so; not declared in any header — tests forward-declare them. The host
// parity job (engine-parity) builds with the host g++ toolchain, so __ANDROID__ is
// undefined and these are available there without needing an extra -D flag.
#ifndef __ANDROID__
uint64_t spk_test_film_cache_hits(spk_engine* eng) {
    if (!eng) return 0;
    std::lock_guard<std::mutex> g(eng->film_cache_mutex);
    return eng->film_cache_hits;
}
uint64_t spk_test_film_cache_misses(spk_engine* eng) {
    if (!eng) return 0;
    std::lock_guard<std::mutex> g(eng->film_cache_mutex);
    return eng->film_cache_misses;
}
#endif

// Read a bundled asset by its path relative to the spektra/ asset root (e.g.
// "profiles/kodak_portra_400.json") into `out`. Returns false on open failure.
// In AAssetManager mode (Android) it opens via AAssetManager_open; otherwise it
// reads asset_dir/<rel_path> with std::ifstream (the historical, parity-gated
// behavior). Throws nothing.
static bool spk_read_asset(spk_engine* eng, const std::string& rel_path,
                           std::vector<char>& out) {
    if (!eng) return false;
#ifdef __ANDROID__
    if (eng->use_asset_mgr()) {
        std::string full = eng->asset_base.empty()
                               ? rel_path
                               : eng->asset_base + "/" + rel_path;
        AAsset* a = AAssetManager_open(eng->asset_mgr, full.c_str(),
                                       AASSET_MODE_BUFFER);
        if (!a) return false;
        off_t len = AAsset_getLength(a);
        out.resize(len > 0 ? static_cast<size_t>(len) : 0);
        bool ok = true;
        if (len > 0) {
            int read = AAsset_read(a, out.data(), static_cast<size_t>(len));
            ok = (read == static_cast<int>(len));
        }
        AAsset_close(a);
        return ok;
    }
#endif
    // Filesystem mode: read asset_dir/<rel_path> — historical ifstream behavior.
    std::string path = eng->asset_dir;
    if (!path.empty() && path.back() != '/') path += '/';
    path += rel_path;
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) return false;
    std::streamsize size = in.tellg();
    if (size < 0) return false;
    in.seekg(0, std::ios::beg);
    out.resize(static_cast<size_t>(size));
    if (size > 0 && !in.read(out.data(), size)) return false;
    return true;
}

// Lazily load + cache the Hanatos2025 spectra LUT (shared across calls), reading
// it through spk_read_asset so it works in both filesystem and AAssetManager
// modes. Throws std::runtime_error if the asset can't be read or parsed.
static const spk::NdArray& engine_spectra(spk_engine* eng) {
    std::lock_guard<std::mutex> g(eng->lut_mutex);
    if (!eng->lut_loaded) {
        std::vector<char> buf;
        if (!spk_read_asset(eng, kSpectraLutRel, buf))
            throw std::runtime_error("spektra: cannot read spectra LUT asset");
        eng->spectra_lut = spk::parse_npy(buf.data(), buf.size(), kSpectraLutRel);
        eng->lut_loaded = true;
    }
    return eng->spectra_lut;
}

// Load a film/print profile by id (e.g. "kodak_portra_400") through spk_read_asset.
// Throws std::runtime_error if the asset can't be read; load_profile_string
// throws on parse failure.
static spk::Profile load_engine_profile(spk_engine* eng, const std::string& id) {
    {
        std::lock_guard<std::mutex> g(eng->cache_mutex);
        auto it = eng->profile_cache.find(id);
        if (it != eng->profile_cache.end()) return it->second;  // copy of cached parse
    }
    std::vector<char> buf;
    std::string rel = std::string("profiles/") + id + ".json";
    if (!spk_read_asset(eng, rel, buf))
        throw std::runtime_error("spektra: cannot read profile asset " + rel);
    spk::Profile parsed = spk::load_profile_string(std::string(buf.data(), buf.size()));
    std::lock_guard<std::mutex> g(eng->cache_mutex);
    // Insert if still absent (another thread may have raced us); either way the
    // stored value equals a fresh parse, so the result is identical.
    return eng->profile_cache.emplace(id, std::move(parsed)).first->second;
}

// Cached filming tc_lut, keyed by (film id, spectral_gaussian_blur, apply_window,
// apply_surface). For the DEFAULT toggles (blur==0, window on, surface off) the key
// is just the film id and the cached LUT is byte-identical to rebuilding it
// (build_filming_tc_lut is a pure function of film profile, the immutable spectra
// LUT, the D55 constant, the blur sigma, and the window/surface toggles). A
// non-default toggle (or non-zero blur) gets its own cache slot so distinct
// adaptations never collide. Returns a reference into the never-evicted cache map
// (node references stay valid). Throws on build failure (caller maps to
// SPK_ERR_ASSET_IO, as the inline build did).
static const spk::NdArray& engine_tc_lut(spk_engine* eng,
                                         const std::string& film_id,
                                         const spk::Profile& film,
                                         float spectral_gaussian_blur,
                                         bool apply_window, bool apply_surface,
                                         const float* filter_uv,
                                         const float* filter_ir) {
    // Compose a key that folds the blur sigma's exact IEEE-754 bytes plus the
    // window/surface toggles and the camera UV/IR band-pass so distinct adaptations
    // (or float jitter) never alias. The DEFAULT (blur==0, window on, surface off,
    // band-pass amplitudes 0) keeps the bare film-id key, preserving the existing
    // cache behaviour for the default path.
    const bool band_pass_on =
        filter_uv != nullptr && filter_ir != nullptr &&
        (filter_uv[0] > 0.0f || filter_ir[0] > 0.0f);
    std::string key = film_id;
    if (spectral_gaussian_blur > 0.0f || !apply_window || apply_surface ||
        band_pass_on) {
        key.push_back('|');
        const unsigned char* b =
            reinterpret_cast<const unsigned char*>(&spectral_gaussian_blur);
        for (size_t i = 0; i < sizeof(float); ++i) {
            key.push_back(static_cast<char>(b[i]));
        }
        key.push_back(apply_window ? 'W' : 'w');
        key.push_back(apply_surface ? 'S' : 's');
        // Fold the band-pass triples' exact bytes only when active, so the default
        // (off) key is unchanged.
        if (band_pass_on) {
            key.push_back('B');
            const unsigned char* fu =
                reinterpret_cast<const unsigned char*>(filter_uv);
            const unsigned char* fr =
                reinterpret_cast<const unsigned char*>(filter_ir);
            for (size_t i = 0; i < 3 * sizeof(float); ++i)
                key.push_back(static_cast<char>(fu[i]));
            for (size_t i = 0; i < 3 * sizeof(float); ++i)
                key.push_back(static_cast<char>(fr[i]));
        }
    }
    {
        std::lock_guard<std::mutex> g(eng->cache_mutex);
        auto it = eng->tc_lut_cache.find(key);
        if (it != eng->tc_lut_cache.end()) return it->second;
    }
    spk::NdArray lut = spk::build_filming_tc_lut(film, engine_spectra(eng),
                                                 kD55Illuminant,
                                                 spectral_gaussian_blur,
                                                 apply_window, apply_surface,
                                                 filter_uv, filter_ir);
    std::lock_guard<std::mutex> g(eng->cache_mutex);
    return eng->tc_lut_cache.emplace(key, std::move(lut)).first->second;
}

// FNV-1a 64-bit over raw bytes. Used to build the print-route film_density_cmy
// cache key by folding the IEEE-754 bytes of every filming-side input.
static inline uint64_t fnv1a64(uint64_t h, const void* data, size_t n) {
    const unsigned char* p = static_cast<const unsigned char*>(data);
    for (size_t i = 0; i < n; ++i) {
        h ^= static_cast<uint64_t>(p[i]);
        h *= 0x100000001b3ULL;
    }
    return h;
}

// Compute the single-slot film_density_cmy cache key for the PRINT route. The key
// is a 64-bit FNV-1a hash folding EVERY input that the print-route filming step
// (expose + develop, spatial-OFF / grain-OFF) consumes, so two calls collide IFF
// their film_density_cmy is byte-identical:
//   - the POST-preprocess `rgb` float64 buffer (the actual expose() input): this
//     already folds in image content + auto-exposure + crop/rescale geometry,
//   - width, height, input color_space,
//   - film_profile string bytes,
//   - exposure_compensation_ev, density_curve_gamma (the FILM gamma, not print gamma),
//     rgb_to_raw_method, spectral_gaussian_blur (blurs the filming tc_lut),
//   - the DIR pointwise params (active, amount, inhibition same/inter-layer),
//   - DEFENSIVELY: the geometry params (crop, crop_center, crop_size, upscale_factor,
//     film_format_mm) and the currently-forced-off toggles (grain/spatial/halation/
//     diffusion/lens_blur) — so a future change that begins to influence filming can
//     never silently reuse a stale entry.
// IMPORTANT: the rgb buffer is the post-preprocess expose input, so geometry params
// are already reflected in it; folding them too is belt-and-suspenders, not required.
static uint64_t compute_film_cache_key(const std::vector<double>& rgb, int width,
                                       int height, int input_color_space,
                                       const spk_params* p) {
    uint64_t h = 0xcbf29ce484222325ULL;  // FNV offset basis
    // 1) The expose input buffer (content + AE + crop/rescale already baked in).
    h = fnv1a64(h, rgb.data(), rgb.size() * sizeof(double));
    // 2) Geometry / domain scalars.
    h = fnv1a64(h, &width, sizeof(width));
    h = fnv1a64(h, &height, sizeof(height));
    h = fnv1a64(h, &input_color_space, sizeof(input_color_space));
    // 3) Film profile id bytes (NULL-safe).
    if (p->film_profile) h = fnv1a64(h, p->film_profile, std::strlen(p->film_profile));
    // 4) Filming pointwise params that change film_density_cmy.
    h = fnv1a64(h, &p->exposure_compensation_ev, sizeof(p->exposure_compensation_ev));
    h = fnv1a64(h, &p->density_curve_gamma, sizeof(p->density_curve_gamma));
    int32_t rgb2raw = static_cast<int32_t>(p->rgb_to_raw_method);
    h = fnv1a64(h, &rgb2raw, sizeof(rgb2raw));
    // Spectral-domain blur of the filming tc_lut changes film_density_cmy, so it
    // MUST be part of the print-route memo key (blur defaults to 0 -> no-op, key
    // unchanged from before for the default path).
    h = fnv1a64(h, &p->spectral_gaussian_blur, sizeof(p->spectral_gaussian_blur));
    // hanatos2025 window/surface adaptation toggles also change the filming tc_lut
    // (and therefore film_density_cmy), so they MUST be part of the print-route memo
    // key — otherwise toggling them returns a stale cached film_density_cmy. The
    // DEFAULTS (window=1, surface=0) keep the key unchanged from before for the
    // default path... but fold them ALWAYS so the digest is honest.
    h = fnv1a64(h, &p->apply_hanatos_window, sizeof(p->apply_hanatos_window));
    h = fnv1a64(h, &p->apply_hanatos_surface, sizeof(p->apply_hanatos_surface));
    // Camera UV/IR band-pass cut filter also modifies the filming tc_lut (and thus
    // film_density_cmy) on the print route, so it MUST be part of the memo key.
    // Defaults (amp 0/0) keep the key unchanged for the default path; fold ALWAYS
    // so the digest is honest.
    h = fnv1a64(h, p->camera_filter_uv, sizeof(p->camera_filter_uv));
    h = fnv1a64(h, p->camera_filter_ir, sizeof(p->camera_filter_ir));
    // Highlight boost (numba_boost_hightlights.boost_highlights) runs in expose()
    // BEFORE the log10, so it changes film_density_cmy and MUST be part of the
    // print-route memo key — otherwise a boost edit returns a stale cached negative.
    // boost_ev defaults to 0 (a strict no-op); fold all three ALWAYS so the digest is
    // honest. (Adding to the hash only forces a one-time recompute; the cached value
    // is always the correct recompute, so this is parity-transparent.)
    h = fnv1a64(h, &p->halation_boost_ev, sizeof(p->halation_boost_ev));
    h = fnv1a64(h, &p->halation_boost_range, sizeof(p->halation_boost_range));
    h = fnv1a64(h, &p->halation_protect_ev, sizeof(p->halation_protect_ev));
    // 5) DIR-coupler pointwise params (the only spatial-independent coupler inputs).
    h = fnv1a64(h, &p->dir_couplers_active, sizeof(p->dir_couplers_active));
    h = fnv1a64(h, &p->dir_amount, sizeof(p->dir_amount));
    h = fnv1a64(h, &p->dir_inhibition_samelayer, sizeof(p->dir_inhibition_samelayer));
    h = fnv1a64(h, &p->dir_inhibition_interlayer, sizeof(p->dir_inhibition_interlayer));
    // 6) DEFENSIVE: geometry params (already folded via `rgb`, repeated for safety)
    //    + the currently-forced-off toggles. If any of these ever start influencing
    //    the print-route filming output, the key changes and the memo can't go stale.
    h = fnv1a64(h, &p->crop, sizeof(p->crop));
    h = fnv1a64(h, p->crop_center, sizeof(p->crop_center));
    h = fnv1a64(h, p->crop_size, sizeof(p->crop_size));
    h = fnv1a64(h, &p->upscale_factor, sizeof(p->upscale_factor));
    h = fnv1a64(h, &p->film_format_mm, sizeof(p->film_format_mm));
    h = fnv1a64(h, &p->grain_active, sizeof(p->grain_active));
    h = fnv1a64(h, &p->halation_active, sizeof(p->halation_active));
    h = fnv1a64(h, &p->glare_active, sizeof(p->glare_active));
    h = fnv1a64(h, &p->camera_diffusion_active, sizeof(p->camera_diffusion_active));
    h = fnv1a64(h, &p->lens_blur_um, sizeof(p->lens_blur_um));
    h = fnv1a64(h, &p->auto_exposure, sizeof(p->auto_exposure));
    return h;
}

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

// Apply the camera/enlarger optical diffusion-filter params from spk_params onto
// the digested struct. The C API does not expose filter_family, so it stays at
// the schema default (black_pro_mist). active defaults to 0, so default params
// leave the diffusion filter inactive (a strict no-op). `is_camera` selects the
// camera (filming) vs enlarger (printing) field group.
void apply_user_diffusion_filter(spk::DiffusionFilterParams& d,
                                 const spk_params* p, bool is_camera) {
    d.family = spk::DiffusionFamily::kBlackProMist;  // schema default family.
    if (is_camera) {
        d.active = (p->camera_diffusion_active != 0);
        d.strength = p->camera_diffusion_strength;
        d.spatial_scale = p->camera_diffusion_spatial_scale;
        d.halo_warmth = p->camera_diffusion_halo_warmth;
        d.core_intensity = p->camera_diffusion_core_intensity;
        d.core_size = p->camera_diffusion_core_size;
        d.halo_intensity = p->camera_diffusion_halo_intensity;
        d.halo_size = p->camera_diffusion_halo_size;
        d.bloom_intensity = p->camera_diffusion_bloom_intensity;
        d.bloom_size = p->camera_diffusion_bloom_size;
    } else {
        d.active = (p->enlarger_diffusion_active != 0);
        d.strength = p->enlarger_diffusion_strength;
        d.spatial_scale = p->enlarger_diffusion_spatial_scale;
        d.halo_warmth = p->enlarger_diffusion_halo_warmth;
        d.core_intensity = p->enlarger_diffusion_core_intensity;
        d.core_size = p->enlarger_diffusion_core_size;
        d.halo_intensity = p->enlarger_diffusion_halo_intensity;
        d.halo_size = p->enlarger_diffusion_halo_size;
        d.bloom_intensity = p->enlarger_diffusion_bloom_intensity;
        d.bloom_size = p->enlarger_diffusion_bloom_size;
    }
}

// Build the crop/resize params from spk_params (mirrors IOParams' crop fields).
spk::CropResizeParams build_crop_resize(const spk_params* p) {
    spk::CropResizeParams cr;
    cr.crop = (p->crop != 0);
    cr.crop_center[0] = static_cast<double>(p->crop_center[0]);
    cr.crop_center[1] = static_cast<double>(p->crop_center[1]);
    cr.crop_size[0] = static_cast<double>(p->crop_size[0]);
    cr.crop_size[1] = static_cast<double>(p->crop_size[1]);
    cr.upscale_factor = p->upscale_factor != 0.0f
                            ? static_cast<double>(p->upscale_factor) : 1.0;
    return cr;
}

// Promote the incoming float32 RGB to float64 and apply pipeline._preprocess:
//   image = np.double(image[..., 0:3])
//   image = self._filming_stage.auto_exposure(image)   # metering + global gain
//   image = self._resize_service.crop_and_rescale(image)
// This runs BEFORE the filming stage. With default *parity* params (auto-exposure
// off, crop off, upscale 1.0) it is a pure passthrough that leaves width/height
// and pixel_size_um unchanged, so the existing goldens stay byte-identical.
//
// AUTO-EXPOSURE: when p->auto_exposure is set, FilmingStage.auto_exposure meters
// the luminance of small_preview(image) (a max-256 nearest downscale of the
// ORIGINAL, pre-crop image) under the chosen metering pattern, computes
// ev = -log2(metered/0.184), and scales the FULL-resolution float64 image by
// 2**ev — a single global gain applied before crop/rescale, exactly like the
// oracle. input_color_space is ProPhoto RGB (the engine's input space) and
// cctf decoding follows io.input_cctf_decoding (default off).
//
// `pixel_size_um` is computed here as film_format_mm*1000/max(h,w) on the
// ORIGINAL geometry (matching ResizingService, which sets it from the incoming
// shape before crop) and then divided by upscale_factor when a rescale runs.
void preprocess_geometry(const spk_image* in, const spk_params* p,
                         std::vector<double>* rgb, int* width, int* height,
                         double* pixel_size_um) {
    const int w = in->width, h = in->height;
    const double fmm = p->film_format_mm > 0.0f ? p->film_format_mm : 35.0;
    const int longest = w > h ? w : h;
    *pixel_size_um = fmm * 1000.0 / static_cast<double>(longest);

    std::vector<double> src(static_cast<size_t>(w) * h * 3);
    for (size_t i = 0; i < src.size(); ++i)
        src[i] = static_cast<double>(in->data[i]);

    // Auto-exposure (pipeline._preprocess -> FilmingStage.auto_exposure). Runs on
    // the original geometry, BEFORE crop_and_rescale. No-op when off.
    if (p->auto_exposure != 0) {
        spk::AeMethod method = spk::AeMethod::kCenterWeighted;
        const bool known = p->auto_exposure_method == nullptr
                               ? true  // NULL => schema default center_weighted
                               : spk::ae_method_from_string(
                                     p->auto_exposure_method, &method);
        spk::apply_auto_exposure(src.data(), w, h,
                                 spk::AeColorSpace::kProPhotoRGB,
                                 /*apply_cctf_decoding=*/p->input_cctf_decoding != 0,
                                 method, known);
    }

    spk::CropResizeParams cr = build_crop_resize(p);
    spk::crop_and_rescale(src.data(), w, h, cr, rgb, width, height,
                          pixel_size_um);
}

// Run the scan_film pipeline, producing display RGB plus the intermediate taps.
// `tap_*` pointers, when non-null, receive the corresponding intermediate.
spk_status run_scan_film(spk_engine* eng, const spk_image* in, const spk_params* p,
                         std::vector<float>* final_rgb,
                         std::vector<float>* tap_log_raw,
                         std::vector<float>* tap_density_cmy,
                         int* out_w = nullptr, int* out_h = nullptr) {
    if (!eng || !in || !p || !in->data) return SPK_ERR_BAD_ARGS;
    if (in->width <= 0 || in->height <= 0) return SPK_ERR_BAD_ARGS;
    if (!p->film_profile) return SPK_ERR_BAD_ARGS;

    // 0) Geometry preprocess (pipeline._preprocess -> crop_and_rescale). Runs
    //    BEFORE filming, mirroring Python ordering. Default params (crop off,
    //    upscale 1.0) leave the image, geometry and pixel_size_um unchanged.
    std::vector<double> rgb;
    int width = 0, height = 0;
    double resize_pixel_size_um = 0.0;
    preprocess_geometry(in, p, &rgb, &width, &height, &resize_pixel_size_um);
    const int npix = width * height;
    if (out_w) *out_w = width;
    if (out_h) *out_h = height;

    // 1) Load the film profile.
    spk::Profile film;
    try {
        film = load_engine_profile(eng, p->film_profile);
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
    spk::FilmingParams fparams = spk::digest_filming_params(
        film.is_negative(), spatial,
        !film.stock.empty() ? film.stock.c_str() : p->film_profile);
    fparams.exposure_compensation_ev = p->exposure_compensation_ev;
    const float g = p->density_curve_gamma != 0.0f ? p->density_curve_gamma : 1.0f;
    fparams.density_curve_gamma[0] = g;
    fparams.density_curve_gamma[1] = g;
    fparams.density_curve_gamma[2] = g;
    // DIR-coupler user params (amount / inhibition / diffusion); the per-channel
    // gamma matrices stay film-specific (baked by the digest).
    apply_user_dir_couplers(fparams.dir_couplers, p, spatial);
    // Camera optical diffusion filter (issue #6 exposed-but-inert param). The
    // oracle's digest_params zeroes camera.diffusion_filter.active under
    // deactivate_spatial_effects=True, so the diffusion filter belongs to the
    // spatial branch: it is only applied when spatial (== halation_active) is on.
    apply_user_diffusion_filter(fparams.diffusion_filter, p, /*is_camera=*/true);
    if (!spatial) fparams.diffusion_filter.active = false;
    // Camera lens blur (camera.lens_blur_um) — applied in expose() between the
    // diffusion filter and halation. The oracle's digest_params zeroes
    // camera.lens_blur_um under deactivate_spatial_effects=True, so it lives in the
    // spatial branch: only honoured when spatial (== halation_active) is on. Default
    // 0.0 µm => strict no-op, so default params stay bit-exact.
    fparams.lens_blur_um = spatial ? static_cast<double>(p->lens_blur_um) : 0.0;
    // pixel_size_um drives both the spatial kernels and the grain blur, so it
    // must be set whenever either spatial effects or grain are active. It comes
    // from the resize service (film_format_mm*1000/max(orig h,w), then /=
    // upscale_factor), computed in preprocess_geometry above.
    if (spatial || grain) {
        fparams.pixel_size_um = resize_pixel_size_um;
    }
    if (grain) {
        // grain_active && stochastic effects on -> AgX particle grain. The
        // density_max_curves are filled inside develop() from the film's
        // normalized density curves.
        spk::digest_grain_params(fparams);
        apply_user_grain(fparams.grain, p);
    }
    if (spatial) {
        // pixel_size_um already set from the resize service above.
        fparams.pixel_size_um = resize_pixel_size_um;
        spk::digest_halation_params(fparams, film.use.c_str(),
                                    film.antihalation.c_str(), true);
        // halation user params (everything except the preset-baked sigma/strength).
        apply_user_halation(fparams.halation, p);
    }

    // Highlight boost is NOT a spatial effect: the oracle applies boost_highlights in
    // filming.expose regardless of deactivate_spatial_effects (params_builder.py only
    // zeroes the scatter/halation sigmas, never boost_ev), and apply_highlight_boost
    // gates on boost_ev > 0. Thread the three boost params into fparams.halation
    // UNCONDITIONALLY so the boost is reachable on the spatial-OFF path too (where the
    // block above is skipped). Idempotent when spatial is ON. boost_ev defaults to 0
    // (schema/UI) -> a strict no-op, so default goldens stay bit-exact.
    fparams.halation.boost_ev = p->halation_boost_ev;
    fparams.halation.boost_range = p->halation_boost_range;
    fparams.halation.protect_ev = p->halation_protect_ev;

    // 3) Build (or reuse the engine-cached) Hanatos2025 filming tc_lut (D55
    //    reference illuminant). Cached by film id — byte-identical to rebuilding
    //    (see engine_tc_lut / the spk_engine cache note).
    const spk::NdArray* tc_lut_ptr = nullptr;
    try {
        tc_lut_ptr = &engine_tc_lut(eng, p->film_profile, film, p->spectral_gaussian_blur,
                                    p->apply_hanatos_window != 0,
                                    p->apply_hanatos_surface != 0,
                                    p->camera_filter_uv, p->camera_filter_ir);
    } catch (const std::exception&) {
        return SPK_ERR_ASSET_IO;
    }
    const spk::NdArray& tc_lut = *tc_lut_ptr;

    // 4) expose(): the image runs as float64 (ProPhoto linear). `rgb` was built
    //    by preprocess_geometry above (crop/rescale applied, float64), matching
    //    the Python pipeline.
    // Scanner BLACK/WHITE correction (scan_film route). Active only when a
    // correction is enabled AND the film is POSITIVE (color_reference.py returns a
    // no-op for negative film + the scan_film negative route). For positive film we
    // measure the reference Y values from the film density curves, build the shared
    // affine (m, q, midgray_corrected), and apply the FILMING exposure correction
    // here + the XYZ correction in scan() below. For negative film everything stays
    // a strict no-op so the default scan goldens are bit-exact.
    spk::ColorCorrection bw_corr;  // inactive by default
    const bool bw_on = (p->scanner_white_correction != 0) ||
                       (p->scanner_black_correction != 0);
    if (bw_on && film.is_positive()) {
        double y_black, y_white;
        spk::measure_scanfilm_references(film, &y_black, &y_white);
        bw_corr = spk::build_color_correction(
            y_black, y_white,
            spk::remove_srgb_cctf(static_cast<double>(p->scanner_black_level)),
            spk::remove_srgb_cctf(static_cast<double>(p->scanner_white_level)),
            p->scanner_black_correction != 0, p->scanner_white_correction != 0);
        fparams.bw_exposure_correction =
            spk::exposure_correction_factor(film, bw_corr, /*filming_positive=*/true);
    }

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
        // scanner.unsharp_mask = (sigma, amount); default (0.7, 0.7). scanner
        // lens_blur (in pixels) is applied before the unsharp mask. Both are part
        // of the spatial branch (the oracle's digest_params zeroes them under
        // deactivate_spatial_effects=True), so they are only honoured when spatial.
        sparams.unsharp_sigma = p->scanner_unsharp[0];
        sparams.unsharp_amount = p->scanner_unsharp[1];
        sparams.lens_blur = static_cast<double>(p->scanner_lens_blur);
    }
    // OPT-IN scanner 3D-LUT acceleration (settings.use_scanner_lut, default 0).
    // When off (the default + parity-gate path) scan() never constructs the LUT and
    // is byte-identical to the direct spectral evaluation. When on, scan() routes
    // density_cmy -> log_xyz through the PCHIP 3D LUT at settings.lut_resolution
    // (clamped), mirroring scanning.py::_density_to_rgb(use_lut=use_scanner_lut).
    if (p->use_scanner_lut != 0) {
        sparams.use_lut = true;
        sparams.lut_resolution = p->lut_resolution;
    }
    sparams.tone_curve = build_tone_curve_set(p);
    // Scanner BLACK/WHITE XYZ correction (scan_film route): apply the shared affine
    // (m, q) per pixel in scan(). Inactive (strict no-op) for negative film.
    if (bw_corr.active) {
        sparams.bw_xyz_correction = true;
        sparams.bw_xyz_m = bw_corr.m;
        sparams.bw_xyz_q = bw_corr.q;
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
                     std::vector<float>* tap_print_density_cmy,
                     int* out_w = nullptr, int* out_h = nullptr) {
    if (!eng || !in || !p || !in->data) return SPK_ERR_BAD_ARGS;
    if (in->width <= 0 || in->height <= 0) return SPK_ERR_BAD_ARGS;
    if (!p->film_profile || !p->print_profile) return SPK_ERR_BAD_ARGS;

    // 0) Geometry preprocess (crop_and_rescale) BEFORE filming, mirroring the
    //    Python pipeline._preprocess. Default params -> passthrough. The print
    //    route runs spatial/grain off, so pixel_size_um is not consumed here;
    //    only the geometry (width/height) can change.
    std::vector<double> rgb;
    int width = 0, height = 0;
    double resize_pixel_size_um = 0.0;
    preprocess_geometry(in, p, &rgb, &width, &height, &resize_pixel_size_um);
    const int npix = width * height;
    if (out_w) *out_w = width;
    if (out_h) *out_h = height;

    // 1) Load film + print profiles.
    spk::Profile film, prnt;
    try {
        film = load_engine_profile(eng, p->film_profile);
        prnt = load_engine_profile(eng, p->print_profile);
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

    // 2) Build (or reuse the engine-cached) Hanatos2025 filming tc_lut (D55
    //    reference illuminant). Needed both by the filming expose and by the
    //    native midgray digest below. Cached by film id — byte-identical to
    //    rebuilding (see engine_tc_lut / the spk_engine cache note).
    const spk::NdArray* tc_lut_ptr = nullptr;
    try {
        tc_lut_ptr = &engine_tc_lut(eng, p->film_profile, film, p->spectral_gaussian_blur,
                                    p->apply_hanatos_window != 0,
                                    p->apply_hanatos_surface != 0,
                                    p->camera_filter_uv, p->camera_filter_ir);
    } catch (const std::exception&) {
        return SPK_ERR_ASSET_IO;
    }
    const spk::NdArray& tc_lut = *tc_lut_ptr;

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
        // Read neutral_print_filters.json via the asset abstraction (FS or AAsset),
        // then resolve from the in-memory bytes. A missing/unreadable asset yields
        // defaults {0,0,0}, mirroring the Python FileNotFoundError branch.
        std::vector<char> nf;
        if (spk_read_asset(eng, kNeutralFiltersRel, nf)) {
            spk::resolve_neutral_cc_string(std::string(nf.data(), nf.size()),
                                           print_stock, "TH-KG3", film_stock,
                                           neutral_cc);
        } else {
            neutral_cc[0] = neutral_cc[1] = neutral_cc[2] = 0.0;
        }
    } else {
        // Use the schema neutral CC values directly (filter_enlarger_source uses
        // [c_filter_neutral, m_filter_neutral, y_filter_neutral] in CMY order).
        neutral_cc[0] = p->c_filter_neutral;
        neutral_cc[1] = p->m_filter_neutral;
        neutral_cc[2] = p->y_filter_neutral;
    }
    // Preserve the UN-SHIFTED neutral CC for the preflash filtered illuminant,
    // which applies its OWN m/y shifts to the neutral values (NOT the image
    // exposure's m/y shifts) per filter_enlarger_source.preflash_filtered_illuminant.
    const double base_neutral_cc[3] = {neutral_cc[0], neutral_cc[1], neutral_cc[2]};
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
        film, prnt, tc_lut, pparams.filtered_illuminant, pg,
        static_cast<double>(p->exposure_compensation_ev),
        p->normalize_print_exposure != 0, p->print_exposure_compensation != 0);
    // enlarger.print_exposure (default 1.0) multiplies the print exposure.
    pparams.print_exposure = p->print_exposure;

    // OPT-IN s023 print density-curve morph (print_render.density_curves_morph).
    // Default-off -> print_develop uses the stored density_curves table (the
    // parity-gate path). The morph is downstream of the cached film_density_cmy,
    // so it needs no film-cache-key fold (the cache memoises only film density).
    pparams.morph.active = p->print_morph_active != 0;
    pparams.morph.gamma_factor = p->print_morph_gamma_factor;
    pparams.morph.gamma_factor_fast = p->print_morph_gamma_factor_fast;
    pparams.morph.gamma_factor_slow = p->print_morph_gamma_factor_slow;
    pparams.morph.gamma_factor_red = p->print_morph_gamma_factor_red;
    pparams.morph.gamma_factor_green = p->print_morph_gamma_factor_green;
    pparams.morph.gamma_factor_blue = p->print_morph_gamma_factor_blue;
    pparams.morph.developer_exhaustion = p->print_morph_developer_exhaustion;

    // Enlarger PREFLASH (printing.py::_compute_raw_preflash via
    // filter_enlarger_source.preflash_filtered_illuminant). The preflash flashes
    // the paper through the enlarger with its OWN filter shifts off the neutral CC:
    //   preflash CC = [c_neutral, m_neutral + preflash_m_filter_shift,
    //                  y_neutral + preflash_y_filter_shift]
    // (the image-exposure m/y shifts are NOT applied to the preflash). print_expose
    // then adds raw_preflash * preflash_exposure (a constant per-channel 3-vector,
    // = sum_l 10^-base_density[l] * preflash_illuminant[l] * sens[l,k]) to the print
    // raw, after the midgray factor. preflash_exposure default 0.0 => print_expose's
    // `if preflash_exposure > 0` guard makes it a STRICT no-op.
    pparams.preflash_exposure = static_cast<double>(p->preflash_exposure);
    if (pparams.preflash_exposure > 0.0) {
        double preflash_cc[3] = {
            base_neutral_cc[0],
            base_neutral_cc[1] + static_cast<double>(p->preflash_m_filter_shift),
            base_neutral_cc[2] + static_cast<double>(p->preflash_y_filter_shift),
        };
        spk::color_enlarger(enl, preflash_cc, pparams.preflash_illuminant);
    }
    // OPT-IN enlarger 3D-LUT acceleration (settings.use_enlarger_lut, default 0).
    // When off (the default + parity-gate path) print_expose never builds the LUT
    // and is byte-identical to the direct spectral integral. When on, print_expose
    // routes film density_cmy -> print log_raw through the PCHIP 3D LUT at
    // settings.lut_resolution (clamped), mirroring printing.py::expose
    // (spectral_compute_enlarger, use_lut=use_enlarger_lut). The print-route final
    // scan() still honours use_scanner_lut independently below.
    if (p->use_enlarger_lut != 0) {
        pparams.use_enlarger_lut = true;
        pparams.lut_resolution = p->lut_resolution;
        pparams.grain_density_min[0] = static_cast<double>(p->grain_density_min[0]);
        pparams.grain_density_min[1] = static_cast<double>(p->grain_density_min[1]);
        pparams.grain_density_min[2] = static_cast<double>(p->grain_density_min[2]);
    }
    // Spatial branch toggle for the print route (mirrors the negative scan:
    // deactivate_spatial_effects=False enables the spatial filters). Driven by
    // halation_active, matching run_scan_film.
    const bool print_spatial = (p->halation_active != 0);
    // Stochastic branch toggle for the print route (mirrors the negative scan:
    // deactivate_stochastic_effects=False enables grain + viewing glare). Used to
    // gate the print-route viewing glare below.
    const bool print_stochastic = (p->grain_active != 0);
    // Enlarger optical diffusion filter (issue #6 exposed-but-inert param),
    // applied in print_expose on the float64 print irradiance before the final
    // log10. Gated on the spatial branch (the oracle's digest_params zeroes
    // enlarger.diffusion_filter.active under deactivate_spatial_effects=True).
    // Inactive by default -> strict no-op. pixel_size_um from the resize service
    // drives the µm->pixel conversion when active.
    apply_user_diffusion_filter(pparams.diffusion_filter, p, /*is_camera=*/false);
    if (!print_spatial) pparams.diffusion_filter.active = false;
    if (pparams.diffusion_filter.active)
        pparams.pixel_size_um = resize_pixel_size_um;

    // Scanner BLACK/WHITE correction (print route). Active only when a correction is
    // enabled AND the print paper is NEGATIVE (color_reference.py's
    // black_white_printing_exposure_correction computes only for print.type ==
    // 'negative'; the xyz correction is active on the whole print route). The two
    // reference Y values come from develop_simple of the print reference log_raw
    // vectors, which are _film_cmy_to_print_log_raw of the film black/white
    // reference CMY densities (cmy_film_black = -grain.density_min, cmy_film_white =
    // nanmax(film.density_curves)) through the LIVE enlarger params (pparams must be
    // fully built — filtered illuminant, midgray factor, preflash — by here). We
    // then set the PRINTING exposure correction (pparams.bw_exposure_correction,
    // already plumbed into print_expose) + the XYZ correction in the print scan()
    // below. Default-off / non-negative-paper => strict no-op, so the print goldens
    // stay bit-exact.
    spk::ColorCorrection bw_corr;  // inactive by default
    const bool bw_on = (p->scanner_white_correction != 0) ||
                       (p->scanner_black_correction != 0);
    if (bw_on && prnt.is_negative()) {
        // cmy_film_black = -grain.density_min; cmy_film_white = nanmax(film curves).
        double cmy_film_black[3] = {
            -static_cast<double>(p->grain_density_min[0]),
            -static_cast<double>(p->grain_density_min[1]),
            -static_cast<double>(p->grain_density_min[2])};
        double cmy_film_white[3] = {-INFINITY, -INFINITY, -INFINITY};
        for (int n = 0; n < film.n_density_pts; ++n) {
            const float* dc =
                film.density_curves.data() + static_cast<size_t>(n) * 3;
            for (int k = 0; k < 3; ++k) {
                double v = static_cast<double>(dc[k]);
                if (!std::isnan(v) && v > cmy_film_white[k]) cmy_film_white[k] = v;
            }
        }
        double log_raw_black[3], log_raw_white[3];
        spk::print_reference_log_raw(film, prnt, pparams, cmy_film_black,
                                     log_raw_black);
        spk::print_reference_log_raw(film, prnt, pparams, cmy_film_white,
                                     log_raw_white);
        double y_black, y_white;
        spk::measure_print_references(prnt, log_raw_black, log_raw_white, pg,
                                      &y_black, &y_white);
        bw_corr = spk::build_color_correction(
            y_black, y_white,
            spk::remove_srgb_cctf(static_cast<double>(p->scanner_black_level)),
            spk::remove_srgb_cctf(static_cast<double>(p->scanner_white_level)),
            p->scanner_black_correction != 0, p->scanner_white_correction != 0);
        pparams.bw_exposure_correction = spk::exposure_correction_factor(
            prnt, bw_corr, /*filming_positive=*/false);
    }

    // 4) Filming stage (rgb -> film density_cmy), reusing the bit-exact port.
    //    The print route runs the negative with spatial+stochastic effects off
    //    (matching the print_portra parity toggles), so only the pointwise
    //    DIR-coupler user params apply here.
    spk::FilmingParams fparams = spk::digest_filming_params(
        film.is_negative(), /*spatial_effects=*/false,
        !film.stock.empty() ? film.stock.c_str() : p->film_profile);
    fparams.exposure_compensation_ev = p->exposure_compensation_ev;
    const float fg = p->density_curve_gamma != 0.0f ? p->density_curve_gamma : 1.0f;
    fparams.density_curve_gamma[0] = fg;
    fparams.density_curve_gamma[1] = fg;
    fparams.density_curve_gamma[2] = fg;
    apply_user_dir_couplers(fparams.dir_couplers, p, /*spatial=*/false);
    // The print route's negative-filming step runs with the spatial branch OFF
    // (the print parity goldens keep deactivate_spatial_effects=True), so the
    // camera diffusion filter is not applied here (it lives in the spatial
    // branch). fparams.spatial_effects stays false -> the filming stage gate
    // skips it regardless.
    fparams.diffusion_filter.active = false;
    // Highlight boost is NOT a spatial effect (it runs in filming.expose regardless of
    // deactivate_spatial_effects). The print route's negative-filming runs spatial-OFF,
    // so thread the boost params in directly; apply_highlight_boost gates on
    // boost_ev > 0. Folded into compute_film_cache_key, so a boost edit busts the
    // film-density memo. boost_ev defaults to 0 -> a strict no-op.
    fparams.halation.boost_ev = p->halation_boost_ev;
    fparams.halation.boost_range = p->halation_boost_range;
    fparams.halation.protect_ev = p->halation_protect_ev;

    // `rgb` (float64, crop/rescale applied) built by preprocess_geometry above.

    // Print-route film_density_cmy memo. On this route filming runs spatial-OFF and
    // grain-OFF (set above: dir spatial=false, diffusion_filter.active=false, and
    // run_print never touches grain), so expose()+develop() is a PURE deterministic
    // function of (rgb, width, height, color_space, filming params) with NO
    // stochastic/grain state. We can therefore memoize the developed film_density_cmy
    // in a single content-hashed slot on the engine and skip expose+develop on the
    // next call whose filming inputs are byte-identical (a downstream-only edit such
    // as enlarger filters, scanner, or tone curve). Everything downstream runs
    // unchanged on every call, so the cache is transparent and bit-exact.
    //
    // Cache is BYPASSED (always recompute) when:
    //   (a) a debug tap (log_raw / film_density_cmy) is requested — keeps the tap
    //       path byte-identical to today and avoids caching tap-only renders, AND
    //   (b) DEFENSIVELY, if the print-route filming step would ever run with the
    //       spatial OR grain branch on — converts a future parity break into a safe
    //       bypass rather than serving a stochastic/spatial result from the memo.
    std::vector<float> film_density_cmy(static_cast<size_t>(npix) * 3);
    const bool tap_bypass = (tap_log_raw != nullptr) || (tap_film_density_cmy != nullptr);
    // print_spatial/print_stochastic are the print route's branch toggles; on this
    // route the filming step is run pointwise regardless, but if either branch is on
    // we refuse to cache (and the recompute path below is unaffected).
    const bool stochastic_or_spatial = print_spatial || print_stochastic;
    const bool use_film_cache = !tap_bypass && !stochastic_or_spatial;

    bool film_cache_hit = false;
    if (use_film_cache) {
        const uint64_t key = compute_film_cache_key(rgb, width, height,
                                                    in->color_space, p);
        std::lock_guard<std::mutex> g(eng->film_cache_mutex);
        if (eng->film_cache_valid && eng->film_cache_key == key &&
            eng->film_cache.width == width && eng->film_cache.height == height &&
            eng->film_cache.film_density_cmy.size() == film_density_cmy.size()) {
            // HIT: copy the cached buffer out BY VALUE while holding the lock.
            film_density_cmy = eng->film_cache.film_density_cmy;
            ++eng->film_cache_hits;
            film_cache_hit = true;
        }
        // MISS path is handled after unlocking (we don't compute under the lock).
    }

    if (!film_cache_hit) {
        std::vector<float> film_log_raw(static_cast<size_t>(npix) * 3);
        spk::expose(rgb.data(), width, height, fparams, tc_lut, film_log_raw.data());
        if (tap_log_raw) *tap_log_raw = film_log_raw;
        spk::develop(film_log_raw.data(), width, height, film, fparams,
                     film_density_cmy.data());
        if (use_film_cache) {
            // Store {width, height, film_density_cmy} + key under the lock, count miss.
            const uint64_t key = compute_film_cache_key(rgb, width, height,
                                                        in->color_space, p);
            std::lock_guard<std::mutex> g(eng->film_cache_mutex);
            eng->film_cache.width = width;
            eng->film_cache.height = height;
            eng->film_cache.film_density_cmy = film_density_cmy;
            eng->film_cache_key = key;
            eng->film_cache_valid = true;
            ++eng->film_cache_misses;
        }
    }
    if (tap_film_density_cmy) *tap_film_density_cmy = film_density_cmy;

    // 4) Printing stage (film density -> enlarger expose -> print develop).
    std::vector<float> print_log_raw(static_cast<size_t>(npix) * 3);
    spk::print_expose(film, prnt, pparams, film_density_cmy.data(), width, height,
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
    // Viewing glare on the PRINT route (scanning.py applies glare = print_render.glare
    // here, in XYZ space, before XYZ->RGB). It is STOCHASTIC (per-pixel lognormal via
    // np.random.randn) so an active result is NOT bit-exact vs the oracle (exactly
    // like grain). The oracle's digest_params sets print_render.glare.active = False
    // under deactivate_stochastic_effects=True, i.e. glare is part of the STOCHASTIC
    // branch — the same branch grain belongs to. The print parity goldens were
    // generated with deactivate_stochastic_effects=True, so glare was OFF and the
    // default native print output (glare off) stays bit-exact.
    //
    // We therefore gate native glare on the stochastic branch (proxied by
    // grain_active, the engine's stochastic toggle, mirroring how the oracle ties
    // both grain and glare to deactivate_stochastic_effects) AND print_glare_active.
    // With the parity toggles (grain_active == 0) glare never runs. When a caller
    // turns the stochastic branch on AND enables print glare, the effect is applied
    // at the oracle position but held to a visual (not bit-exact) tolerance.
    if (print_stochastic && p->print_glare_active != 0) {
        sparams.glare_active = true;
        sparams.glare_percent = p->print_glare_percent;
        sparams.glare_roughness = p->print_glare_roughness;
        sparams.glare_blur = p->print_glare_blur;
    }
    // Scanner lens blur + unsharp on the print route (scanning.py runs the same
    // _apply_blur_and_unsharp on both routes). Part of the spatial branch (the
    // oracle zeroes scanner.lens_blur / unsharp under deactivate_spatial_effects),
    // so honoured only when the print spatial branch is on; default 0 => no-op, so
    // the print parity goldens stay bit-exact.
    if (print_spatial) {
        sparams.lens_blur = static_cast<double>(p->scanner_lens_blur);
        sparams.unsharp_sigma = p->scanner_unsharp[0];
        sparams.unsharp_amount = p->scanner_unsharp[1];
    }
    // OPT-IN scanner 3D-LUT acceleration on the print-scan route (same
    // settings.use_scanner_lut gate; scan_film == false so scan() picks the print
    // density-curve domain bounds). Default 0 => never constructed, print goldens
    // stay bit-exact.
    if (p->use_scanner_lut != 0) {
        sparams.use_lut = true;
        sparams.lut_resolution = p->lut_resolution;
    }
    sparams.tone_curve = build_tone_curve_set(p);
    // Scanner BLACK/WHITE XYZ correction (print route): apply the shared affine
    // (m, q) per pixel in scan(). Inactive (strict no-op) by default / when the
    // print paper is not negative.
    if (bw_corr.active) {
        sparams.bw_xyz_correction = true;
        sparams.bw_xyz_m = bw_corr.m;
        sparams.bw_xyz_q = bw_corr.q;
    }
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
    // Schema default metering = "center_weighted"; NULL selects it. (The JNI
    // marshaller may later read CameraParams.auto_exposure_method; leaving it
    // NULL keeps the schema default until then.)
    p->auto_exposure_method = nullptr;
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

    // s023 print density-curve morph: OFF + identity (a strict no-op default).
    p->print_morph_active = 0;
    p->print_morph_gamma_factor = 1.0f;
    p->print_morph_gamma_factor_fast = 1.0f;
    p->print_morph_gamma_factor_slow = 1.0f;
    p->print_morph_gamma_factor_red = 1.0f;
    p->print_morph_gamma_factor_green = 1.0f;
    p->print_morph_gamma_factor_blue = 1.0f;
    p->print_morph_developer_exhaustion = 0.0f;

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

    // tone curve: OFF / identity by default (strict no-op => goldens stay bit-exact).
    p->tone_curve_active = 0;
    p->tone_curve_master_n = 0;
    p->tone_curve_rgb_n[0] = 0;
    p->tone_curve_rgb_n[1] = 0;
    p->tone_curve_rgb_n[2] = 0;
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
    if (!asset_dir) return SPK_ERR_BAD_ARGS;  // filesystem mode requires a dir.
    auto eng = std::make_unique<spk_engine>();
    eng->asset_dir = asset_dir;
    eng->profiles_dir = join_path(eng->asset_dir, "profiles");
    *out = eng.release();
    return SPK_OK;
}

spk_status spk_engine_create_asset_manager(void* aasset_manager, spk_engine** out) {
    if (!out) return SPK_ERR_BAD_ARGS;
#ifdef __ANDROID__
    if (!aasset_manager) return SPK_ERR_BAD_ARGS;
    auto eng = std::make_unique<spk_engine>();
    eng->asset_mgr = static_cast<AAssetManager*>(aasset_manager);
    // asset_base defaults to "spektra" (the bundled tree lives at assets/spektra/).
    // FS dirs (asset_dir/profiles_dir) stay empty: spk_read_asset uses the AAsset
    // path when asset_mgr is non-null.
    *out = eng.release();
    return SPK_OK;
#else
    (void)aasset_manager;
    return SPK_ERR_BAD_ARGS;  // no AAssetManager off Android (host tests use FS mode).
#endif
}

void spk_engine_destroy(spk_engine* eng) { delete eng; }

spk_status spk_engine_list_profiles(spk_engine* eng, char* buf, size_t buf_len,
                                    size_t* needed) {
    if (!eng) return SPK_ERR_BAD_ARGS;
    std::string list;
#ifdef __ANDROID__
    if (eng->use_asset_mgr()) {
        std::string dir = eng->asset_base.empty()
                              ? std::string("profiles")
                              : eng->asset_base + "/profiles";
        AAssetDir* ad = AAssetManager_openDir(eng->asset_mgr, dir.c_str());
        if (!ad) return SPK_ERR_ASSET_IO;
        const char* fname;
        while ((fname = AAssetDir_getNextFileName(ad)) != nullptr) {
            std::string name = fname;
            if (ends_with(name, ".json")) {
                if (!list.empty()) list += '\n';
                list += name.substr(0, name.size() - 5);  // strip ".json"
            }
        }
        AAssetDir_close(ad);
    } else
#endif
    {
        DIR* d = opendir(eng->profiles_dir.c_str());
        if (!d) return SPK_ERR_ASSET_IO;
        struct dirent* ent;
        while ((ent = readdir(d)) != nullptr) {
            std::string name = ent->d_name;
            if (ends_with(name, ".json")) {
                if (!list.empty()) list += '\n';
                list += name.substr(0, name.size() - 5);  // strip ".json"
            }
        }
        closedir(d);
    }

    size_t req = list.size() + 1;  // include NUL
    if (needed) *needed = req;
    if (!buf || buf_len < req) return SPK_ERR_BAD_ARGS;  // caller resizes & retries
    std::memcpy(buf, list.c_str(), req);
    return SPK_OK;
}

spk_status spk_simulate(spk_engine* eng, const spk_image* in, const spk_params* p,
                        spk_image* out) {
    // Validate every pointer before any deref, matching spk_simulate_preview /
    // spk_simulate_tap. `in` is read for width/height just below, so guard it
    // (and its data) here rather than relying on the run_* callees.
    if (!eng || !in || !p || !out || !in->data) return SPK_ERR_BAD_ARGS;
    std::vector<float> rgb;
    spk_status st;
    int ow = in->width, oh = in->height;
    if (p->scan_film) {
        st = run_scan_film(eng, in, p, &rgb, nullptr, nullptr, &ow, &oh);
    } else {
        // Print (enlarger) route: filming -> printing -> scan(print).
        st = run_print(eng, in, p, &rgb, nullptr, nullptr, nullptr, &ow, &oh);
    }
    if (st != SPK_OK) return st;
    return fill_out_image(out, rgb, ow, oh,
                          static_cast<int>(p->output_color_space));
}

spk_status spk_simulate_preview(spk_engine* eng, const spk_image* in,
                                const spk_params* p, spk_image* out) {
    if (!eng || !in || !p || !out || !in->data) return SPK_ERR_BAD_ARGS;
    // Proxy-approximate / export-exact (docs/PERF_ROADMAP.md): the interactive preview
    // runs the approximate LUT fast-path (the scanner density->log_xyz spectral integral
    // is PCHIP-interpolated through a 3D LUT instead of evaluated per pixel, ~5e-5 vs the
    // direct path — see runtime/stages/scanning.h). spk_simulate (export) keeps the exact
    // direct spectral evaluation, so the bit-exact parity goldens are untouched. We copy
    // the caller's params and force the LUT on for the preview only.
    spk_params pp = *p;
    if (pp.use_scanner_lut == 0) {
        pp.use_scanner_lut = 1;
        if (pp.lut_resolution < 2) pp.lut_resolution = 17;
    }
    p = &pp;
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
    if (!out || !p || !tap_name || !in) return SPK_ERR_BAD_ARGS;

    std::string tap = tap_name;
    std::vector<float> log_raw, film_density_cmy, print_density_cmy, final_rgb;
    spk_status st;
    // Output geometry after the crop/resize preprocess (== input geometry under
    // default params); written by the run_* functions.
    int ow = in->width, oh = in->height;

    if (p->scan_film) {
        // scan_film route: only the negative taps + final RGB exist.
        if (tap == "film_log_raw") {
            st = run_scan_film(eng, in, p, nullptr, &log_raw, nullptr, &ow, &oh);
            if (st != SPK_OK) return st;
            return fill_out_image(out, log_raw, ow, oh, in->color_space);
        } else if (tap == "film_density_cmy") {
            st = run_scan_film(eng, in, p, nullptr, &log_raw, &film_density_cmy,
                               &ow, &oh);
            if (st != SPK_OK) return st;
            return fill_out_image(out, film_density_cmy, ow, oh, in->color_space);
        } else if (tap == "final_rgb") {
            st = run_scan_film(eng, in, p, &final_rgb, nullptr, nullptr, &ow, &oh);
            if (st != SPK_OK) return st;
            return fill_out_image(out, final_rgb, ow, oh,
                                  static_cast<int>(p->output_color_space));
        }
        return SPK_ERR_BAD_ARGS;  // unknown / print-only tap on scan_film route
    }

    // Print route taps (filming -> printing -> scan).
    if (tap == "film_log_raw") {
        st = run_print(eng, in, p, nullptr, &log_raw, nullptr, nullptr, &ow, &oh);
        if (st != SPK_OK) return st;
        return fill_out_image(out, log_raw, ow, oh, in->color_space);
    } else if (tap == "film_density_cmy") {
        st = run_print(eng, in, p, nullptr, nullptr, &film_density_cmy, nullptr,
                       &ow, &oh);
        if (st != SPK_OK) return st;
        return fill_out_image(out, film_density_cmy, ow, oh, in->color_space);
    } else if (tap == "print_density_cmy") {
        st = run_print(eng, in, p, nullptr, nullptr, nullptr, &print_density_cmy,
                       &ow, &oh);
        if (st != SPK_OK) return st;
        return fill_out_image(out, print_density_cmy, ow, oh, in->color_space);
    } else if (tap == "final_rgb") {
        st = run_print(eng, in, p, &final_rgb, nullptr, nullptr, nullptr, &ow, &oh);
        if (st != SPK_OK) return st;
        return fill_out_image(out, final_rgb, ow, oh,
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
    // The .cube lattice is a synthetic count x 1 image; geometry transforms must
    // not touch it (a crop/rescale would corrupt the lattice -> wrong LUT).
    bp.crop = 0;
    bp.upscale_factor = 1.0f;

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

    out += "# Spektrafilm for Android — baked film-look 3D LUT (.cube)\n";
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

    std::snprintf(line, sizeof(line), "TITLE \"Spektrafilm %s\"\n",
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
