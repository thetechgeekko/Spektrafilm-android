# GPU device probes

Standalone arm64 executables that measure a GPU kernel or route against the CPU engine on real
device hardware, outside the app. **None of them is wired into the app or into CI**; each answered
one question, and the answer is recorded where the table says. They stay in the tree because the
engine and the research documents cite them as the evidence behind shipped decisions.

Strict Exact CPU remains the parity-bearing route and the automatic fallback; a probe result
qualifies exactly what it measured (one kernel, one device, one build) and nothing else.

## Inventory

| Source | Question | Answer recorded in | Build |
|---|---|---|---|
| `probe_main.cpp` (`gpu_probe`, M1, #135 E3) | Does the fp32 scan integral sit inside the oracle band against an f64 mirror? | `docs/research/gpu-device-probe.md` | `build_push_run.sh` |
| `probe_m2_main.cpp` (`gpu_probe_m2`, M2, #147) | Same for the filming and printing chains (`filming.comp` / `printing.comp`, later promoted into `engine/.../gpu/`) | `docs/research/gpu-device-probe.md` | `build_push_run.sh` (needs glslc) |
| `probe_rt_main.cpp` (#208, #207) | The host's real per-call cost for a realtime frame (10.5 ms, not 23-46) | commit `68195c6`; map #207 | `build_push_run_rt.sh` |
| `probe_diffusion_main.cpp` (#213) | Does a GPU port of camera diffusion pay off at export scale? (no) | commit `60711b3`; `docs/research/perf-lab.md` | `build_push_run_diffusion.sh` |
| `probe_grain_main.cpp` (#214) | GPU grain sampler throughput and distribution; carrying the dye-cloud blur in the shader | commits `82fe11b`, `595a2ad` | `build_push_run_grain.sh` |
| `census_grain_branches.cpp` (#214) | Which sampler branch the grain work lands in, before writing a shader | commit `1488ac8` | hand-built (compile line in the commit message) |
| `probe_spatial_main.cpp` (#215) | Spatial stages CPU vs GPU; why the GPU blur is refused for IIR-class sigmas | commit `3ca85f5` | `build_push_run_spatial.sh` |
| `probe_f32_fft_main.cpp` | f32 through the engine's FFT convolution: accurate, and no faster | commit `f75f95c` | `build_push_run_f32fft.sh` |
| `probe_fft_shape_main.cpp` | The diffusion transform at 2.89x: pairing, rectangle, tiled transpose, radix 16 | commit `1adc6fb`; `docs/research/perf-lab.md` | hand-built (compile line in the commit message) |
| `probe_route_compare_main.cpp` (#221) | A whole GPU render against a whole CPU render, which nothing else compared | commit `1cbde83` | hand-built (compile line in the commit message) |

`gpu_dispatch.{cpp,h}` and `ref_{filming,printing,scan}_f64.{cpp,h}` are the shared Vulkan
dispatch shim and the f64 CPU mirrors the M1/M2 probes compare against.

## The M1/M2 probes

`gpu_probe` measures the SCAN integral via the **unmodified** `spk::gpu::scan_spectral` host and the
engine's `scan_spectral.comp`. `gpu_probe_m2` measures the FILMING chain (ProPhoto→tc/b →
Mitchell-cubic tc_lut interp → log10 → density-curve interp → pointwise DIR couplers) and the
PRINTING chain (film CMY → 81-band dichroic-filtered spectral integral → midgray/round-trip → paper
density-curve interp) as probe-local shaders; tables are folded on the host through the engine's own
builders, and each run also executes the real engine stage on-device and prints mirror-vs-engine,
engine-vs-golden (must PASS) and GPU-vs-mirror/engine/golden numbers.

Run (phone on USB, NDK r28c installed):

```bash
bash tools/gpu_probe/build_push_run.sh
```

- Tier 0 `caps`: device identity, `shaderFloat64/16`, the full float-controls block.
- Tier 1 `run`: golden density plane + 64³ CMY sweep + NaN case; `max_abs`/`rms` vs f64,
  determinism ×5 (byte-compare).
- Tier 2 `perf`: warm-call wall time at 0.3 MP / 12 MP (includes the host's per-call
  buffer+pipeline rebuild — offload cost, not pure kernel time).
- Tier 3 (auto, needs glslc): `precise` (NoContraction) and `mediump` (RelaxedPrecision) shader
  variants, same Tier 1 run.
- M2 (needs glslc): `film` + `print` subcommands over the `tools/parity/goldens/{scan,print}_portra`
  inputs + 64³ sweeps + NaN case, determinism ×5, and the same `precise`/`mediump` brackets.

Results land in `tools/gpu_probe/captures/` (untracked).

Film modeling powered by spektrafilm (GPLv3).
