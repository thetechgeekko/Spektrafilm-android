# Research — reverse-engineering the MotionCam `.mcraw` container (import plan)

MotionCam (and MotionCam Pro) record RAW **video/bursts** into a single `.mcraw` container
instead of a folder of DNGs (30× 4000×3000 frames ≈ 234 MB lossless-compressed vs ≈ 686 MB as
uncompressed DNGs). Spektrafilm today imports single stills only (DNG/CR2/CR3/NEF/ARW/RAF/ORF/RW2
via LibRaw — `lib/libraw/src/main/kotlin/com/spectrafilm/libraw/RawDecoder.kt:114`). This doc reverse-engineers the `.mcraw` format and lays out a
low-risk path to import a frame from one into the existing engine pipeline. ("Un-mcraw" the file.)

Evidence base: the canonical decoder published by MotionCam's author —
[`mirsadm/motioncam-decoder`](https://github.com/mirsadm/motioncam-decoder) (`Decoder.hpp`,
`Container.hpp`, `RawData.hpp`, `example.cpp`) — plus `tlenke/libmcraw`. GPL-compatible licensing
must be confirmed before vendoring (see Licensing).

## Container format (RE'd from `Container.hpp`)

A flat, append-friendly chunk stream with an index written at/near the end.

```
Header        { uint8_t ident[7]; uint8_t version; }
                ident   = {'M','O','T','I','O','N',' '}   // CONTAINER_ID
                version = 3                                 // CONTAINER_VERSION
INDEX_MAGIC_NUMBER = 0x8A905612

Item          { Type type; uint32_t size; }                // chunk header, precedes each payload
Type (enum)   { BUFFER_INDEX, BUFFER_INDEX_DATA, BUFFER, METADATA,
                AUDIO_INDEX, AUDIO_DATA, AUDIO_DATA_METADATA }

BufferOffset  { int64_t offset; int64_t timestamp; }       // one per video frame
BufferIndex   { int32_t magicNumber;                       // == INDEX_MAGIC_NUMBER
                int32_t numOffsets;
                int64_t indexDataOffset; }
AudioIndex    { int64_t numOffsets; int64_t startTimestampMs; }
AudioMetadata { int64_t timestampNs; }
```

- **Frames** are addressed by `Timestamp` (`int64_t`, nanoseconds). The `BufferIndex` →
  `BufferOffset[]` table maps each timestamp to a file offset of a `BUFFER` chunk (the
  compressed raw bytes) with a sibling `METADATA` chunk (per-frame JSON).
- **Video modes:** `VIDEO`, `TIMELAPSE`.
- **Audio** (optional) lives in `AUDIO_*` chunks; irrelevant for still import.

## Decoder API (from `Decoder.hpp`, namespace `motioncam`)

```cpp
typedef int64_t Timestamp;
class Decoder {
public:
    Decoder(const std::string& path);
    Decoder(FILE* file);                                   // ← matches our fd-based JNI path
    const nlohmann::json& getContainerMetadata() const;
    const std::vector<Timestamp>& getFrames() const;       // ordered frame timestamps
    void loadFrame(Timestamp, std::vector<uint8_t>& outData, nlohmann::json& outMetadata);
    void loadFrameMetadata(Timestamp, nlohmann::json& outMetadata);
    // + audio: audioSampleRateHz(), numAudioChannels(), loadAudio(...)
};
```

Raw codec (`RawData.hpp`) — MotionCam's **own lossless codec**, decoded in-lib (SIMDE-vectorised;
not zstd/lz4):

```cpp
size_t Decode      (uint16_t* output, int width, int height, const uint8_t* input, size_t len);
size_t DecodeLegacy(uint16_t* output, int width, int height, const uint8_t* input, size_t len);
```

`loadFrame` returns the **decompressed 16-bit Bayer mosaic** for a frame; `Decode`/`DecodeLegacy`
do the bit-unpacking. We do **not** reimplement the codec — we vendor the lib.

## Metadata keys (from `example.cpp`)

Container-level (`getContainerMetadata()`): `blackLevel` (uint16[]), `whiteLevel` (double),
`sensorArrangment` (string — CFA pattern; note the upstream typo), `colorMatrix1`/`colorMatrix2`,
`forwardMatrix1`/`forwardMatrix2` (float[]).
Per-frame (`loadFrame` outMetadata): `width`, `height`, `asShotNeutral` (float[]).

