/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * Auto preview resolution (#299). Picks the settle-preview and live-draft edges from
 * what the device can afford, then keeps refining the draft edge from how long drafts
 * actually take on this device. App-side only: it chooses `previewMaxSize` and the
 * draft edge and never changes engine math, so parity is unaffected.
 */
package com.spectrafilm.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.spectrafilm.engine.SpektraEngine

/** What the device reports about itself. Every field may be 0 / false when unknown. */
internal data class DeviceRenderProfile(
    val physicalBytes: Long,
    val lowRamDevice: Boolean,
    /** `Build.VERSION.MEDIA_PERFORMANCE_CLASS` (API 31+), 0 when undeclared. */
    val mediaPerformanceClass: Int,
    val cpuCores: Int,
    /** Cores within 80% of the fastest core's max clock (NDK sysfs probe). */
    val bigCores: Int,
    /** Sum of every core's max clock in kHz (NDK sysfs probe). */
    val aggregateMaxKHz: Long,
    /** Longest edge of the display in physical pixels. */
    val displayLongEdgePx: Int,
)

internal enum class PreviewTier { LOW, BASELINE, HIGH, MAX }

internal data class AutoPreviewEdges(
    val tier: PreviewTier,
    /** Settle preview long edge; inside the existing 128..1024 slider range. */
    val previewEdge: Int,
    /** Starting live-draft long edge; inside the existing 128..512 draft range. */
    val draftEdge: Int,
)

internal const val PREVIEW_EDGE_MIN = 256
internal const val PREVIEW_EDGE_MAX = 1024
internal const val DRAFT_EDGE_MIN = 128
internal const val DRAFT_EDGE_MAX = 512
private const val EDGE_STEP = 64

/**
 * Map a device profile to starting edges. Tiers, not a formula: the boundaries are
 * conservative starting guesses, and [DraftEdgeCalibrator] corrects the draft edge
 * from measured timings, so a wrong tier costs a few frames, not the session.
 *
 * RAM picks the base tier (the same physical-RAM bands as [deviceMemoryPolicy], so
 * the preview never asks for more than the process memory budget is sized for);
 * CPU strength and the platform performance class move it one step either way.
 * Missing telemetry keeps the baseline (640 / 384, i.e. the old fixed defaults).
 */
internal fun autoPreviewEdges(p: DeviceRenderProfile): AutoPreviewEdges {
    var tier = when (deviceMemoryPolicy(p.physicalBytes).tier) {
        DeviceMemoryTier.COMPACT -> if (p.physicalBytes <= 0L) PreviewTier.BASELINE else PreviewTier.LOW
        DeviceMemoryTier.STANDARD -> PreviewTier.BASELINE
        DeviceMemoryTier.LARGE -> PreviewTier.HIGH
        DeviceMemoryTier.XLARGE, DeviceMemoryTier.XXLARGE -> PreviewTier.MAX
    }
    // Weak CPU: few cores, or (when the clock probe worked) little aggregate clock.
    // 12 GHz aggregate is roughly an older 8x1.5 GHz part. Estimate, not measured.
    val weakCpu = (p.cpuCores in 1..4) ||
        (p.aggregateMaxKHz in 1L until 12_000_000L)
    // Strong CPU: modern flagship class; Android 13+ performance class or a big
    // cluster of 4+ fast cores with lots of aggregate clock.
    val strongCpu = p.mediaPerformanceClass >= Build.VERSION_CODES.TIRAMISU ||
        (p.bigCores >= 4 && p.aggregateMaxKHz >= 20_000_000L)
    if (weakCpu) tier = tier.down()
    else if (strongCpu && tier < PreviewTier.HIGH) tier = tier.up()
    if (p.lowRamDevice) tier = PreviewTier.LOW

    val (preview, draft) = when (tier) {
        PreviewTier.LOW -> 512 to 256
        PreviewTier.BASELINE -> 640 to DRAFT_RENDER_MAX_PX
        PreviewTier.HIGH -> 896 to 448
        PreviewTier.MAX -> 1024 to 512
    }
    // No point rendering a preview much larger than the screen can show at fit zoom.
    val screenCap = if (p.displayLongEdgePx > 0) {
        roundDownToStep(p.displayLongEdgePx).coerceAtLeast(PREVIEW_EDGE_MIN)
    } else {
        PREVIEW_EDGE_MAX
    }
    val previewEdge = preview.coerceAtMost(screenCap).coerceIn(PREVIEW_EDGE_MIN, PREVIEW_EDGE_MAX)
    val draftEdge = draft.coerceAtMost(previewEdge).coerceIn(DRAFT_EDGE_MIN, DRAFT_EDGE_MAX)
    return AutoPreviewEdges(tier, previewEdge, draftEdge)
}

private fun PreviewTier.down(): PreviewTier = PreviewTier.entries[(ordinal - 1).coerceAtLeast(0)]
private fun PreviewTier.up(): PreviewTier =
    PreviewTier.entries[(ordinal + 1).coerceAtMost(PreviewTier.entries.size - 1)]

private fun roundDownToStep(px: Int): Int = (px / EDGE_STEP) * EDGE_STEP

/**
 * Runtime correction of the live-draft edge. Hardware specs mispredict under thermal
 * throttling and background load, so each successful draft reports its wall time and
 * the edge moves one 64 px step at a time:
 *   - two consecutive drafts slower than [slowMs], or one slower than 2x [slowMs],
 *     step down (a single mildly slow outlier — GC, a thermal blip — does not);
 *   - a step-down is then judged on the next [probeSamples] drafts: unless their
 *     median is below [keepRatio] of the slow time that triggered it, the edge goes
 *     back up and becomes this session's floor (smaller bought no speed);
 *   - [fastStreak] consecutive drafts faster than [fastMs] step up (cautiously).
 * Bounded by [minEdge]..[maxEdge]; the auto preview edge caps [maxEdge] so a draft is
 * always a real step down from the settle render.
 */
internal class DraftEdgeCalibrator(
    initialEdge: Int,
    private val minEdge: Int = DRAFT_EDGE_MIN,
    maxEdge: Int = DRAFT_EDGE_MAX,
    private val slowMs: Long = 120L,
    private val fastMs: Long = 50L,
    private val fastStreak: Int = 3,
    /** Drafts to judge a step-down by before keeping or reverting it. */
    private val probeSamples: Int = 3,
    /** A step-down is kept only if the new edge's median is below this share of the old. */
    private val keepRatio: Double = 0.8,
) {
    private val maxEdge = maxEdge.coerceAtLeast(minEdge)
    private var fastCount = 0
    private var slowCount = 0
    private var slowSumMs = 0L

    // Pending step-down under evaluation: the edge we left and its slow time.
    private var probeFromEdge = 0
    private var probeFromMs = 0L
    private val probe = ArrayList<Long>(probeSamples)

    // Lowest edge worth using this session. Raised when a step-down bought nothing:
    // on-device (SM-S948W, debug) draft time was dominated by per-draft fixed cost,
    // so 320 px drafts took as long as 512 px ones and shrinking only cost quality.
    private var floorEdge = minEdge

    @Volatile var edge: Int = roundDownToStep(initialEdge).coerceIn(minEdge, this.maxEdge)
        private set

    /** Record one completed draft rendered at [atEdge] that took [elapsedMs]. */
    @Synchronized
    fun record(atEdge: Int, elapsedMs: Long): Int {
        // A result for an edge we already moved away from says nothing about the
        // current edge; ignore it rather than double-stepping.
        if (atEdge != edge || elapsedMs < 0L) return edge
        if (probeFromEdge != 0) {
            probe += elapsedMs
            if (probe.size < probeSamples) return edge
            val median = probe.sorted()[probe.size / 2]
            if (median >= keepRatio * probeFromMs) {
                // Smaller draft was not meaningfully faster: go back and stop here.
                edge = probeFromEdge
                floorEdge = probeFromEdge
            }
            probeFromEdge = 0
            probe.clear()
            resetStreaks()
            return edge
        }
        when {
            elapsedMs > slowMs -> {
                fastCount = 0
                slowCount += 1
                slowSumMs += elapsedMs
                if ((slowCount >= 2 || elapsedMs > 2 * slowMs) && edge > floorEdge) {
                    probeFromEdge = edge
                    probeFromMs = slowSumMs / slowCount
                    edge = (edge - EDGE_STEP).coerceAtLeast(floorEdge)
                    resetStreaks()
                } else if (slowCount >= 2) {
                    resetStreaks()
                }
            }
            elapsedMs < fastMs -> {
                slowCount = 0
                slowSumMs = 0
                fastCount += 1
                if (fastCount >= fastStreak) {
                    fastCount = 0
                    edge = (edge + EDGE_STEP).coerceAtMost(maxEdge)
                }
            }
            else -> resetStreaks()
        }
        return edge
    }

    private fun resetStreaks() {
        fastCount = 0
        slowCount = 0
        slowSumMs = 0
    }
}

/** Reads [DeviceRenderProfile] once per process and owns the session's calibrator. */
internal object AdaptivePreview {
    @Volatile private var cachedProfile: DeviceRenderProfile? = null
    @Volatile private var cachedEdges: AutoPreviewEdges? = null
    @Volatile private var calibrator: DraftEdgeCalibrator? = null

    fun profile(context: Context): DeviceRenderProfile {
        cachedProfile?.let { return it }
        return synchronized(this) {
            cachedProfile ?: readProfile(context.applicationContext).also { cachedProfile = it }
        }
    }

    fun edges(context: Context): AutoPreviewEdges {
        cachedEdges?.let { return it }
        return synchronized(this) {
            cachedEdges ?: autoPreviewEdges(profile(context)).also { e ->
                cachedEdges = e
                Diag.i("auto preview tier=${e.tier.name.lowercase()} preview=${e.previewEdge} " +
                    "draft=${e.draftEdge} profile=${cachedProfile}")
            }
        }
    }

    /** One calibrator per process; its ceiling is the auto settle edge. */
    fun calibrator(context: Context): DraftEdgeCalibrator {
        calibrator?.let { return it }
        return synchronized(this) {
            calibrator ?: edges(context).let { e ->
                DraftEdgeCalibrator(e.draftEdge, maxEdge = minOf(DRAFT_EDGE_MAX, e.previewEdge - EDGE_STEP))
            }.also { calibrator = it }
        }
    }

    private fun readProfile(context: Context): DeviceRenderProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val perfClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.VERSION.MEDIA_PERFORMANCE_CLASS
        } else {
            0
        }
        // NDK sysfs probe; the engine library may not be loadable in every context
        // (e.g. a broken split install) — fall back to the JVM's core count.
        val topo = runCatching { SpektraEngine.cpuTopology() }
            .onFailure { Diag.w("cpu topology probe failed: ${it.message}") }
            .getOrNull()
        val cores = topo?.getOrNull(0)?.toInt()?.takeIf { it > 0 }
            ?: Runtime.getRuntime().availableProcessors()
        val dm = context.resources.displayMetrics
        return DeviceRenderProfile(
            physicalBytes = mem.totalMem,
            lowRamDevice = am?.isLowRamDevice == true,
            mediaPerformanceClass = perfClass,
            cpuCores = cores,
            bigCores = topo?.getOrNull(1)?.toInt() ?: 0,
            aggregateMaxKHz = topo?.getOrNull(3) ?: 0L,
            displayLongEdgePx = maxOf(dm.widthPixels, dm.heightPixels),
        )
    }
}
