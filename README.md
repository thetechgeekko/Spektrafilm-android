# SpectraFilm for Android

> Spectral film simulation of analog photography — on Android, with RAW/DNG editing.

This repository is the Android port of [**spektrafilm**](https://github.com/andreavolpato/spektrafilm),
a physically-based, spectral simulation engine that turns a camera RAW into a convincing
film **negative → print → scan** rendering using data extracted from real film-stock datasheets.

The goal: bring the full spektrafilm pipeline (spectral upsampling, characteristic density
curves, DIR couplers, stochastic grain, halation, enlarger filtration, virtual scanning)
to a native Android app that can **open and edit RAW DNG files** directly on device.

> [!IMPORTANT]
> This is **milestone 0 — project foundation**. This commit contains the architecture,
> the porting plan, faithful maps of both source repositories, the RAW/DNG decoding
> strategy, the licensing analysis, and the engine API contract (C++/JNI/Kotlin).
> The host app and engine implementation are delivered in subsequent milestones — see
> [`docs/ROADMAP.md`](docs/ROADMAP.md).

## The three source repositories

| Repo | What it is | Role in this port |
|------|------------|-------------------|
| [`spektrafilm`](https://github.com/andreavolpato/spektrafilm) | Python spectral film simulator (NumPy/SciPy/Numba, GPLv3) | **The engine we port.** Source of all the photographic physics + 28 film/paper profiles. |
| [`ImageToolbox`](https://github.com/T8RIN/ImageToolbox) | Mature Kotlin/Compose Android image editor (Apache-2.0) | **The host app.** Solves image I/O, gallery, export, Compose UI, gestures, RAW *preview* decoding, and a 295-filter framework. |
| **Spectrafilmandroid** (this repo) | The Android product | ImageToolbox host **+** ported spektrafilm engine **+** real RAW/DNG decode. |

## How we do it (one paragraph)

We fork **ImageToolbox** as the host application (it already solves the hard Android
problems), add a native **C++ NDK engine module** (`engine/spektra-core`) that is a direct
port of spektrafilm's `runtime` + `model` packages, add a **LibRaw NDK module** for
full-resolution RAW/DNG decode (spektrafilm uses `rawpy`, which *is* LibRaw — so we get
bit-for-bit parity), and add a **`feature:film-emulation`** Compose screen that drives the
engine. The 28 film/paper JSON profiles and the spectral-upsampling LUT binaries (~17 MB)
ship as app assets. Because spektrafilm is GPLv3, the combined app is **GPLv3**
(Apache-2.0 → GPLv3 is one-way compatible). Full reasoning in [`docs/DECISION.md`](docs/DECISION.md).

## Documentation

- [`docs/DECISION.md`](docs/DECISION.md) — the chosen approach and the alternatives we rejected
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — target module architecture of the Android app
- [`docs/PORTING_PLAN.md`](docs/PORTING_PLAN.md) — spektrafilm → Kotlin/C++ module-by-module map + effort table
- [`docs/RAW_DNG.md`](docs/RAW_DNG.md) — how RAW/DNG decode works on Android (LibRaw/NDK, rawpy parity)
- [`docs/LICENSING.md`](docs/LICENSING.md) — GPLv3 obligations and compatibility
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — phased milestones and acceptance criteria
- [`docs/ASSETS.md`](docs/ASSETS.md) — film/paper profiles + LUT binaries inventory
- [`docs/maps/SPEKTRAFILM_MAP.md`](docs/maps/SPEKTRAFILM_MAP.md) — full technical map of the engine
- [`docs/maps/IMAGETOOLBOX_MAP.md`](docs/maps/IMAGETOOLBOX_MAP.md) — full technical map of the host

## Engine API contract (preview)

The native engine exposes a small surface mirroring spektrafilm's `create_params` / `simulate`:

```kotlin
val engine = SpektraEngine()                       // loads native lib + bundled assets
val params = SpektraParams(
    filmProfile  = "kodak_portra_400",
    printProfile = "kodak_portra_endura",
)
val result: Bitmap = engine.simulate(linearRgb, params)   // RAW → negative → print → scan
```

See [`engine/spektra-core`](engine/spektra-core) for the C++ header, JNI bridge, and Kotlin facade.

## License

GPLv3 — see [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md). Derivative of `spektrafilm`
(GPLv3) and `ImageToolbox` (Apache-2.0). Film modeling powered by **spektrafilm**.