These are exactly the DNG calibration tags — which is why `example.cpp` reconstructs a CinemaDNG
per frame (tinydng: `SetImageData` + `SetCFAPattern` from `sensorArrangment` + `SetColorMatrix*` +
`SetAsShotNeutral`).

## Integration into Spektrafilm (low-risk, reuses the LibRaw→ACES path)

The current still path (file:line, from the RAW-import map):

```
MainActivity.kt:517   rawPicker (SAF OpenDocument, no MIME filter)
MainActivity.kt:531   RawDecoder.isRawFileName(name)         ← extension gate
lib/libraw/src/main/kotlin/com/spectrafilm/libraw/RawDecoder.kt:114
                      RAW_EXTENSIONS = {dng,cr2,cr3,nef,arw,raf,orf,rw2}
EngineHelpers.kt:74   decodeRawToLinear() → RawDecoder.decodeToLinear(bytes/fd)
raw_decoder_jni.cpp   nativeDecodeFd → decodeFromFd() (LibRaw → ACES2065-1 float RGB)
→ LinearImage → SpektraEngine.simulate/simulatePreview
```

**Plan A (recommended): mcraw → in-memory CinemaDNG → existing LibRaw path.**
1. New NDK module `lib:mcraw` (`libsfmcraw.so`) vendoring `mirsadm/motioncam-decoder`
   (`Decoder.cpp`, `RawData*.cpp`, `nlohmann/json`, SIMDE). Build like the existing `lib:libraw`
   CMake module (16 KB page align, 3 ABIs).
2. JNI `nativeListFrames(fd) → long[] timestamps` and `nativeExtractFrameDng(fd, timestamp) →
   byte[]` that runs `Decoder::loadFrame` + tinydng (already in the example) to emit a CinemaDNG
   blob.
3. Kotlin `McrawDecoder` facade. In the RAW picker callback, branch on `.mcraw`: extract the
   chosen frame (default: middle frame, or frame 0) to a DNG byte[], then feed it straight into
   the **unchanged** `RawDecoder.decodeToLinear(bytes)` → LibRaw → ACES → engine. Zero new color
   code; the entire existing pipeline (WB UI, ACES, downsample, recipe sidecar) just works.
4. Add `"mcraw"` to a NON-LibRaw extension set (don't add it to `RAW_EXTENSIONS`, which routes to
   LibRaw directly) and gate the new branch on it.

**Plan B (later): native frame picker.** Surface `getFrames()` as a thumbnail strip so the user
chooses which frame of the clip to develop (MotionCam clips are short bursts/video). Builds on A.

### Effort / risk
- **M–L.** The unknown is build-time: vendoring SIMDE + the codec into the NDK toolchain and the
  16 KB-page link flags. The decode itself is a solved problem (drop-in lib).
- Memory: one frame is a single still (≈ same as a DNG) — no streaming needed for v1.
- The CFA `sensorArrangment` string → DNG CFA mapping is the only fiddly bit; `example.cpp` has it.

### Licensing — RESOLVED
`motioncam-decoder` is **Apache-2.0**, which is GPLv3-compatible (Apache-2.0 → GPLv3 one-way), so
it folds into Spektrafilm's GPLv3 cleanly. SIMDE is permissive. Record both in `NOTICE.md` when the
`lib:mcraw` module lands. No licensing blocker remains.

### Container read sequence (from `Decoder.cpp`, for an exact reimplementation)
`init()` reads the `Header`, then a camera-level `METADATA` `Item`, then seeks
`EOF − (sizeof(Item) + sizeof(BufferIndex))` to read the trailing `BufferIndex` (validate
`magicNumber == 0x8A905612`), then seeks `indexDataOffset` and reads `numOffsets` × `BufferOffset`.
No endianness conversion — fields are native little-endian (Android arm64/x86). `getFrames()`
returns the `timestamp` of each `BufferOffset`, in stored order.

### Pre-flight parser (first slice)
A pure-Kotlin `McrawContainer` reader implements exactly the read sequence above (validate v3
header, walk the footer `BufferIndex` + `BufferOffset[]`, list frame timestamps) — no native code,
so the UI can reject junk and offer a frame picker before paying for a native decode. The pixel
codec (`Decode`/`DecodeLegacy`) stays owned by the vendored native lib.

---
*Reverse-engineered from public MotionCam decoder sources. Film modeling powered by spektrafilm (GPLv3).*

## Sources
- [mirsadm/motioncam-decoder](https://github.com/mirsadm/motioncam-decoder)
- [tlenke/libmcraw](https://github.com/tlenke/libmcraw)
