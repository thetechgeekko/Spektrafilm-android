# Research — Lightroom stack vs ours, and what's worth copying

Static RE of Adobe Lightroom mobile (`com.adobe.lrmobile`, in-env APK) via the
android-reverse-engineering skill: `nm`/`strings` over its native libs (`libLrAndroid.so`
67 MB + siblings) plus the earlier UI/bridge RE in `docs/IMPROVEMENT_BACKLOG.md`.

## Language / tech stack

| Layer | Lightroom | Spektrafilm (ours) |
|-------|-----------|--------------------|
| Render engine | **C++** — Adobe Camera-Raw "CR" core (`cr_*` symbols, `Adobe::PM` namespaces), built **clang 19 + libc++** | **C++17** — `libspektra` (NDK), clang/libc++ |
| Parallelism | **Intel oneTBB** (`tbb10`/`tbb12`, `N3tbb...blocked_range`, `auto_partitioner`) | custom **deterministic fork-join** (`kernels/parallel`, thread-invariant) |
| GPU | **Vulkan + OpenCL + Metal** (Metal ⇒ shared iOS core); GPU ML accel `libLiteRtClGlAccelerator.so` | **CPU only** |
| SIMD / precision | NEON + **`__fp16` / Float16** half-precision | NEON (portable vector `exp10`), **float32** |
| ML | **LiteRT/TFLite** (`libLiteRt.so`, `libLrmModels.so`) — subject/sky/mask | none |
| Codecs | **JPEG-XL** (`libjxl`), webp/sharpyuv, brotli, **C2PA** (`libadobe_c2pa`) | LibRaw (decode), hand-rolled TIFF/PNG (encode), Ultra-HDR |
| Other | Abseil, OpenCV (`libopencv_java4`), Lua (scripting), SQLite catalog, Crashlytics | — |
| UI shell | **Java/Kotlin** | **Kotlin + Jetpack Compose** |

**Bottom line:** same fundamental split — **a C++ render engine under a Kotlin/Java UI**. We
match LR's architecture; the gaps are all *scale/perf/feature* infra: TBB, GPU, fp16,
pyramids/tiling, ML.

## Important things we can do similar (prioritized)

Ranked by value/effort. Evidence = the symbols/libs each is grounded in.

1. **fp16 / half-float intermediate buffers** — LR carries `__fp16`/Float16 through the
   pipeline. Our 81-band spectral buffers are float32; fp16 storage ~halves memory + bandwidth
   on the per-pixel loops (NEON `fmla` on fp16). *Med, CPU-only, no new deps.* High value for
   big-image headroom (compounds with the proxy decode).
2. **Image pyramid + render levels (coarse→fine progressive)** — `cr_base_pyramid`,
   `ICBSetRenderLevel`, `"Choosing RPTM Pyramid Level"`. Show a coarse render instantly, refine
   in place. Makes the spectral pipeline *feel* instant. *Large.* Backlog #H.
3. **Tiled full-res export** — `cr_cpu_const_tile_buffer` / `cr_cpu_dirty_tile_buffer`. Render
   the export in bounded-memory tiles so 50–200 MP files export without a full-frame transient.
   Closes the remaining half of issue #7 (the proxy decode already fixed *preview*). *Large, native.*
4. **Per-stage intermediate caches** — the huge `cr_*_cache` family (tone-map, mask, stats…).
   We recompute the whole pipeline on every param change; caching upstream stages so only the
   changed stage re-runs would cut preview latency a lot. *Med.*
5. **Pause/refresh render on gesture** — `ICBPauseRendering`/`ICBRefreshRendering`. Skip
   rendering mid-drag, render once on release. Cheap, immediate smoothness win. *Small.*
6. **Embedded-JPEG instant preview** — `ICBGetAndReleasePreviewJpegBytes` / LibRaw
   `unpack_thumb`: show the camera's embedded JPEG (<100 ms) while the real decode runs. *Small–Med.*
7. **GPU compute path (Vulkan)** for the per-pixel spectral integral — LR's Vulkan/CL backend.
   Biggest speedup, biggest effort; would need a parity-preserving GPU port. *XL.*
8. **Modern export formats** — JPEG-XL / AVIF / HEIC (`libjxl` present in LR). *Med.* Backlog #I.
9. **ML masking** (subject/sky) via LiteRT — `libLiteRt.so`. *Large.* Backlog #A.

**Recommended next:** #5 (tiny, instant smoothness) and #6 (instant first paint) are the
cheapest wins; #1 (fp16) is the best perf/memory-per-effort; #2/#3 (pyramid + tiling) are the
real "handles big files like Lightroom" endgame.

## Already matched
Proxy/Smart-Preview decode (bounded preview cap + half-size proxy, `NegativeCacheLargePreviewSize`
analog) — see `docs/RESEARCH_BIG_FILES.md`. Tone curve, copy/paste, preset amount, granular
resets — see `docs/IMPROVEMENT_BACKLOG.md`.

*Film modeling powered by spektrafilm (GPLv3).*
