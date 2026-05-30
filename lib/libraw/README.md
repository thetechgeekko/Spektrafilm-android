# lib:libraw

On-device camera **RAW / DNG** decoding for Spektrafilm for Android, producing a
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

## Mobile / Google Pixel DNG decode (native vs fallback)

Most mobile DNGs — including **Google Pixel computational-RAW DNGs** — use one
of three raw-plane compressions, **all decoded natively** by this module with
the current build flags:

| Compression tag        | Decodes here? | How                                                   |
|------------------------|---------------|-------------------------------------------------------|
| 1 — uncompressed       | ✅ native     | plain unpack                                          |
| 7 — lossless JPEG/LJ92 | ✅ native     | LibRaw **internal** lossless-JPEG (`lossless_jpeg_load_raw` / `ljpeg_start` / `ljpeg_row`, `src/decoders/dcraw_common.cpp`) — **no libjpeg required** |
| 8 — DEFLATE/ZIP        | ✅ native     | zlib `inflate()` (`USE_ZLIB`; NDK `libz` linked)      |
| 6 — old-style JPEG     | ❌ fallback   | needs libjpeg → `LOSSY_JPEG_DNG`                      |
| 0x884C — lossy JPEG    | ❌ fallback   | needs libjpeg → `LOSSY_JPEG_DNG`                      |
| 0xCD42 — JPEG-XL       | ❌ fallback   | needs libjxl/dngsdk → `JPEGXL_DNG`                    |

**Key point:** `USE_JPEG` is intentionally OFF and is **not** needed for Pixel
DNGs. `USE_JPEG` only adds *lossy* baseline-JPEG (0x884C) decode and embedded
JPEG thumbnails; LibRaw's lossless-JPEG (LJ92) raw decoder is compiled
unconditionally. (Evidence: libraw.org node/2639 lists `lossless_jpeg_load_raw`
among the native unpack functions for "Lossless JPEG (Canon, some DNG)".) Pixel
DNGs therefore decode with the prior wave's flags — **no build change was
needed**; this wave fixes the *classification/diagnostics*.

> Mobile DNGs commonly embed a large JPEG **preview** in IFD0 with the real
> Bayer/linear raw in a **SubIFD**. The `dngsniff` sniffer walks IFD0 + SubIFDs
> + the next-IFD chain and picks the largest **non-reduced** (`NewSubFileType`
> bit 0 clear) image, so a JPEG preview is never mistaken for the raw
> compression. The unpack-failure classifier only flags genuinely-unsupported
> codecs (6 / 0x884C → `LOSSY_JPEG_DNG`, 0xCD42 → `JPEGXL_DNG`); uncompressed /
> LJ92 / deflate that reach it are treated as real data errors (generic
> `UNPACK`), never given a false fallback hint.

A host unit test (`src/test/cpp/test_dng_sniffer.cpp`) compiles `raw_decoder.cpp`
with `-include` (no LibRaw needed — the decoder guards its LibRaw include) and
exercises the sniffer + classifier against synthesized uncompressed / LJ92 /
deflate / lossy / old-JPEG / JPEG-XL and Pixel-style preview+SubIFD headers.
22/22 cases pass:

```
g++ -std=c++17 -I../../main/cpp -include ../../main/cpp/raw_decoder.cpp \
    test_dng_sniffer.cpp -o /tmp/test_dng_sniffer && /tmp/test_dng_sniffer
```

## Half-size (proxy) decode — memory and performance option

For large RAW/DNG files (50–200 MP Expert RAW, high-resolution MFT/medium-format
bodies) the full-resolution path in `dcraw_process()` builds a full 16-bit image
in RAM before it is normalised to float32 and handed to the engine — a transient
several hundred MB allocation that is the primary OOM surface on low-RAM devices.

`RawDecoder.Settings` exposes a `halfSize: Boolean` flag (default `false`):

```kotlin
// Proxy decode — ~¼ the pixel count, ~¼ the peak memory, much faster.
val proxy: LinearResult = RawDecoder.decodeToLinear(
    fd,
    RawDecoder.Settings(halfSize = true),
)
// proxy.width  ≈ fullWidth  / 2
// proxy.height ≈ fullHeight / 2
```

| Option        | Value  | Effect                                                      |
|---------------|--------|-------------------------------------------------------------|
| `halfSize`    | false  | Full-resolution decode (existing behaviour, unchanged).     |
| `halfSize`    | true   | LibRaw `imgdata.params.half_size = 1`; each 2×2 Bayer cell  |
|               |        | is averaged into one output pixel (no demosaic).            |

**How it works (LibRaw `half_size`):**
LibRaw's `half_size` parameter skips the full Bayer demosaic interpolation and
instead merges each **2×2 Bayer cell** (one R + two G + one B sample) into a
**single RGB output pixel** using a simple average.  Because the output is
half the linear dimensions in each axis, the total pixel count is **¼ of the
full-res decode** — and so is the peak allocation.  LibRaw updates
`imgdata.sizes` (specifically `S.width` / `S.height`) after `dcraw_process()`,
and `dcraw_make_mem_image` reports the post-process dimensions, so
`LinearResult.width * LinearResult.height * 3 == rgb.size()` is always satisfied.

**When to use:**
- Fast proxy preview of a large Expert RAW on a low-RAM device.
- "Does this file decode?" health checks where full quality is not needed.
- App-side thumbnail generation before handing the full-res job to a background
  worker.

**When NOT to use:**
- Export (TIFF / PNG / Ultra HDR) — lower quality, wrong dimensions.
- Engine spectral film simulation — all processing must be at full resolution.

The app module decides when to request `halfSize = true`; the lib only exposes
the capability.  Existing call sites (`Settings()` / `Settings(whiteBalance = …)`)
default to `halfSize = false` and are byte-for-byte unchanged.

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

Spektrafilm for Android is **GPLv3**. This module **uses LibRaw**, which is
**LGPL-2.1 / CDDL-1.0** dual-licensed; we link it under **LGPL-2.1**
(GPLv3-compatible). See `../../docs/RAW_DNG.md` and `LICENSING.md`.
