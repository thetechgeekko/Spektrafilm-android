# Export cache and idle pre-render — device evidence (#179)

**Historical:** app 0.9.0 (versionCode 11), Strict Exact CPU route, before v0.10.0 made the Fast
GPU route the default. The release-candidate SLO proof is #186. Device SM-S948W (Tier A hardware),
API 36, unplugged for every sample, release build. Captured 2026-09-02 and 2026-09-03. Raw capture
of the passing run: [`capture-gate-clean.json`](capture-gate-clean.json); its report re-renders
with `python tools/baseline/bench_report.py docs/device/ticket179/capture-gate-clean.json --gate`.

The first (2026-09-02) capture failed the Tier A gate on environmental preconditions only (thermal
status 2 during one run, battery below the 50 % floor); it was rerun and its files were removed on
2026-09-14 (git history keeps them). It was also the first capture taken under the protocol's 60 s
idle — see the dated correction in [DEVICE_EXPORT_BASELINE.md](../../DEVICE_EXPORT_BASELINE.md).

## 1. Idle pre-render — payload fidelity

`ticket177_phase prerender` on APK `ea390f9397c49633…`: render once, encode the live engine
result, encode the payload written to disk and mapped back, compare the containers under a
pinned clock.

| format | live | restored | payload write | restore + encode |
|---|---|---|---:|---:|
| JPEG_Q95 | `70145513a444` | `70145513a444` | 97 ms | **272 ms** |
| PNG16 | `0cd14d142b35` | `0cd14d142b35` | 68 ms | 1969 ms |
| TIFF16 | `39f29adbf7f7` | `39f29adbf7f7` | 91 ms | 1965 ms |

Payload 149 817 600 bytes (12.5 MP × 3 × float32). The container digests are identical, so the
native TIFF/PNG16 writers read a `MappedByteBuffer` across JNI exactly as they read an engine
allocation — the property the JVM tests cannot reach.

Rerun on the build `6b9f193663d0fe88…` (HEAD `b0e032d`) after the #175 writer work:
digests still identical live-vs-restored, and restore+encode is **261 / 781 / 540 ms** for
JPEG / PNG16 / TIFF16 — the PNG16 and TIFF16 figures are 2.5× and 3.6× faster than the row
above, and PNG16's digest moved to `74237c9d8543` because the banded deflate re-baselined the
container under #126 C4 (decoded pixels unchanged). See
[`prerender-identity.txt`](prerender-identity.txt).

A first export served from a payload is therefore **~0.27 s (JPEG) / ~2.0 s (PNG16, TIFF16)**
against 6.2–7.4 s rendered.

## 2. Editor wiring

With the editor open on a 12 MP source and left alone, the release build logs

```
I Spektra: stage timings ms [export id=2]: preprocess=153.0 … grain=2337.9 … glare_field=205.7
I Spektra: pre-rendered export in 9396 ms
```

so the 5 s idle trigger fires, renders at full resolution and stores the payload. (9.4 s because
the recipe on screen carried grain, halation and DIR couplers.)

**Not yet verified on a device:** an export *consuming* that payload through the editor's own
export path. It needs a UI export on the owner's phone, which writes to their gallery; the
instrumentation above proves the payload round-trip and the store, and both call sites build
the key from the same `decodeIdentityOf` helper, but the editor-to-export hit itself is
unproven.

## 3. The Tier A SLO capture (gate-clean)

`bench-report: OK`, no findings. 2026-09-03, APK `6b9f193663d0fe88…` (HEAD `b0e032d`), 44
samples, unplugged for every one of them (`plugged: 0`), battery **79% → 71%** against the 50%
floor, and the per-sample thermal wait never had to give up.

| format | render (the one miss) | cache hits, n = 10 | p50 | p95 |
|---|---:|---|---:|---:|
| **JPEG_Q95** (SLO) | 5831 ms | 29 … 68 ms | **32 ms** | **68 ms** |
| ULTRA_HDR | 6458 ms | 32 … 77 ms | 51 ms | 77 ms |
| PNG16 | 6016 ms | 79 … 133 ms | 106 ms | 133 ms |
| TIFF16 | 12030 ms | 295 … 413 ms | 340 ms | 413 ms |

The #126 SLO binds BASE/JPEG_Q95 on the cache-hit path, and the gate evaluates exactly that
subset (10 warm hits, meeting the 11-run protocol's 10 after its discarded first run):
**p50 32 ms against 2000 ms, p95 68 ms against 3000 ms** — inside by ~63× and ~44×.

**Exactness.** One distinct `container_sha256` and one distinct `decoded_sample_sha256` per
format across all 11 samples. These are the same digests the #175 captures recorded on two earlier
builds, so the cache hit publishes the bytes the render produced, and does so identically across
three independent builds of the app.

Peak RSS 1346 MB, i.e. bounded and below the 1.5–1.8 GB the ungated runs reached.

Two differences from the failed capture are worth naming, because both are the protocol working
rather than noise:

- **The failed capture's PNG16 first-hit outlier (1215 ms, a cold page cache after the idle) did
  not recur**: every PNG16 hit here lands in 93–133 ms, so p95 is 133 ms rather than 1215 ms.
- **TIFF16's miss is 12030 ms, and that is throttling, not TIFF.** It is the third full 12 MP
  render inside run 0, which the protocol runs back to back (the 60 s idle sits between runs,
  not between the formats of one run), and it is the only sample reporting
  `thermal_status: 1`. Every phase roughly doubled together — decode 961 → 1847 ms, simulate
  4644 → 9427 ms, encode 399 → 721 ms — which is what a clock drop looks like and is not
  something any single stage can cause. It is a miss, so it is not an SLO sample.

*Film modeling powered by spektrafilm (GPLv3).*
