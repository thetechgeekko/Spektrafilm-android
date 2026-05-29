# RAW / DNG editing on Android

Goal: open camera RAW (incl. **DNG**) on device and feed the engine a **linear, scene-referred
RGB** buffer that matches what spektrafilm gets from `rawpy` on desktop.

## Key insight: rawpy == LibRaw

spektrafilm reads RAW with `rawpy`, and **`rawpy` is a thin Python binding over LibRaw**.
So "RAW like spektrafilm" = "LibRaw with the same `postprocess` options". We compile LibRaw
with the NDK and call it from a JNI wrapper using the identical settings:

```python
# spektrafilm/utils/raw_file_processor.py (effective settings)
raw.postprocess(
    output_color = rawpy.ColorSpace.ACES,   # linear ACES2065-1 primaries
    output_bps   = 16,                       # 16-bit
    no_auto_bright = True,
    gamma        = (1, 1),                   # LINEAR (gamma 1.0)
    use_camera_wb = True,                    # or daylight / tungsten / custom
)
```

LibRaw equivalent (C++):

```cpp
LibRaw raw;
raw.open_buffer(bytes, len);            // from a SAF InputStream / fd
raw.imgdata.params.output_color   = 6;  // 6 = ACES (matches rawpy.ColorSpace.ACES)
raw.imgdata.params.output_bps     = 16;
raw.imgdata.params.no_auto_bright = 1;
raw.imgdata.params.gamm[0] = 1.0;       // gamma 1/1 → linear
raw.imgdata.params.gamm[1] = 1.0;
raw.imgdata.params.use_camera_wb  = 1;  // or set user_mul[4] for custom WB
raw.unpack();
raw.dcraw_process();
libraw_processed_image_t* img = raw.dcraw_make_mem_image();  // 16-bit linear RGB
```

White-balance modes mirror upstream: **as-shot** (`use_camera_wb`), **daylight**/**tungsten**
(fixed multipliers / D-illuminant), **custom** (temperature + tint → `user_mul`), then the same
Von-Kries chromatic adaptation + green/magenta tint multiplier spektrafilm applies.

## Why not Android's built-in DNG API?

`android.hardware.camera2.DngCreator` **writes** DNG from `RAW_SENSOR` buffers; it does not
decode arbitrary RAW into RGB. Android's NDK `ImageDecoder` can decode some DNGs but only via
embedded preview / limited paths and gives no control over demosaic, gamma, or output
primaries. Neither reproduces spektrafilm's scene-referred linear output. LibRaw does, exactly.

(ImageToolbox already ships a `NefDecoder` that extracts the embedded **JPEG preview** from
Nikon NEF — useful for fast thumbnails, but it is *not* sensor data. We keep it for previews
and add LibRaw for the real decode.)

## Building LibRaw for Android

- Add LibRaw as a native source set under `lib:libraw/src/main/cpp` with a `CMakeLists.txt`.
- ABIs: reuse ImageToolbox's `armeabi-v7a, arm64-v8a, x86_64`.
- Build the **DNG SDK** add-on only if we must decode "non-standard"/lossy DNGs; baseline DNG +
  common RAW (CR2/CR3/NEF/ARW/RAF/ORF/RW2) work without it. Decision tracked in M2.
- License: LibRaw is LGPL-2.1 / CDDL-1.0 dual-licensed. We link it into a GPLv3 app under
  LGPL-2.1 (GPLv3-compatible). See `LICENSING.md`.

## Two integration points

1. **Engine input (primary).** `feature:film-emulation` asks `lib:libraw` to decode the picked
   RAW `Uri` → `float[]`/`ByteBuffer` linear RGB (+ width/height/primaries) → hand straight to
   `SpektraEngine.simulate`. No intermediate 8-bit bitmap; full 16-bit precision preserved.
2. **Full-res RAW in the gallery (secondary).** Register a `RawDecoder : Decoder.Factory` in
   `core/data/.../coil/` so RAW files open full-resolution throughout the host app (extending
   the existing decoder registry in `ImageLoaderModule`), not just as previews.

## Output

Editing results are exported through ImageToolbox's existing save pipeline: 8/16-bit
PNG/TIFF/JPEG with EXIF (`ExifInterface`) and embedded ICC matching the chosen output color
space (sRGB / Adobe RGB / ProPhoto / Rec.2020 / ACES), reproducing spektrafilm's `io.py`
behavior on the formats Android supports natively.
