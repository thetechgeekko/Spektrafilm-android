# lib:libraw

On-device camera **RAW / DNG** decoding for SpectraFilm for Android, producing a
**linear, scene-referred float32 RGB** buffer with **bit-parity to spektrafilm's
desktop `rawpy` settings**.

> **Status: scaffold (M0/M1).** The native decoder, JNI bridge, and Kotlin facade
> are in place. The module **activates at M1** (same as `engine:spektra-core`),
> once the ImageToolbox host — with its build-logic convention plugins + version
> catalog — is seeded. **LibRaw must be vendored** (see below) before the native
> build can actually decode; until then `libsfraw.so` links and `decode()` returns
> a clear "LibRaw not vendored" error.

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
    │   ├── raw_decoder_jni.cpp      # JNI bridge -> direct float ByteBuffer + w/h/cs
    │   └── libraw/upstream/         # ← VENDOR LIBRAW HERE (not committed)
    └── kotlin/com/spectrafilm/libraw/
        ├── RawDecoder.kt            # facade: decodeToLinear(bytes/buffer/fd/stream)
        └── RawCoilDecoder.kt        # Coil 3 Decoder.Factory (gallery full-res open)
```

## Vendoring LibRaw

LibRaw is **not** bundled. Add it as a git submodule (preferred) or vendor a
release under `src/main/cpp/libraw/upstream`:

```sh
git submodule add https://github.com/LibRaw/LibRaw \
    lib/libraw/src/main/cpp/libraw/upstream
```

Expected stock-checkout layout: `libraw/upstream/libraw/libraw.h` (public headers),
`libraw/upstream/src/**/*.cpp` and `libraw/upstream/internal/*.cpp` (implementation).
`CMakeLists.txt` globs those sources, builds a static `raw` lib (with
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
