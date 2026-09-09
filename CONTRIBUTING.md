# Contributing

Thanks for looking. This project is a parity-first port of a research engine, so the bar for
engine changes is unusual: **numbers before opinions**. Read this before opening a pull request.

## The one rule that governs everything

The Strict Exact CPU route must stay bit-exact against the pinned Python oracle. "Bit-exact" here
means within the declared tolerance (`max_abs <= 1e-4`, `rms <= 1e-5`) **and** byte-identical across
worker counts for the same build. It does not mean identical bytes across CPU architectures or
compiler flags — `-ffast-math` reassociates, and that is expected.

Any change under `engine/spektra-core/src/main/cpp/**` must keep the parity suite green. There is no
exception for "it looked the same to me".

## Before you start

Read [`docs/EXECUTION_INDEX.md`](docs/EXECUTION_INDEX.md). It declares which documents are current
authority and which are historical evidence. Dated files (`*_2026-*`, `AUDIT.md`) record what was
true when written; they are not a work queue.

Live work is tracked in GitHub Issues. See [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md)
for the protocol and [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md) for labels.

## Toolchain

| Tool | Pinned version |
|---|---|
| JDK | 21 (use Android Studio's JBR; a PATH JDK 26 breaks Gradle) |
| Gradle / AGP | 9.5.1 / 9.3.2 (Kotlin 2.2.10 built in) |
| NDK | r28c (`28.2.13676358`) |
| CMake | 3.22.1 |
| Build tools | 36.0.0 |

```bash
sdkmanager "ndk;28.2.13676358" "cmake;3.22.1" "build-tools;36.0.0"
```

## Running the gates

```bash
# JVM unit tests and lint
./gradlew :app:testDebugUnitTest :app:lint

# Debug APK for all three ABIs
./gradlew :app:assembleDebug

# The engine parity suite — the real gate. Runs on the host g++ toolchain.
tools/parity/run_engine_parity.sh /tmp/parity-out

# The second leg, at the flags the release APK actually ships with
SPK_PARITY_EXTRA_FLAGS="-O3 -ffast-math -fno-finite-math-only" \
  tools/parity/run_engine_parity.sh /tmp/parity-ship
```

Both legs must pass. CI runs them as a matrix because the release APK's numerics were otherwise
never gated.

## Things that will fail review

- **A new `.cpp` not added to `engine/.../cpp/CMakeLists.txt`.** The host parity build globs; the
  Android build enumerates. A missing entry passes every host gate and fails only at the Android
  link step. Check with `tools/arm64_check/check_android_link.sh`.
- **A JNI-resolved symbol without a keep rule.** R8 removes members that only native code names by
  string. This shipped a real defect once: 19 engine parameters silently marshalled as `0.0` in
  every release build. `tools/r8_check/check_release_dex.sh` gates it now.
- **A performance claim from a debug build.** Debug and release differ unevenly per module. State
  the build type on every timing, and never size a lever from a debug APK.
- **A GPU change without a host gate.** The Fast GPU route is tolerance-bounded and fails closed to
  the CPU. New passes need a lavapipe gate that measures against the f64 CPU stage and proves the
  device actually engaged.

## Numeric work on the GPU

If you touch `engine/.../gpu/`, two hard-won rules apply:

1. **The shader derives no parameter of its own.** Sigmas, filter classes, weights, coefficients and
   tap radii are computed on the host in f64 and shipped in a table. Anything quantised — a class
   flag, an integer radius — will disagree with the f64 CPU somewhere reachable.
2. **Never silently clamp what the oracle does not clamp.** If the GPU cannot hold a parameter range
   inside the tolerance band, refuse the request and let the CPU render it.

## Commits and pull requests

Conventional commit prefixes: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`.

A pull request should say what changed, what it measured, and which gates ran. Evidence beats
description: paste the numbers.

## Licence

GPL-3.0. This project is a derivative of the GPLv3 spektrafilm engine, so contributions are GPLv3
too. The attribution "Film modeling powered by spektrafilm" must stay.
