/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import com.spectrafilm.engine.SpektraEngine

/**
 * Return the GPU context's grow-only buffers to the process memory budget after a
 * full-resolution render (#269).
 *
 * The Vulkan kernels keep their staging/work buffers sized for the largest frame
 * they have seen. On SM-S948W one 12.5 MP export left ~1.1 GiB of gpu_host reserved
 * afterwards, leaving the 1.5 GiB budget ~380 MiB for everything that followed, and a
 * later export in the same session failed with "native allocation failed". Releasing
 * is output-neutral (test_gpu_host gates byte-identity across a release); the cost is
 * rebuilding pipelines on the next GPU call, paid once after an export rather than on
 * every preview.
 *
 * Call from a background thread: it waits for the GPU lock if a render holds it.
 */
internal fun releaseGpuAfterFullResRender(reason: String) {
    runCatching { SpektraEngine.releaseGpuResources() }
        .onSuccess { released -> if (released) Diag.i("gpu resources released after $reason") }
        .onFailure { Diag.w("gpu resource release after $reason failed: ${it.message}") }
}
