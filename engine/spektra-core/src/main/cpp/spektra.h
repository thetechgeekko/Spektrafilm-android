/*
 * Spektrafilm for Android — native engine C API.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
 *
 * --------------------------------------------------------------------------------
 * This header is the stable contract between the JVM/JNI layer and the native
 * engine. It mirrors spektrafilm's public surface: simulate(image, params) and the
 * RuntimePhotoParams tree. Implementation lands in M3–M4 (see docs/PORTING_PLAN.md);
 * in M0 these are declarations only.
 * --------------------------------------------------------------------------------
 */
#ifndef SPEKTRA_H
#define SPEKTRA_H

#include <cstdint>
#include <cstddef>

#ifdef __cplusplus
extern "C" {
#endif

/* Spectral working shape, matching spektrafilm/config.py exactly:
 * SpectralShape(380, 780, 5) -> 81 samples at 5 nm. */
#define SPK_SPECTRAL_MIN_NM 380
#define SPK_SPECTRAL_MAX_NM 780
#define SPK_SPECTRAL_STEP_NM 5
#define SPK_SPECTRAL_SAMPLES 81

/* Max control points per tone-curve channel (master / R / G / B). 16 matches the
 * Lightroom point-curve cap and keeps spk_params a fixed-size POD across JNI. */
#define SPK_TONE_MAX_PTS 16

typedef enum {
    SPK_OK = 0,
    SPK_ERR_BAD_ARGS = 1,
    SPK_ERR_PROFILE_NOT_FOUND = 2,
    SPK_ERR_ASSET_IO = 3,
    SPK_ERR_OOM = 4,
    SPK_ERR_INTERNAL = 5,
} spk_status;

/* RGB→spectral upsampling method (settings.rgb_to_raw_method). */
typedef enum { SPK_RGB2RAW_HANATOS2025 = 0, SPK_RGB2RAW_MALLETT2019 = 1 } spk_rgb2raw;

/* White-balance mode for RAW import (mirrors the GUI 'import raw' modes). */
typedef enum {
    SPK_WB_AS_SHOT = 0, SPK_WB_DAYLIGHT = 1, SPK_WB_TUNGSTEN = 2, SPK_WB_CUSTOM = 3
} spk_wb_mode;

/* Opaque engine handle. Holds asset manager, profile catalog, and LUT caches. */
typedef struct spk_engine spk_engine;

/* A linear, scene-referred image buffer (planar interleaved RGB, row-major). */
typedef struct {
    float*   data;       /* length = width*height*3, linear (gamma 1.0) */
    int32_t  width;
    int32_t  height;
    int32_t  color_space; /* spk_color_space of `data` (e.g. linear ProPhoto/ACES) */
} spk_image;

/* Output color spaces (io.output_color_space). */
typedef enum {
    SPK_CS_SRGB = 0, SPK_CS_ADOBE_RGB = 1, SPK_CS_PROPHOTO = 2,
    SPK_CS_REC2020 = 3, SPK_CS_ACES2065_1 = 4, SPK_CS_LINEAR_SRGB = 5
} spk_color_space;

/*
 * Flat parameter struct mirroring RuntimePhotoParams (see SpektraParams.kt and
 * spektrafilm/runtime/params_schema.py). Kept flat (no nested allocations) so it
 * marshals trivially across JNI. Only the high-traffic fields are listed here in
 * M0; the full set is filled in alongside the implementation. Profiles are passed
 * by name and resolved from bundled assets.
 */
typedef struct {
    const char* film_profile;   /* e.g. "kodak_portra_400" */
    const char* print_profile;  /* e.g. "kodak_portra_endura" */

    /* camera */
    float exposure_compensation_ev;
    int32_t auto_exposure;        /* bool */
    float lens_blur_um;
    float film_format_mm;
    /* auto-exposure metering pattern (CameraParams.auto_exposure_method). Only
     * consumed when auto_exposure != 0. NULL => the schema default
     * "center_weighted". Allowed: "center_weighted" (default), "average",
     * "median", "partial", "matrix", "multi_zone", "highlight_weighted". An
     * unrecognised string reproduces the oracle's else-branch (EV = 0). */
    const char* auto_exposure_method;

    /* enlarger */
    float y_filter_shift, m_filter_shift;
    float preflash_exposure;
    int32_t normalize_print_exposure; /* bool */

    /* film rendering toggles */
    float density_curve_gamma;
    int32_t grain_active;             /* bool */
    int32_t halation_active;          /* bool */
    int32_t dir_couplers_active;      /* bool */
    int32_t glare_active;             /* bool */

    /* io / settings */
    int32_t scan_film;                /* bool: skip print, scan negative directly */
    spk_color_space output_color_space;
    int32_t output_cctf_encoding;     /* bool */
    spk_rgb2raw rgb_to_raw_method;
    int32_t preview_max_size;         /* downscale target for simulate_preview */

    /* ====================================================================
     * Full RuntimePhotoParams surface (appended; existing fields/offsets above
     * are unchanged so the marshalled prefix stays compatible). Every field is
     * initialised to its physical default by the JNI marshaller (and by
     * spk_default_params for host tests) so a default-constructed SpektraParams
     * reproduces the goldens byte-for-byte. RGB triples are fixed-size [3]
     * arrays; Pairs are [2] arrays.
     * ==================================================================== */

    /* --- camera (extended) --- */
    float camera_filter_uv[3];        /* (strength, center_nm, sigma_nm) */
    float camera_filter_ir[3];        /* (strength, center_nm, sigma_nm) */
    /* camera diffusion filter (Black Pro-Mist family) */
    int32_t camera_diffusion_active;  /* bool */
    float camera_diffusion_strength;
    float camera_diffusion_spatial_scale;
    float camera_diffusion_halo_warmth;
    float camera_diffusion_core_intensity;
    float camera_diffusion_core_size;
    float camera_diffusion_halo_intensity;
    float camera_diffusion_halo_size;
    float camera_diffusion_bloom_intensity;
    float camera_diffusion_bloom_size;

    /* --- enlarger (extended) --- */
    float print_exposure;
    int32_t print_exposure_compensation;  /* bool */
    float y_filter_neutral, m_filter_neutral, c_filter_neutral;
    float enlarger_lens_blur;
    float preflash_y_filter_shift, preflash_m_filter_shift;
    /* enlarger diffusion filter */
    int32_t enlarger_diffusion_active;    /* bool */
    float enlarger_diffusion_strength;
    float enlarger_diffusion_spatial_scale;
    float enlarger_diffusion_halo_warmth;
    float enlarger_diffusion_core_intensity;
    float enlarger_diffusion_core_size;
    float enlarger_diffusion_halo_intensity;
    float enlarger_diffusion_halo_size;
    float enlarger_diffusion_bloom_intensity;
    float enlarger_diffusion_bloom_size;

    /* --- scanner --- */
    float scanner_lens_blur;
    float scanner_unsharp[2];          /* (sigma, amount) */
    int32_t scanner_white_correction;  /* bool */
    int32_t scanner_black_correction;  /* bool */
    float scanner_white_level;
    float scanner_black_level;

    /* --- film rendering: grain --- */
    int32_t grain_sublayers_active;    /* bool */
    float grain_particle_area_um2;
    float grain_particle_scale[3];
    float grain_particle_scale_layers[3];
    float grain_density_min[3];
    float grain_uniformity[3];
    float grain_blur;
    float grain_blur_dye_clouds_um;
    float grain_micro_structure[2];    /* (blur_um, sigma_nm) */
    int32_t grain_n_sub_layers;

    /* --- film rendering: halation --- */
    float halation_scatter_amount;
    float halation_scatter_spatial_scale;
    float halation_halation_amount;
    float halation_halation_spatial_scale;
    float halation_scatter_core_um[3];
    float halation_scatter_tail_um[3];
    float halation_scatter_tail_weight[3];
    float halation_boost_ev;
    float halation_boost_range;
    float halation_protect_ev;
    float halation_strength[3];
    float halation_first_sigma_um[3];
    int32_t halation_n_bounces;
    float halation_bounce_decay;
    int32_t halation_renormalize;      /* bool */

    /* --- film rendering: DIR couplers --- */
    float dir_amount;
    float dir_inhibition_samelayer;
    float dir_inhibition_interlayer;
    float dir_gamma_samelayer_rgb[3];
    float dir_gamma_interlayer_r_to_gb[2];
    float dir_gamma_interlayer_g_to_rb[2];
    float dir_gamma_interlayer_b_to_rg[2];
    float dir_diffusion_size_um;
    float dir_diffusion_tail_um;
    float dir_diffusion_tail_weight;

    /* --- film/print rendering: glare --- */
    float glare_percent;
    float glare_roughness;
    float glare_blur;
    int32_t print_glare_active;        /* bool (print_render.glare.active) */
    float print_glare_percent;
    float print_glare_roughness;
    float print_glare_blur;
    float print_density_curve_gamma;

    /* --- io (extended) --- */
    int32_t input_cctf_decoding;       /* bool */
    int32_t crop;                      /* bool */
    float crop_center[2];
    float crop_size[2];
    float upscale_factor;

    /* --- settings (extended) --- */
    int32_t apply_hanatos_window;      /* bool */
    int32_t apply_hanatos_surface;     /* bool */
    float spectral_gaussian_blur;
    /* OPT-IN 3D-LUT acceleration of the spectral density->log_xyz transforms.
     * Both default 0/false (spk_default_params) so the default engine path is the
     * direct, bit-exact spectral evaluation and never constructs a LUT.
     *   use_scanner_lut: WIRED. Gates the scanner LUT in scan() for BOTH the
     *     scan_film route (run_scan) and the print-scan route (run_print). When
     *     on, density_cmy->log_xyz is PCHIP-interpolated through a per-channel 3D
     *     LUT built at lut_resolution; result is within ~5e-5 of the direct path
     *     (NOT bit-exact by design). Gated by tests/test_scanner_lut_e2e.cpp.
     *   use_enlarger_lut: RESERVED / not yet wired. The oracle also LUT-accelerates
     *     the enlarger expose (printing.py via spectral_compute_enlarger), but that
     *     path is materially more involved (full enlarger spectral chain) and is
     *     left for a follow-up; this flag is read by JNI but currently inert in the
     *     native print path. Wiring scanner-only keeps this pass low-risk. */
    int32_t use_enlarger_lut;          /* bool (RESERVED, not yet wired) */
    int32_t use_scanner_lut;           /* bool (wired: opt-in scanner LUT) */
    int32_t lut_resolution;            /* LUT steps/axis; clamped to [2,192] */
    int32_t neutral_print_filters_from_database; /* bool */

    /* --- tone curve (NEW, optional; applied to final display RGB) ---
     * A Lightroom-style point curve on the output RGB, per channel. Control points
     * are (x,y) in [0,1] with x strictly increasing; a master curve applies to all
     * channels and per-R/G/B curves apply first. Default OFF / identity:
     * tone_curve_active = 0 and all counts 0 => strict no-op, so the parity goldens
     * (which carry no curve) stay bit-exact. A count < 2 for any curve is identity. */
    int32_t tone_curve_active;         /* bool; 0 => stage skipped entirely */
    int32_t tone_curve_master_n;       /* master control-point count (0..SPK_TONE_MAX_PTS) */
    float   tone_curve_master_x[SPK_TONE_MAX_PTS];
    float   tone_curve_master_y[SPK_TONE_MAX_PTS];
    int32_t tone_curve_rgb_n[3];       /* per-channel R,G,B point counts */
    float   tone_curve_rgb_x[3][SPK_TONE_MAX_PTS];
    float   tone_curve_rgb_y[3][SPK_TONE_MAX_PTS];
} spk_params;

/* Initialise `p` to the physical defaults that mirror a default-constructed
 * Kotlin SpektraParams (so default params reproduce the goldens). film/print
 * profile pointers are left untouched (caller sets them). Used by host tests;
 * the JNI marshaller writes the same defaults before reading the jobject. */
void spk_default_params(spk_params* p);

/* Lifecycle ------------------------------------------------------------------- */

/* Create an engine reading bundled assets from a filesystem directory. `asset_dir`
 * is the on-device path where bundled assets (profiles/, luts/, filters/, icc/)
 * were extracted; must be non-NULL. To read assets straight from the APK without
 * extraction, use spk_engine_create_asset_manager instead. */
spk_status spk_engine_create(const char* asset_dir, spk_engine** out);

/* Create an engine that reads its bundled assets directly from the APK via
 * Android's AAssetManager (no on-device extraction needed). `aasset_manager` is a
 * `void*` that must be an `AAssetManager*` (obtained on the JNI side via
 * `AAssetManager_fromJava`). Asset paths are resolved relative to `assets/spektra/`
 * inside the APK. The AAssetManager (and the Java AssetManager backing it) MUST
 * outlive the engine — the caller is responsible for keeping it alive.
 *
 * Only available on Android; on a non-Android host this returns SPK_ERR_BAD_ARGS
 * (host parity tests use the filesystem path via spk_engine_create). */
spk_status spk_engine_create_asset_manager(void* aasset_manager, spk_engine** out);

void       spk_engine_destroy(spk_engine*);

/* Profile catalog: returns a newline-separated, NUL-terminated list of available
 * film/print profile ids into `buf` (caller-provided). Sets `*needed` to required size. */
spk_status spk_engine_list_profiles(spk_engine*, char* buf, size_t buf_len, size_t* needed);

/* Simulation ------------------------------------------------------------------ */

/* Full pipeline: RGB → negative → (print) → scan. `out` is allocated by the engine
 * (display-referred RGB in params.output_color_space); free with spk_image_free. */
spk_status spk_simulate(spk_engine*, const spk_image* in, const spk_params*, spk_image* out);

/* Downscaled fast path (to params.preview_max_size) for interactive tuning. */
spk_status spk_simulate_preview(spk_engine*, const spk_image* in, const spk_params*, spk_image* out);

void spk_image_free(spk_image*);

/* Debug taps (mirror DebugParams) for the golden-vector parity harness: dump an
 * intermediate buffer by name ("film_log_raw" | "film_density_cmy" | "print_density_cmy"). */
spk_status spk_simulate_tap(spk_engine*, const spk_image* in, const spk_params*,
                            const char* tap_name, spk_image* out);

const char* spk_status_str(spk_status);

/* LUT baking ------------------------------------------------------------------ */

/*
 * Bake the current film look into a 3D `.cube` LUT (Adobe/Resolve format).
 *
 * Builds an identity RGB lattice of size `lut_size` (default 33; clamped to
 * [2,256]) spanning the unit cube [0,1]^3, runs each lattice point through the
 * exact same per-pixel pipeline that spk_simulate uses (scan_film or print route,
 * per params->scan_film), and writes the results as a text `.cube`.
 *
 * INPUT DOMAIN of the LUT: linear ProPhoto RGB in [0,1] (the engine's working
 * space; the lattice axes are the linear input fed to the filming expose). This
 * is documented in the emitted `.cube` header comments.
 * OUTPUT DOMAIN: display-referred RGB in params->output_color_space, with CCTF per
 * params->output_cctf_encoding — identical to spk_simulate's output.
 *
 * EXCLUDED EFFECTS: spatial/stochastic effects cannot be captured by a 3D LUT and
 * are FORCED OFF for baking regardless of the params: grain, halation (with its
 * in-emulsion scatter), camera/enlarger diffusion glare, DIR-coupler spatial
 * diffusion, and scanner unsharp. The pointwise color science is kept: spectral
 * upsampling, density curves, pointwise DIR couplers, printing, scanning, and the
 * output color-space transform. This is also noted in the `.cube` header.
 *
 * The `.cube` text (LUT_3D_SIZE N, TITLE, DOMAIN_MIN/MAX, N^3 RGB triples in
 * blue-fastest / red-slowest order) is written NUL-terminated into `out_text`.
 * `*needed` is always set to the required buffer size (including the NUL); if
 * `out_text` is null or `out_cap` is too small, returns SPK_ERR_BAD_ARGS so the
 * caller can resize and retry (the bake still runs to size it).
 */
spk_status spk_bake_cube_lut(spk_engine*, const spk_params*, int lut_size,
                             char* out_text, size_t out_cap, size_t* needed);

#ifdef __cplusplus
} /* extern "C" */
#endif
#endif /* SPEKTRA_H */
