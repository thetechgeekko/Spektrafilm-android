# Research — how Lightroom handles big files, and our equivalent

Goal: large RAWs (50–200 MP Samsung Expert-RAW / medium-format DNGs) must load and edit
without OOMing or stalling. This documents Lightroom's approach and the matching design now
in Spektrafilm.

## What Lightroom does (reverse-engineered from behaviour + docs)

- **Edit a downsized proxy, not the original.** Lightroom's *Smart Previews* are lossy-DNG
  proxies with the **longest edge ≈ 2560 px**. The Develop module edits the proxy; the
  full-resolution original is only touched on **export** or when **zooming past the proxy
  resolution**. A Smart Preview is ~2% of the original's size, so a 14 GB shoot → ~400 MB of
  proxies. ([Adobe: Smart Previews](https://helpx.adobe.com/lightroom-classic/help/lightroom-smart-previews.html),
  [Optimize performance](https://helpx.adobe.com/lightroom-classic/kb/optimize-performance-lightroom.html),
  [Shotkit](https://shotkit.com/lightroom-smart-previews/))
- **Embedded JPEG preview for instant display**, then a background high-quality render from
  the original data.
- **Multi-resolution / progressive render** (coarse → fine) and **tiled** processing so peak
  memory is bounded by the working resolution, not the file size.

The single load-bearing idea: **decode/work at a bounded resolution; reserve full-res for
export.** Memory and latency track the *proxy* size, not megapixels.

## What we do (the equivalent)

Spektrafilm already separates an interactive cap (`MAX_EDGE_PX = 2048`, our proxy ≈ Lightroom's
2560) from an export cap (`EXPORT_MAX_EDGE_PX = 16384`). Two fixes make big files actually load:

1. **fd decode, no full-file `byte[]`** (PR #43): the RAW is decoded straight from the file
   descriptor, so a 100–200 MB DNG is never copied into one contiguous Java array (that array
   was OOMing on the ~256 MB Java heap before LibRaw even ran).
2. **Proxy (half-size) decode for interactive targets** (this change): for any target at or
   below `HALF_DECODE_EDGE_THRESHOLD` (4096) — i.e. the preview and the magnifier — we ask
   LibRaw for a **half-size decode** (`Settings.halfSize`), which averages each Bayer 2×2 into
   one pixel → **¼ the pixels and ¼ the native float buffer**. We then box-downsample the rest
   of the way to `maxEdge`. Export-scale targets (16384) still decode full-resolution, exactly
   like Lightroom reaching for the original on export.
3. **Graceful ladder.** If a full-res export decode OOMs, the retry first flips to a half-size
   decode (¼ memory, keeps more resolution than dropping the cap), then shrinks the output cap,
   then surfaces a catchable error instead of crashing.
4. **Full-res pixels live OFF the managed heap** (the export OOM fix). On Android,
   `ByteBuffer.allocateDirect` is backed by a **non-movable `byte[]` on the ART managed heap**
   (~256 MB growth limit), so a full-res RAW input (~140 MB for a 12 MP DNG) *plus* the engine's
   equally large output buffer cannot coexist — export threw
   `OutOfMemoryError "Failed to allocate a 149817619 byte allocation … growth limit 268435456"`
   even after the proxy/cap work above. Lightroom never crosses full-res pixels to the Java heap
   at all (see Evidence). We mirror that: the RAW decode result (`raw_decoder_jni.cpp`) and the
   engine output (`spektra_jni.cpp`) are now allocated with **`malloc` + `NewDirectByteBuffer`**
   — true native memory, off the ART heap, multi-GB headroom — and freed explicitly
   (`RawDecoder.freeOffHeap` / `SimResult.freeDirectBuffer`) via `LinearImage`/`SimResult`
   becoming `AutoCloseable`. Proxy/preview-scale buffers (≤ `HALF_DECODE_EDGE_THRESHOLD`) stay
   managed/GC'd, so the preview cache lifecycle is unchanged; only export-scale buffers are
   off-heap and closed by the caller.

Net: interactive editing of a 50 MP DNG now peaks at the proxy's memory (~¼ of before), the
preview path no longer allocates a multi-hundred-MB transient, and **full-res export of a 12 MP
DNG no longer OOMs** — the two ~140 MB buffers now sit in native memory instead of fighting over
the 256 MB Java heap.

## Evidence — static RE of `libLrAndroid.so` (Lightroom `com.adobe.lrmobile`)

Decompiled the Lightroom APK in-env (android-reverse-engineering skill); extracted these
concrete symbols from the native engine `libLrAndroid.so` (`strings`/`nm`), confirming the
proxy + bounded-cap + pyramid + tile model above:

- **Proxy negative + bounded preview cap** (the Smart-Preview store):
  `NegativeCacheLargePreviewSize`, `NegativeCacheMaximumSize`, `NegativeCachePath`,
  and bridge methods `ICBIsProxyNegative`, `ICBGeneratePreview`,
  `ICBGeneratePreviewAndKeepIt`, `ICBGetAndReleasePreviewJpegBytes`. The decoded "negative"
  is cached with a *maximum size* and a *large-preview size* — i.e. a capped proxy, not the
  full original.
- **Multi-resolution pyramid / mipmap**: `cr_base_pyramid`, log strings
  `"Choosing RPTM Pyramid Level = %u"`, `"Computed pyramid level %d"`, `"MipMap: level %d"`,
  `GuideMipMapMethod`. Render runs off a precomputed image pyramid.
- **Render levels (coarse→fine) + pause/refresh**: `ICBSetRenderLevel`, `ICBRenderAsync`,
  `ICBRenderLayerAsync`, `ICBPauseRendering`, `ICBRefreshRendering`, `ICBCheckNeedsRefresh`,
  `ICBSetRenderCallback`.
- **Tiled processing**: `cr_cpu_const_tile_buffer`, `cr_cpu_dirty_tile_buffer` (+ the
  `cr_*_cache` family for per-stage intermediate caching).
- **Pixels never touch the Java heap — native off-heap buffers.** Across *all* of LR's native
  libs (`libLrAndroid.so`, `libimaging.so`, `libcapture.so`, `libvfexporterlib-native-lib.so`,
  `libkernel.so`) there is **zero** `NewDirectByteBuffer` / `GetDirectBufferAddress` /
  `allocateDirect` — i.e. no image pixel buffer is ever surfaced to Java. Large buffers use the
  **Intel oneTBB scalable allocator** (`scalable_malloc`, `scalable_posix_memalign`, `mmap` via
  `rml::internal::mmapTHP`) — native, `mmap`-backed, off the ART heap. The engine holds the
  image for its whole life behind a native handle (`ICBConstructor`/`ICBDestructor`) and only
  ever hands Java a small **compressed JPEG** (`ICBGetAndReleasePreviewJpegBytes`) or the final
  export file — never raw pixels. This is the direct evidence behind "What we do" item 4: keep
  full-res pixels in native memory, not on the managed heap.

Mapping to Spektrafilm: our `MAX_EDGE_PX` proxy cap == `NegativeCacheLargePreviewSize`; our
half-size proxy decode == decoding the negative at the proxy resolution rather than full;
our export-only full-res decode == LR using the original only on export. The pyramid /
render-level / tiling symbols are the deeper native items we have **not** matched yet (below).

## Not yet (true parity, larger native work)

- **Native tiling / pyramid decode** for full-res export of very large files (LibRaw still
  expands the whole frame for the export path). Tracked as issue #7.
- **Embedded-JPEG instant preview** (LibRaw `unpack_thumb`) for sub-100 ms first paint.
- **GPU progressive render** (`cr_gpu_pyramid` analog — see IMPROVEMENT_BACKLOG #H).

*Film modeling powered by spektrafilm (GPLv3).*
