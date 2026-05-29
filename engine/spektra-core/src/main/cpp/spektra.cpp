/*
 * SpectraFilm for Android — native engine entry (M0 stub).
 * GPLv3. Port of spektrafilm (GPLv3) — film modeling powered by spektrafilm.
 *
 * M0: declarations only. Real implementation is ported stage-by-stage in M3–M4
 * (see docs/PORTING_PLAN.md), each gated by the golden-vector parity harness.
 */
#include "spektra.h"

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

/* The functions below are intentionally unimplemented in M0. They compile so the
 * module and JNI boundary build; each returns SPK_ERR_INTERNAL until ported. */

spk_status spk_engine_create(const char*, spk_engine**)                     { return SPK_ERR_INTERNAL; }
void       spk_engine_destroy(spk_engine*)                                  {}
spk_status spk_engine_list_profiles(spk_engine*, char*, size_t, size_t*)    { return SPK_ERR_INTERNAL; }
spk_status spk_simulate(spk_engine*, const spk_image*, const spk_params*, spk_image*)         { return SPK_ERR_INTERNAL; }
spk_status spk_simulate_preview(spk_engine*, const spk_image*, const spk_params*, spk_image*) { return SPK_ERR_INTERNAL; }
spk_status spk_simulate_tap(spk_engine*, const spk_image*, const spk_params*, const char*, spk_image*) { return SPK_ERR_INTERNAL; }
void       spk_image_free(spk_image*)                                       {}

} /* extern "C" */
