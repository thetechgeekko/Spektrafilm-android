# lib:libraw

On-device camera **RAW / DNG** decoding for SpectraFilm for Android, producing a
**linear, scene-referred float32 RGB** buffer with **bit-parity to spektrafilm's
desktop `rawpy` settings**.

> **Status: building.** The native decoder, JNI bridge, and Kotlin facade are in
> place, and **LibRaw is obtained at configure time via CMake `FetchContent`**
> (pinned 0.21.4 release tarball + SHA256 — see `src/main/cpp/CMakeLists.txt`), so a
> clean checkout builds `libsfraw.so` with just a working network. RAW/DNG decode is
> live (verified end-to-end on a real DNG). `build.gradle.kts` mirrors
> `engine:spektra-core` (plain AGP `com.android.library` + `kotlin.android` +
> `externalNativeBuild` CMake), so the module configures and builds standalone the
> moment it is added to `settings.gradle.kts`.

## What it does

`rawpy` is a thin Python binding over **LibRaw**, so "RAW like spektrafilm" =
"LibRaw with the same `postprocess` options". This module compiles LibRaw with the
NDK and calls it from a JNI wrapper using the identical settings, then applies the
same white-balance colour science spektrafilm uses (see
`spektrafilm/utils/raw_file_processor.py`).

Output is interleaved RGB **float32**, row-major, normalized 16-bit → `[0,1]`
(value / 65535), in **ACES2065-1** primaries — delivered as a direct
`ByteBuffer` ready to become an engine `LinearImage` with no 8-bit round-trip.

## Layout

```
lib/libraw/
├── build.gradle.kts                 # convention plugins + externalNativeBuild(CMake); 3 ABIs
├── src/main/AndroidManifest.xml     # minimal (no components)
└── src/main/
    ├── cpp/
    │   ├── CMakeLists.txt           # builds libsfraw.so; expects libraw/upstream
    │   ├── raw_decoder.h            # decode API + WB math contract
    │   ├── raw_decoder.cpp          # LibRaw params + Von-Kries adaptation (guarded)
    │   └── raw_decoder_jni.cpp      # JNI bridge -> direct float ByteBuffer + w/h/cs
    │       # (LibRaw sources are fetched at configure time, not committed)
    └── kotlin/com/spectrafilm/libraw/
        ├── RawDecoder.kt            # facade: decodeToLinear(bytes/buffer/fd/stream)
        └── RawCoilDecoder.kt        # Coil 3 Decoder.Factory (gallery full-res open)
```

## Vendoring LibRaw

LibRaw is **not** committed. `src/main/cpp/CMakeLists.txt` fetches it at configure
time via CMake **`FetchContent`**, pinned to a specific release tarball + SHA256:

```cmake
SFRAW_LIBRAW_VERSION = "0.21.4"
SFRAW_LIBRAW_URL     = "https://www.libraw.org/data/LibRaw-0.21.4.tar.gz"
SFRAW_LIBRAW_SHA256  = "6be43f19397e43214ff56aab056bf3ff4925ca14012ce5a1538a172406a09e63"
```

A clean checkout therefore builds with just a working network — no git submodule,
no committed third-party blob. To pin a different release, bump the three
`SFRAW_LIBRAW_*` cache variables. For offline/CI builds, point
`-DSFRAW_LIBRAW_SOURCE_DIR=<dir>` at a stock LibRaw checkout (the dir containing
`libraw/libraw.h`) and `FetchContent` is skipped.

`CMakeLists.txt` then globs LibRaw's `src/**/*.cpp` (matching its own
`Makefile.am` source list — notably **excluding the `*_ph.cpp` placeholder TUs**,
which are postprocessing-free stubs that would otherwise shadow the real
`dcraw_process` / `dcraw_make_mem_image`), builds a static `raw` lib (with
`NO_JASPER/NO_JPEG/NO_LCMS` to keep the `.so` small — baseline DNG + common RAW
decode without them), and links it into `libsfraw.so`.

> **DNG SDK add-on** (lossy / non-standard DNGs) is a separate decision tracked in
> M2; baseline DNG + CR2/CR3/NEF/ARW/RAF/ORF/RW2 work without it.

## rawpy ↔ LibRaw parity

| `rawpy.postprocess`          | value                  | LibRaw (`imgdata.params`)        |
|------------------------------|------------------------|----------------------------------|
| `output_color`               | `ColorSpace.ACES`      | `output_color = 6` (ACES2065-1)  |
| `output_bps`                 | `16`                   | `output_bps = 16`                |
| `no_auto_bright`             | `True`                 | `no_auto_bright = 1`             |
| `gamma`                      | `(1, 1)` → linear      | `gamm[0] = gamm[1] = 1.0`        |
| `use_camera_wb` (as-shot)    | `True`                 | `use_camera_wb = 1`              |
| normalization                | `/ 65535.0` → float32  | done in `raw_decoder.cpp`        |

### White balance (mirrors `raw_file_processor.py`)

| Mode       | Behavior                                                                  |
|------------|---------------------------------------------------------------------------|
| `AS_SHOT`  | LibRaw camera WB (`use_camera_wb`) during demosaic.                        |
| `DAYLIGHT` | LibRaw daylight-balanced base output; no adaptation.                       |
| `TUNGSTEN` | Von-Kries adapt **2850 K → 6504 K** reference, tint = 1.0, in linear ACES. |
| `CUSTOM`   | Von-Kries adapt **`temperature` K → 6504 K**, green/magenta `tint`.        |

Whitepoints come from CCT: **CIE daylight locus** for ≥ 4000 K, **Kang 2002**
Planckian approximation below — matching `_whitepoint_xyz_from_temperature`. The
adaptation is `method='Von Kries'` in CIE XYZ, applied in ACES2065-1, then the
green-channel tint multiplier (`_apply_tint_adjustment`).

## Two integration points (docs/RAW_DNG.md)

1. **Engine input (primary).** `feature:film-emulation` asks `lib:libraw` to decode
   a picked RAW `Uri` → direct float32 linear RGB (+ width/height/primaries) via
   `RawDecoder.decodeToLinear(...)`, then hands the `LinearResult` straight to
   `SpektraEngine.simulate` as a `LinearImage` — full 16-bit precision, no
   intermediate 8-bit bitmap.
2. **Full-res RAW in the gallery (secondary).** `RawCoilDecoder.Factory` is a Coil 3
   `Decoder.Factory` registered in the host's
   `core/data/.../di/ImageLoaderModule.kt` (`provideComponentRegistry`), alongside
   the existing `NefDecoder.Factory()`, so RAW files open full-resolution
   throughout the host app.

## License

SpectraFilm for Android is **GPLv3**. This module **uses LibRaw**, which is
**LGPL-2.1 / CDDL-1.0** dual-licensed; we link it under **LGPL-2.1**
(GPLv3-compatible). See `../../docs/RAW_DNG.md` and `LICENSING.md`.
