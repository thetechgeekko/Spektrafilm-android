# CI workflows

## `ci.yml`

Runs on every push, PR, and manual dispatch. Six jobs:

| Job | What it gates | Status today |
|-----|---------------|--------------|
| **engine-native** | The ported C++ (`engine/spektra-core/src/main/cpp/{model,kernels}`) + JNI bridge compile and link into `libspektra.so` on a host toolchain (JDK provides `jni.h`); checks the exported `spk_*` symbols exist. | **Active** — verified locally. |
| **engine-parity** | Deterministic stage parity tests (`test_simulate_e2e`, `test_filming`, `test_spatial`) run against bundled assets and committed goldens. Bit-exact gate for the ported pipeline. | **Active**. |
| **parity** | The standalone `.spkvec` comparator (`tools/parity`) builds via CMake and its `spkvec_selftest` ctest passes (byte-format compatibility between `spkvec.py` and `spkvec_io.h`). | **Active**. |
| **python-lint** | The Python parity harness (`gen_goldens.py`, `spkvec.py`) byte-compiles. | **Active**. |
| **android** | Gradle assemble of the app + engine modules (NDK-built `libspektra.so` for all ABIs); uploads the debug APK as the `SpectraFilm-debug-apk` artifact. | **Active**. |
| **android-emulator** | Downloads the APK built by `android`, boots a KVM-accelerated AVD (API 34, AOSP default, x86\_64), installs the APK, launches `MainActivity`, and asserts no `FATAL EXCEPTION`/`UnsatisfiedLinkError`. Closes issue #5. | **Active** — requires GitHub `ubuntu-latest` runner (has `/dev/kvm`). |

### android-emulator details

- **Depends on:** `android` job (via `needs: android` + `actions/download-artifact@v4`).  Reuses the already-built APK rather than rebuilding; no NDK/CMake/Gradle duplication.
- **AVD:** API 34, target `default` (AOSP — no Google Play/APIs), arch `x86_64`, profile `Nexus 6`.
- **Emulator flags:** `-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -camera-back none` — headless, software renderer, fastest cold boot.
- **AVD caching:** `actions/cache@v4` on `~/.android/avd/*` (key `avd-api34-x86_64-v1`).  On a cache hit the AVD creation step is skipped entirely.
- **Smoke check assertions (both must pass):**
  1. `logcat -d` must contain **no** line matching `FATAL EXCEPTION` or `UnsatisfiedLinkError` for the `com.spectrafilm.app` process.
  2. `adb shell dumpsys activity activities` must show `com.spectrafilm.app/.MainActivity` in the activity stack after an 8-second settle wait.
- **Debug artifacts** (`emulator-smoke-artifacts`): screenshot (`screencap -p`) + full logcat, uploaded with `if: always()` so failures are always debuggable.
- **KVM note:** GitHub `ubuntu-latest` runners expose `/dev/kvm`; the cloud dev sandbox used for editing does not.  The udev rule `echo 'KERNEL=="kvm"...' | sudo tee /etc/udev/rules.d/99-kvm4all.rules` + `udevadm trigger` grants the runner user KVM access before the emulator boots.

### Notes
- `engine-native` uses `-Wall -Wextra` (not `-Werror`) while the JNI layer is still M0 stubs with intentionally-unused parameters; tighten to `-Werror` once the bridge is implemented (M3).
- Generating *real* golden vectors needs a `spektrafilm` Python environment and runs out-of-CI for now (see `tools/parity/README.md`); CI only byte-compiles the generator. Once the engine port begins (M3), add a step that runs `spkvec_compare` against committed tiny goldens.
- When the host lands, extend `android` to build all ABIs and (optionally) run instrumented/unit tests.
