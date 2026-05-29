<div align="center">

# 🎞️ SpectraFilm for Android

### Spectral film simulation of analog photography — running natively on your phone.

*Film modeling powered by [**spektrafilm**](https://github.com/andreavolpato/spektrafilm).*
*Dedicated to the [**pixls.us**](https://pixls.us) community.*

</div>

---

SpectraFilm takes a scene-linear image and runs it through a **physically-based, spectral**
simulation of the full analog pipeline — a virtual **negative → enlarger → print → scan** —
grounded in real film-stock datasheet measurements. It is a faithful native-C++ port of the
[spektrafilm](https://github.com/andreavolpato/spektrafilm) research engine, brought to Android.

This is not a "film-look LUT." It reconstructs spectra from RGB, exposes a virtual emulsion with
real spectral sensitivities, develops the dyes through characteristic density curves, simulates
**DIR couplers**, **halation**, **in-emulsion scatter**, **stochastic grain**, an **enlarger**
with dichroic Y/M/C filters, print paper, and a virtual scanner — then renders to your chosen
color space.

## What it does

- ✅ **Full pipeline, both routes:** scan-the-negative *and* negative → print → scan.
- ✅ **28 film & paper profiles**, browsable by **friendly names grouped by category** (color
  negative / slide / motion-picture / print film / RGB paper) with ISO · balance · era.
- ✅ **The whole look:** DIR couplers, halation, in-emulsion scatter, scanner unsharp, and
  **film grain** (Poisson-binomial particle model with sublayers + micro-structure).
- ✅ **Every parameter exposed**, organized **exactly like the spektrafilm desktop GUI** (Input ·
  Import Raw · Simulation · Grain · Preflash · Halation · Couplers · Glare · Experimental ·
  Display) with live preview.
- ✅ **RAW/DNG import** (LibRaw → linear ACES) + photo picker, and a **synthetic demo image**.
- ✅ **RAW white-balance UI** — Temperature/Tint sliders + mode dropdown (as-shot / daylight /
  tungsten / custom) + reset, shown only for RAW/DNG sources; changing WB re-decodes the
  preview automatically.
- ✅ **Crop/resize geometry stage** — the `IOParams` crop fields and cubic `upscale_factor` are
  live (bit-exact vs the spektrafilm `_preprocess` step); defaults are a strict no-op.
- ✅ **Presets:** 20 built-in researched film→print looks + **save / import / export** your own.
- ✅ **6 output color spaces** — sRGB, Adobe RGB, ProPhoto, Rec.2020, ACES2065-1, linear.
- ✅ **Native engine** (`libspektra.so`) + **`libsfraw.so`** (LibRaw) for arm64-v8a /
  armeabi-v7a / x86_64, driven from a Jetpack Compose UI with a full-res **export to gallery**.

**Next (M2+):** global Coil RAW decoder, 16-bit PNG/TIFF export, profile-curve browser, batch
processing, porting the remaining gated stages (diffusion filters, lens blur, auto-exposure) —
see `docs/ROADMAP.md`.

## Install

Grab the APK from [**`dist/`**](dist/) (or the **CI build artifact** on the latest green run),
enable "install from unknown sources," and open it. Min Android 7.0 (API 24). Pick a photo or a
RAW/DNG, choose a preset or tune the parameters, and export to your gallery.

---

## 🙏 How this app was made — and who to thank

SpectraFilm stands entirely on the shoulders of open color science and open source. **Every
stage of the engine was ported and then checked, bit-for-bit, against the original.** Huge
thanks to:

- **[spektrafilm](https://github.com/andreavolpato/spektrafilm)** by **Andrea Volpato** — the
  spectral film-simulation engine this project ports. The science, the film-stock profiles, and
  the spectral LUTs are all his work. *Film modeling powered by spektrafilm.* If you find this
  useful, please go star spektrafilm and read the brilliant write-up on
  [discuss.pixls.us](https://discuss.pixls.us/t/spectral-film-simulations-from-scratch/48209).
- **[Image Toolbox](https://github.com/T8RIN/ImageToolbox)** by **T8RIN (Malik Mukhametzyanov)**
  — the reference Android image-editor architecture that guided this app's design (and the
  intended richer host for future versions).
- **[colour-science](https://www.colour-science.org/)** — the color-science backbone whose CMFs,
  illuminants, and color-space transforms define "correct" here.
- **[LibRaw](https://www.libraw.org/)** — for the coming on-device RAW/DNG decode.
- **The [pixls.us](https://pixls.us) community** — for keeping open photography and open color
  science alive and welcoming. **This app is dedicated to you.** 💛

### The method (for the pixls.us folks who'll appreciate it)

The port was done **parity-first**. We ran the real Python `spektrafilm` engine headless as a
*live oracle*, captured golden vectors of every intermediate (`film_log_raw`,
`film_density_cmy`, `print_density_cmy`, `final_rgb`) via its `DebugParams` taps, then ported
each stage to C++ and gated it against those goldens:

| Stage | Parity vs the original engine |
|-------|-------------------------------|
| Hanatos2025 spectral upsampling | max_abs ≈ 1.1e-7 |
| Filming (expose → develop) + DIR couplers | ≈ 1.2e-7 / 2.4e-7 |
| Printing (enlarger + dichroic filters, all profiles) | ≈ 2.4e-7 / 5.6e-7 |
| Scanning (spectral → XYZ → RGB, 6 spaces) | ≈ 6e-8 |
| Halation + scatter + coupler diffusion | ≈ 1.5e-7 |
| Grain (stochastic) | mean-preserving; noise std matched ~0% |

Those are *float32 rounding* differences — the double-precision math reproduces the original
exactly. A `tools/parity` harness + CI guard this on every commit. The deeper story, the
architecture, and the full stage-by-stage map live in [`docs/`](docs/).

---

## 👋 About the author

Built and directed by **Akshay**.

- Instagram: **[@akshay.pool](https://www.instagram.com/akshay.pool/)**
- YouTube: **[@Akshayishere](https://www.youtube.com/@Akshayishere/videos)**

If SpectraFilm brings you joy, say hi, share your renders, and tag along. 🎬

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — engine + app architecture
- [`docs/PORTING_PLAN.md`](docs/PORTING_PLAN.md) — module-by-module port map
- [`docs/MOBILE_STRATEGY.md`](docs/MOBILE_STRATEGY.md) — bit-exact parity + mobile design
- [`docs/RAW_DNG.md`](docs/RAW_DNG.md) — RAW/DNG decode plan (LibRaw)
- [`docs/maps/`](docs/maps/) — technical maps of the source projects
- [`tools/parity/`](tools/parity/) — the golden-vector parity harness
- [`NOTICE.md`](NOTICE.md) — attributions

## License

**GPL-3.0** — see [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md). Because SpectraFilm is a
derivative of the GPLv3 spektrafilm engine, the whole app is GPLv3. Please keep it open. 💛
