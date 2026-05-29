# Mobile editing strategy — what we learned from Lightroom mobile

Decision input for the question *"are we porting the true system, and must the app do everything
the spektrafilm GUI does?"* — answer: **yes**, and here is how we make that smart on a phone,
informed by how Adobe Lightroom mobile (and other modern mobile RAW editors) actually work.

## The two anchoring facts

1. **The spektrafilm GUI is a thin shell over the engine.** Verified in source: `spektrafilm_gui`
   imports `init_params` / `digest_params` / `RuntimePhotoParams` / `load_profile` and only
   contains widgets + a `params_mapper` that turns slider state into a `RuntimePhotoParams` and
   calls `simulate`. **No processing math lives in the GUI.** Therefore "do everything the GUI
   does" == "port the engine faithfully + expose the full `RuntimePhotoParams` surface." Our
   `SpektraParams.kt` already mirrors that tree 1:1, so the control surface is covered by design.

2. **The parity bar is bit-exact.** We gate the C++ port against the Python engine stage-by-stage
   (`tools/parity`) to a tight tolerance. This is not an imitation of the "film look"; it is a
   reproduction of the *true system*.

## What Lightroom mobile does, and what we copy

| Lightroom behavior | Source | Our decision |
|--------------------|--------|--------------|
| **Smart Previews**: edits run on a lossy DNG proxy (long edge 2560 px, ~2% size); **export re-renders from the full-res original**. | [Adobe: Smart Previews](https://helpx.adobe.com/lightroom-classic/help/lightroom-smart-previews.html) | Adopt the proxy/preview model — which is *exactly* spektrafilm's `preview` vs `scan` split (`settings.preview_max_size`). Interactive sliders run on a downscaled **linear** proxy; the full pipeline runs at full resolution only on export. Raise the default proxy size toward a "smart-preview"-like long edge (≈1280–2560) for quality, configurable. |
| **Non-destructive**: every edit is a recipe/sidecar (XMP); the original file is never modified; edits re-applied on view/export. | [Adobe non-destructive editing](https://lifeafterphotoshop.com/non-destructive-editing-and-how-it-works/) | Add a **non-destructive recipe layer**: the edit is a serialized `SpektraParams` stored as a sidecar keyed to the source RAW; the original is untouched; re-render on demand. Enables presets (the 28 stocks + saved params), history, and "extract preset" like Lightroom. |
| **GPU-accelerated editing** for slider responsiveness; same pipeline, faster. | [ACR GPU FAQ](https://helpx.adobe.com/camera-raw/kb/acr-gpu-faq.html) | GPU is a **later, optional accelerator for the preview path only** — see the precision note below. It is never the parity-bearing implementation. |
| Imports RAW/DNG/JPEG/TIFF; exports JPEG/DNG/TIFF — **no EXR**. | Lightroom format support | Be photo-app pragmatic: ingest RAW/DNG (LibRaw) + JPEG/PNG/16-bit TIFF; export 16-bit TIFF + high-quality JPEG (+ optional baked DNG). **Defer EXR / 32-bit-float-TIFF I/O** (spektrafilm's OpenImageIO niche) behind the same writer interface for a later milestone. Internal pipeline stays 32-bit float. |

Reference open-source corroboration: [RapidRAW](https://github.com/CyberTimon/RapidRAW) — a modern
non-destructive, GPU-accelerated RAW editor (WGPU/WGSL) — confirms the "offload pipeline to GPU
for a fluid UI, keep edits non-destructive" pattern.

## The decisive architectural conclusion: CPU is the parity engine, GPU is a future accelerator

Bit-exact parity is required, and **GPU floating-point results are not bit-reproducible across
vendors** (per-architecture precision differs; see the GPU precision discussion in the research).
Therefore:

- **The parity-bearing engine is CPU C++/NDK** (deterministic IEEE float, with NEON/SIMD where it
  helps). This is what `tools/parity` gates to bit-exact tolerance against Python. (Confirms the
  M0 decision — now with a hard justification.)
- **A GPU path (Vulkan / OpenGL ES compute) is an optional M6 accelerator for the interactive
  preview only**, validated against the CPU golden vectors within a *visual* tolerance — it never
  becomes the parity gate, and export always uses the CPU engine.

## Scope verdict for "everything"

**In scope (the whole true system, bit-exact):** spectral upsampling (Hanatos2025 + LUT binary),
all three stages (filming / printing-with-enlarger-dichroics / scanning), DIR couplers, grain
(Poisson-binomial), halation + in-emulsion scatter + diffusion filters, **FFT-based diffusion**
(needed for exact large-radius parity — *no longer trimmed*), spectral-LUT acceleration
(`use_enlarger_lut` / `use_scanner_lut`, with the exact non-LUT path as the parity gate), all
`RuntimePhotoParams` controls, debug taps, and `scan_film` (view-negative). Output color spaces
sRGB/AdobeRGB/ProPhoto/Rec2020/ACES with ICC on export.

**Smart additions (Lightroom-informed):** non-destructive recipe/sidecar + presets/history;
proxy-preview vs full-res-export; device color management for on-screen.

**Deferred (not capability we lose, just niche file formats / later optimization):** EXR &
32-bit-float TIFF I/O; the GPU preview accelerator; `lensfunpy` lens correction (unused upstream).

**Dropped (not part of the engine):** the napari/Qt GUI (reimplemented in Compose),
`plotting.py`, and unused upstream modules (`parametric`, `stocks`, `calibration_targets`).
