/*
 * SpectraFilm for Android — native engine C API.
 * Copyright (C) 2026 SpectraFilm Android contributors.
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

    /* Reserved for the remaining RuntimePhotoParams fields (grain/halation/coupler
     * coefficients, scanner unsharp, debug taps, ...). See PORTING_PLAN.md. */
} spk_params;

/* Lifecycle ------------------------------------------------------------------- */

/* Create an engine. `asset_dir` is the on-device path where bundled assets
 * (profiles/, luts/, filters/, icc/) were extracted, or NULL to use the AAssetManager
 * wired via JNI. */
spk_status spk_engine_create(const char* asset_dir, spk_engine** out);
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

#ifdef __cplusplus
} /* extern "C" */
#endif
#endif /* SPEKTRA_H */
