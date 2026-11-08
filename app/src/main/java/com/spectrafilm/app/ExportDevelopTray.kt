/*
 * Spektrafilm for Android — the export "developer tray" animation. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * While an export runs, the photo is shown as a print developing in the tray under a red
 * safelight: blank paper first, then the image coming up shadows first, with a slow liquid
 * wobble. It opens with a 0.45 s shutter blink (an iris closing and reopening with a flash)
 * as the feedback for the Export tap.
 *
 * Honest progress: the engine reports no fraction inside film simulation, which is ~85% of
 * an export (ExportProgress.kt). So the develop level is driven by elapsed time, easing toward
 * [DEVELOP_CAP] and never reaching a finished print on its own; a stage start can only push it
 * forward. The finished state is the existing "Saved" hero, not this shader.
 *
 * API 33+ only (RuntimeShader + RenderEffect.createRuntimeShaderEffect). Below that, when
 * animations are off, when there is no preview, or if the shader fails to compile, the export
 * mask keeps its plain scrim and loading indicator.
 */

/** The develop level never passes this while exporting: the print finishes in the hero. */
internal const val DEVELOP_CAP = 0.9f

/** Time constant of the develop ease; a ~6 s release export reaches ~0.8 of the cap. */
internal const val DEVELOP_TAU_S = 2.5f

/** Length of the opening shutter blink. */
internal const val SHUTTER_S = 0.45f

/** Long edge of the tray's private copy of the preview. */
private const val TRAY_MAX_EDGE = 1024

/**
 * How far the print has developed after [elapsedSec], given the export's monotonic,
 * stage-weighted [stageFraction] (0 before the first stage report). Monotonic in both inputs.
 */
internal fun developLevel(elapsedSec: Float, stageFraction: Float): Float {
    val timed = DEVELOP_CAP * (1f - exp(-elapsedSec.coerceAtLeast(0f) / DEVELOP_TAU_S))
    val staged = DEVELOP_CAP * stageFraction.coerceIn(0f, 1f)
    return max(timed, staged).coerceIn(0f, DEVELOP_CAP)
}

/** 0..1 through the opening shutter blink, then held at 1 (iris fully open, no flash). */
internal fun shutterPhase(elapsedSec: Float): Float = (elapsedSec / SHUTTER_S).coerceIn(0f, 1f)

private const val DEVELOP_TRAY_AGSL = """
uniform shader image;
uniform float2 size;
uniform float develop;
uniform float shutter;
uniform float time;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

half4 main(float2 p) {
    float2 uv = p / size;
    // The tray rocking: a few pixels of slow liquid wobble.
    float2 q = p + float2(sin(uv.y * 10.0 + time * 3.0), sin(uv.x * 8.0 + time * 2.3)) * 3.0;
    half4 c = image.eval(q);
    float a = float(c.a);
    float3 rgb = a > 0.0 ? float3(c.rgb) / a : float3(0.0);

    // Shadows come up first, like a real print in developer; a little grain breaks the edge.
    float lum = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    float g = hash(floor(p / 2.0)) - 0.5;
    float e = smoothstep(lum - 0.15, lum + 0.05, develop * 1.2 - 0.1 + g * 0.04);
    float3 col = mix(float3(0.96, 0.95, 0.92), rgb, e);
    col *= float3(1.0, 0.42, 0.30); // red safelight

    // Shutter blink: a six-blade iris closes to a point and reopens, with a flash at the middle.
    float s = clamp(shutter, 0.0, 1.0);
    if (s < 1.0) {
        float2 d = (p - size * 0.5) / size.y;
        float seg = 1.04719755;
        float corner = length(float2(size.x / size.y, 1.0)) * 0.5;
        float aperture = (1.0 - sin(3.14159265 * s)) * corner / cos(seg * 0.5);
        float ang = atan(d.y, d.x) + s * 1.2;
        float poly = cos(seg * 0.5) / cos(mod(ang, seg) - seg * 0.5);
        col *= step(length(d), aperture * poly);
        col += 0.9 * exp(-(s - 0.5) * (s - 0.5) * 60.0);
    }
    col = clamp(col, 0.0, 1.0);
    return half4(half3(col * a), half(a));
}
"""

/** The tray's own copy of the photo and its compiled shader. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class DevelopTray(val photo: Bitmap, val shader: RuntimeShader)

private fun animationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/**
 * A downscaled ARGB_8888 copy of [source], taken under a preview read lease so a render that
 * retires the preview mid-copy cannot recycle it underneath us. The copy belongs to the tray
 * alone and is left to the GC, so no draw pass can ever see it recycled.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun trayCopyOf(source: Bitmap): Bitmap? = acquirePreviewLease(source)?.use { lease ->
    val src = lease.value
    if (src.isRecycled || src.width <= 0 || src.height <= 0) return@use null
    val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
    val scale = minOf(1f, TRAY_MAX_EDGE.toFloat() / maxOf(soft.width, soft.height))
    val w = (soft.width * scale).roundToInt().coerceAtLeast(1)
    val h = (soft.height * scale).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(soft, w, h, true)
    // createScaledBitmap may hand back its input; the tray must never share the preview.
    val owned = if (scaled === src) src.copy(Bitmap.Config.ARGB_8888, false) else scaled
    if (soft !== src && soft !== owned) soft.recycle()
    owned
}

/**
 * The tray for the photo on screen when the export started, or null when it cannot run
 * (below API 33, animations off, no preview, or a shader compile failure).
 */
@Composable
internal fun rememberDevelopTray(source: Bitmap?): DevelopTray? {
    val context = LocalContext.current
    return remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || source == null || !animationsOn(context)) {
            null
        } else {
            runCatching {
                trayCopyOf(source)?.let { DevelopTray(it, RuntimeShader(DEVELOP_TRAY_AGSL)) }
            }.onFailure { Diag.w("export develop tray unavailable: $it") }.getOrNull()
        }
    }
}

/** Draws [tray] fitted into [modifier]'s bounds, developing until it leaves composition. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun DevelopTrayImage(tray: DevelopTray, modifier: Modifier = Modifier) {
    val fraction by ExportWorkRuntime.progress.collectAsStateWithLifecycle()
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { elapsed = (it - start) / 1_000_000_000f }
    }
    val image = remember(tray) { tray.photo.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        // State is read inside the layer block, so each frame only re-runs the layer, never
        // recomposition. The effect is rebuilt per frame because it snapshots the uniforms.
        modifier = modifier.graphicsLayer {
            val t = elapsed
            tray.shader.setFloatUniform("size", size.width, size.height)
            tray.shader.setFloatUniform("develop", developLevel(t, fraction))
            tray.shader.setFloatUniform("shutter", shutterPhase(t))
            tray.shader.setFloatUniform("time", t)
            renderEffect = RenderEffect.createRuntimeShaderEffect(tray.shader, "image")
                .asComposeRenderEffect()
        },
    )
}
