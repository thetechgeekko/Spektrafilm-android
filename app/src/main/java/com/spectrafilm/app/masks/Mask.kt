/*
 * Spektrafilm for Android — local-adjustment mask data model. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The foundation of the masking keystone (forum pain #2: "no way to limit a change to one area").
 * Pure Kotlin, JVM-testable, ZERO engine/parity impact — masks are rasterized to an alpha buffer and
 * composited on the engine's OUTPUT (the `simResultToBitmap` seam) in a later increment, so the film
 * render itself is untouched.
 *
 * Geometry is stored NORMALIZED (0..1 in image space) so one mask drives the 640px draft, the zoom
 * ROI, and the full-res export identically (resolution-independent). A [Mask] is a list of shape
 * [MaskComponent]s folded by [BlendMode] (darktable's proven identities), then optionally inverted and
 * scaled by opacity. A [LocalAdjustment] pairs a mask with the [TierADelta] payload to apply where the
 * mask is opaque. A global edit is the degenerate case: an empty, inverted mask → alpha ≡ 1.
 */
package com.spectrafilm.app.masks

/** How a component combines with the accumulated mask alpha (darktable blend identities). */
enum class BlendMode { ADD, SUBTRACT, INTERSECT }

/** A shape generator evaluated at a normalized image coordinate → coverage alpha in [0,1]. */
sealed interface MaskComponent {
    fun alphaAt(nx: Float, ny: Float): Float

    /**
     * Linear gradient: alpha ramps 0 → 1 along the direction from ([x0],[y0]) to ([x1],[y1]) (the two
     * points the user drags), flat (0) before the start line and flat (1) past the end line. A smooth
     * (smoothstep) transition in between. All coordinates normalized 0..1.
     */
    data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : MaskComponent {
        override fun alphaAt(nx: Float, ny: Float): Float {
            val dx = x1 - x0; val dy = y1 - y0
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-9f) return 0f
            // projection of (nx,ny) onto the gradient axis, as a fraction of its length
            val t = ((nx - x0) * dx + (ny - y0) * dy) / len2
            return smoothstep(t)
        }
    }

    /**
     * Radial: a feathered ellipse centered at ([cx],[cy]) with normalized radii ([rx],[ry]). Alpha is
     * 1 inside the core, smoothly falling to 0 at the boundary; [feather] in (0,1] sets the fraction of
     * the radius over which it fades (1 = fade from the very center, →0 = a hard edge).
     */
    data class Radial(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val feather: Float = 0.5f, val angleDeg: Float = 0f,
    ) : MaskComponent {
        override fun alphaAt(nx: Float, ny: Float): Float {
            var dx = nx - cx
            var dy = ny - cy
            if (angleDeg != 0f) {
                // rotate the sample by −angle so the ellipse axes align (matches LR's radial Angle)
                val r = -angleDeg * (Math.PI.toFloat() / 180f)
                val cs = kotlin.math.cos(r); val sn = kotlin.math.sin(r)
                val rx2 = dx * cs - dy * sn; val ry2 = dx * sn + dy * cs
                dx = rx2; dy = ry2
            }
            val ex = if (rx > 1e-6f) dx / rx else 0f
            val ey = if (ry > 1e-6f) dy / ry else 0f
            val d = kotlin.math.sqrt(ex * ex + ey * ey)   // 0 at center, 1 at the ellipse boundary
            val f = feather.coerceIn(1e-3f, 1f)
            // full coverage until (1 - feather), then ramp down to 0 at d = 1
            val t = (1f - d) / f
            return smoothstep(t)
        }
    }
}

/**
 * A mask: ordered [components] (each with its [BlendMode]) folded into one alpha field, then
 * [invert]ed and scaled by [opacity]. No components → selects nothing (alpha 0); invert that for a
 * global (alpha ≡ opacity) selection.
 */
data class Mask(
    val components: List<Component> = emptyList(),
    val invert: Boolean = false,
    val opacity: Float = 1f,
) {
    /**
     * One folded component. [invert] = `crs:MaskInverted` (per-component, applied BEFORE the fold);
     * [value] = `crs:MaskValue` (per-component strength 0..1).
     */
    data class Component(
        val mode: BlendMode,
        val shape: MaskComponent,
        val invert: Boolean = false,
        val value: Float = 1f,
    )

    /** Fold the components at a normalized coordinate → final alpha in [0,1]. */
    fun alphaAt(nx: Float, ny: Float): Float {
        var a = 0f
        for (comp in components) {
            var c = comp.shape.alphaAt(nx, ny).coerceIn(0f, 1f)
            if (comp.invert) c = 1f - c                 // crs:MaskInverted (per-component)
            if (comp.value != 1f) c *= comp.value       // crs:MaskValue (per-component strength)
            a = when (comp.mode) {
                BlendMode.ADD -> a + c - a * c          // union: 1-(1-a)(1-c)
                BlendMode.INTERSECT -> a * c
                BlendMode.SUBTRACT -> a * (1f - c)
            }
        }
        if (invert) a = 1f - a
        return (a * opacity).coerceIn(0f, 1f)
    }
}

/**
 * The per-mask adjustment payload (Tier-A: parameters applied as a post-engine op on the output,
 * blended by the mask alpha). All default to no-op. Exposure in stops; temp/tint/saturation/contrast
 * are the same [-100,100] relative scales as the global creative controls, so the UI + math are shared.
 */
data class TierADelta(
    val exposureEv: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val contrast: Float = 0f,
) {
    val isNoOp: Boolean
        get() = exposureEv == 0f && temp == 0f && tint == 0f && saturation == 0f && contrast == 0f
}

/** A mask plus the adjustment to apply where it is opaque. */
data class LocalAdjustment(val mask: Mask, val delta: TierADelta)

/** Smooth Hermite ramp clamped to [0,1] (0 for t≤0, 1 for t≥1). */
internal fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
