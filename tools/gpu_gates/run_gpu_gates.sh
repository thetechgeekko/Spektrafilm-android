#!/usr/bin/env bash
# Runs every host GPU gate under a Vulkan ICD (lavapipe on CI and in cloud
# sandboxes). Used by ci.yml's "Engine (C++ host build)" job and by
# gpu-gates.yml, which runs it automatically when native render code changes.
#
# Why this exists: the Fast GPU route is what every user gets by default
# (AppSettings.gpuEngine / gpuExportEngine always return true), but the host
# parity suite compiles WITHOUT SPK_ENABLE_VULKAN, so every GPU branch is dead
# there. These gates are the only automated check of the shipping route.
#
# The engine sources are compiled ONCE per flag leg into objects and each test
# links against them, instead of recompiling the whole engine per test (that
# was ~1 min of runner time per test per leg).
#
# Every gate must ENGAGE a device: each binary also passes vacuously without
# one (it then checks only the CPU-fallback law), so a broken ICD would
# otherwise turn this into a green no-op.
#
# Usage:  tools/gpu_gates/run_gpu_gates.sh [build_dir]
# Env:    VK_ICD_FILENAMES  defaults to the lavapipe ICD
#         JOBS=<n>          parallel compile jobs (default: CPU count)
#         SPK_VULKAN_INCLUDE / SPK_VULKAN_LIB  for a box without libvulkan-dev
#           (header-only Vulkan-Headers checkout + libvulkan.so.1 by path)
#
# Film modeling powered by spektrafilm (GPLv3).
set -euo pipefail
shopt -s nullglob

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CPP="$ROOT/engine/spektra-core/src/main/cpp"
ASSET="$ROOT/engine/spektra-core/src/main/assets/spektra"
OUT="${1:-${TMPDIR:-/tmp}/spk-gpu-gates}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"
export VK_ICD_FILENAMES="${VK_ICD_FILENAMES:-/usr/share/vulkan/icd.d/lvp_icd.json}"

VK_INC=()
[ -n "${SPK_VULKAN_INCLUDE:-}" ] && VK_INC=(-I"$SPK_VULKAN_INCLUDE")
VK_LIB="${SPK_VULKAN_LIB:--lvulkan}"

if [ ! -f "$VK_ICD_FILENAMES" ]; then
    echo "::error::Vulkan ICD not found at $VK_ICD_FILENAMES (install mesa-vulkan-drivers)"
    exit 1
fi

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$CPP"
SRC=(spektra.cpp gpu/*.cpp kernels/*.cpp io/*.cpp model/*.cpp profiles/*.cpp runtime/*.cpp runtime/stages/*.cpp)

failed=()

# gate <leg-name> <test.cpp> <binary-name> <engaged-regex> [args...]
gate() {
    local leg="$1" test="$2" name="$3" engaged="$4"
    shift 4
    local flags_var="FLAGS_$leg"
    local -a flags=(${!flags_var})
    local bin="$OUT/$leg/$name" log="$OUT/$leg/$name.out"
    echo "== $name ($leg) =="
    if ! g++ -std=c++17 "${flags[@]}" -pthread -I. -I"$ROOT/tools/parity" "${VK_INC[@]}" \
            -DSPK_ENABLE_VULKAN=1 "$test" "$OUT/$leg"/obj/*.o "$VK_LIB" -o "$bin"; then
        failed+=("$name/$leg: build"); return
    fi
    "$bin" "$@" >"$log" 2>&1 || true
    cat "$log"
    if ! grep -qE "$engaged" "$log"; then
        echo "::error::$name ($leg): no GPU engaged - this gate validated nothing"
        failed+=("$name/$leg: not engaged"); return
    fi
    if ! grep -q "$name: ALL OK" "$log"; then
        failed+=("$name/$leg: FAIL"); return
    fi
}

# Two legs, like engine-parity: the host side of every comparison (blur
# tables, reference expose, mean/variance reductions) is f64 code that the
# shipping -ffast-math is free to reassociate. The shaders themselves do not
# see host flags; the bars they are held to do.
FLAGS_o2="-O2"
FLAGS_ship="-O3 -ffast-math -fno-finite-math-only"

for leg in o2 ship; do
    flags_var="FLAGS_$leg"
    mkdir -p "$OUT/$leg/obj"
    echo "== compiling engine objects ($leg: ${!flags_var}) =="
    # One object per source; names flattened so runtime/stages/x.cpp and a
    # same-named file elsewhere cannot collide.
    printf '%s\n' "${SRC[@]}" | xargs -P "$JOBS" -I{} sh -c '
        src="$1"; obj="$2/$(echo "$src" | tr / _).o"
        g++ -std=c++17 $3 -pthread -I. $4 -DSPK_ENABLE_VULKAN=1 -c "$src" -o "$obj"
    ' _ {} "$OUT/$leg/obj" "${!flags_var}" "${VK_INC[*]:-}"

    if [ "$leg" = o2 ]; then
        # Shader math vs the CPU reference + the pointwise-chain contract.
        # -O2 only, as it always was in ci.yml.
        gate o2 tests/test_gpu_host.cpp test_gpu_host "self-check passed \(state == 1\)" "$ASSET"
    fi
    gate "$leg" gpu/tests/test_pointwise_chain.cpp test_pointwise_chain "vulkan_available=1"
    gate "$leg" gpu/tests/test_halation_gpu.cpp test_halation_gpu "engaged=1"
    gate "$leg" gpu/tests/test_grain_gpu.cpp test_grain_gpu "engaged=1"
    gate "$leg" gpu/tests/test_fft_convolve_gpu.cpp test_fft_convolve_gpu "engaged=1"
    gate "$leg" gpu/tests/test_filming_stage_gpu.cpp test_filming_stage_gpu "engaged=1"
    gate "$leg" gpu/tests/test_contrast_mask_gpu.cpp test_contrast_mask_gpu "engaged=1" ../assets/spektra
    gate "$leg" tests/test_band_seam_sweep.cpp test_band_seam_sweep "engaged=1" ../assets/spektra
done

# A new gpu/tests/*.cpp must be added above, not silently skipped.
for t in gpu/tests/test_*.cpp; do
    name="$(basename "$t" .cpp)"
    if ! grep -q "gpu/tests/$name.cpp" "$ROOT/tools/gpu_gates/run_gpu_gates.sh"; then
        echo "::error::$t is not wired into run_gpu_gates.sh"
        failed+=("$name: not wired")
    fi
done

if [ "${#failed[@]}" -gt 0 ]; then
    echo "GPU gates FAILED:"; printf '  %s\n' "${failed[@]}"
    exit 1
fi
echo "GPU gates: ALL OK"
