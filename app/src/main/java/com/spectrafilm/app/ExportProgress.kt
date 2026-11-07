/*
 * Spektrafilm for Android — honest, stage-weighted export progress (#466). GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

/**
 * The export's known stages, in order, each weighted by its share of a measured RELEASE
 * export (docs/research/perf-lab.md §19.1, 24 MP RAW on the test phone: total 6251 ms,
 * simulate 5504, decode 546, grade 30, the rest ~171 of which encode is most).
 *
 * The engine reports no fraction inside a stage, so progress only moves when a stage
 * STARTS, to the summed weight of the stages before it. That is the honest resolution: the
 * bar never claims work it cannot see, and it never moves backwards. Simulate dominates by
 * design, because it dominates the real time.
 */
internal enum class ExportStage(val weight: Float) {
    SETUP(0.01f),
    DECODE(0.09f),
    EXIF(0.005f),
    SIMULATE(0.855f),
    GRADE(0.005f),
    ENCODE(0.035f),
    ;

    /** Fraction of the whole export done when this stage begins. */
    val startFraction: Float
        get() = entries.takeWhile { it != this }.sumOf { it.weight.toDouble() }.toFloat()
}

/** Monotonic merge: a late or repeated stage report can never pull progress back. */
internal fun mergeExportProgress(current: Float, stage: ExportStage): Float =
    maxOf(current, stage.startFraction).coerceIn(0f, 1f)
