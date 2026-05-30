# engine:spektra-core

Native (C++/NDK) port of spektrafilm's `runtime` + `model` packages, exposed to the app through
a thin JNI + Kotlin facade.

> **Status: contract only (M0).** This directory currently defines the *API surface* — the C++
> header, the JNI bridge signatures, and the Kotlin facade/params. The implementation
> (`spektra.cpp` and the per-stage `.cpp` files) is filled in across M3–M4 per
> `../../docs/PORTING_PLAN.md`, gated by the golden-vector parity harness.

## Layout (target)

```
spektra-core/
├── build.gradle.kts                     # convention plugins + externalNativeBuild(CMake)
├── src/main/cpp/
│   ├── CMakeLists.txt                   # builds libspektra.so
│   ├── spektra.h                        # ★ engine C API (the contract)
│   ├── spektra.cpp                      # engine entry (stub in M0)
│   ├── spektra_jni.cpp                  # JNI ↔ C API bridge (stub in M0)
│   ├── model/                           # density_curves, couplers, grain, diffusion, ...
│   ├── runtime/                         # pipeline + stages (filming/printing/scanning)
│   └── kernels/                         # gaussian, interp_lut, stats, spectral_upsampling
├── src/main/kotlin/com/spectrafilm/engine/
│   ├── SpektraEngine.kt                 # ★ Kotlin facade (loads .so, marshals buffers)
│   └── SpektraParams.kt                 # ★ params mirror of RuntimePhotoParams
└── src/main/assets/spektra/             # profiles/ luts/ filters/ icc/  (see docs/ASSETS.md)
```

## Contract

- C API: see [`src/main/cpp/spektra.h`](src/main/cpp/spektra.h).
- Kotlin: [`SpektraEngine`](src/main/kotlin/com/spectrafilm/engine/SpektraEngine.kt),
  [`SpektraParams`](src/main/kotlin/com/spectrafilm/engine/SpektraParams.kt).

The Kotlin/C API mirror spektrafilm's `simulate(image, params)` /
`simulate_preview(image, params)` and the `RuntimePhotoParams` dataclass tree so behavior can be
checked stage-by-stage against the upstream Python.
