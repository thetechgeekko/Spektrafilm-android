# CI workflows

## `ci.yml`

Runs on every push, PR, and manual dispatch. Four jobs:

| Job | What it gates | Status today |
|-----|---------------|--------------|
| **engine-native** | The ported C++ (`engine/spektra-core/src/main/cpp/{model,kernels}`) + JNI bridge compile and link into `libspektra.so` on a host toolchain (JDK provides `jni.h`); checks the exported `spk_*` symbols exist. | **Active** — verified locally. |
| **parity** | The standalone `.spkvec` comparator (`tools/parity`) builds via CMake and its `spkvec_selftest` ctest passes (byte-format compatibility between `spkvec.py` and `spkvec_io.h`). | **Active**. |
| **python-lint** | The Python parity harness (`gen_goldens.py`, `spkvec.py`) byte-compiles. | **Active**. |
| **android** | Gradle assemble of the app + the three new modules. | **Skipped until M1** — guarded by `hashFiles('settings.gradle.kts')`, so it auto-activates the moment the ImageToolbox host is seeded. No edit needed. |

### Notes
- `engine-native` uses `-Wall -Wextra` (not `-Werror`) while the JNI layer is still M0 stubs with intentionally-unused parameters; tighten to `-Werror` once the bridge is implemented (M3).
- Generating *real* golden vectors needs a `spektrafilm` Python environment and runs out-of-CI for now (see `tools/parity/README.md`); CI only byte-compiles the generator. Once the engine port begins (M3), add a step that runs `spkvec_compare` against committed tiny goldens.
- When the host lands, extend `android` to build all ABIs and (optionally) run instrumented/unit tests.
