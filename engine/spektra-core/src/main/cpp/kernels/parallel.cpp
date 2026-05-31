/*
 * Spektrafilm for Android — native engine: deterministic parallel-for (worker
 * count resolution). GPLv3. Film modeling powered by spektrafilm (GPLv3).
 */
#include "kernels/parallel.h"

#include <cstdlib>
#include <thread>

namespace spk {

int parallel_num_threads() {
    // Explicit override (tests, or a host that wants to pin the worker count).
    if (const char* env = std::getenv("SPK_NUM_THREADS")) {
        const int n = std::atoi(env);
        if (n >= 1) return n;
    }
    const unsigned hw = std::thread::hardware_concurrency();
    return hw == 0 ? 1 : static_cast<int>(hw);
}

}  // namespace spk
