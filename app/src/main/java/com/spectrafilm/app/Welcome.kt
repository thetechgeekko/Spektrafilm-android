/*
 * Spektrafilm for Android — first-run intro. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Three pages, one idea and one looping animation each (the "simple intro" Akshay picked on
 * 2026-11-08 over a longer darkroom story):
 *   (1) Real film looks — the sample photo wipes between plain digital and a film look.
 *   (2) Tap a film      — a fingertip taps along a stock strip and the photo follows.
 *   (3) Save it         — a wavy progress line fills and a cookie-shaped tick pops.
 *
 * The sample photo is drawn in code (no bundled image) and the looks are colour matrices,
 * so the intro costs nothing at first launch and never waits on the engine. Shown once,
 * gated by AppSettings.seenOnboarding. Reduced motion freezes every loop on a still frame.
 */
package com.spectrafilm.app

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.launch

private data class IntroPage(@StringRes val title: Int, @StringRes val body: Int, val periodMs: Int)

private val INTRO_PAGES = listOf(
    IntroPage(R.string.screen_intro_look_title, R.string.screen_intro_look_body, 5600),
    IntroPage(R.string.screen_intro_tap_title, R.string.screen_intro_tap_body, 6400),
    IntroPage(R.string.screen_intro_save_title, R.string.screen_intro_save_body, 4200),
)

/**
 * Full-screen first-run intro. [onFinish] runs on Skip or Get started; the host marks
 * onboarding seen and dismisses this, leaving the user on the editor's Open-a-photo screen.
 */
@Composable
fun WelcomeFlow(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { INTRO_PAGES.size })
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val reduceMotion = remember(ctx) {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    val scene = remember { introSceneBitmap() }
    val last = pagerState.currentPage == INTRO_PAGES.lastIndex
    val pageIndicator = stringResource(
        R.string.screen_welcome_page_indicator, pagerState.currentPage + 1, INTRO_PAGES.size,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End) {
            if (!last) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.screen_welcome_skip)) }
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            IntroPageContent(
                page = INTRO_PAGES[page],
                index = page,
                scene = scene,
                animate = !reduceMotion && pagerState.currentPage == page,
            )
        }
        if (last) {
            Text(
                stringResource(R.string.help_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.screen_welcome_author_links),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).semantics { contentDescription = pageIndicator },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(INTRO_PAGES.size) { i ->
                    val active = pagerState.currentPage == i
                    val w by animateDpAsState(if (active) 22.dp else 6.dp, label = "dot")
                    Box(
                        Modifier
                            .size(width = w, height = 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
            Button(
                onClick = {
                    if (last) onFinish()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(if (last) R.string.screen_welcome_get_started else R.string.screen_welcome_next))
            }
        }
    }
}

@Composable
private fun IntroPageContent(page: IntroPage, index: Int, scene: ImageBitmap, animate: Boolean) {
    // One loop per page. A frozen phase is chosen so the still frame (reduced motion, or a
    // neighbouring page during a swipe) shows the idea: mid-wipe, a tile picked, saved.
    val still = when (index) { 0 -> 0.30f; 1 -> 0.30f; else -> 0.92f }
    val phase = if (animate) {
        rememberInfiniteTransition(label = "intro$index").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(page.periodMs, easing = LinearEasing)),
            label = "phase$index",
        ).value
    } else still
    val colors = IntroColors(
        accent = MaterialTheme.colorScheme.primary,
        onAccent = MaterialTheme.colorScheme.onPrimary,
        track = MaterialTheme.colorScheme.secondaryContainer,
        text = MaterialTheme.colorScheme.onSurface,
        textMuted = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val digital = stringResource(R.string.screen_intro_digital)
    val saved = stringResource(R.string.screen_intro_saved)
    val measurer = rememberTextMeasurer()

    // The art takes what is left after the words, so a landscape or split-screen window
    // still shows the title and body; a window too short for both scrolls.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = minOf(maxWidth, 360.dp, (maxHeight - 150.dp).coerceAtLeast(96.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(
                Modifier
                    .size(side)
                    // Decorative: the title and body below carry the meaning for TalkBack.
                    .clearAndSetSemantics { },
            ) {
                when (index) {
                    0 -> drawWipe(scene, phase, colors, measurer, digital)
                    1 -> drawTapAFilm(scene, phase, colors, measurer)
                    else -> drawSaveIt(scene, phase, colors, measurer, saved)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(page.title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(page.body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

private class IntroColors(
    val accent: Color,
    val onAccent: Color,
    val track: Color,
    val text: Color,
    val textMuted: Color,
)

/* ---------------------------------------------------------------- looks */

/** The film looks shown in the intro, as colour matrices (illustrative, not the engine). */
internal enum class IntroLook(val label: String, val matrix: ColorMatrix?) {
    DIGITAL("Digital", null),
    PORTRA("Portra 400", lookMatrix(saturation = 1.05f, contrast = 1.05f, r = 1.06f, g = 1.0f, b = 0.88f)),
    EKTAR("Ektar 100", lookMatrix(saturation = 1.6f, contrast = 1.18f)),
    VISION("500T", lookMatrix(saturation = 0.9f, contrast = 1.02f, r = 0.9f, g = 1.0f, b = 1.12f)),
    HP5("HP5", lookMatrix(saturation = 0f, contrast = 1.25f)),
}

/**
 * Saturation about Rec. 709 luma, then contrast about mid-grey, then per-channel gain.
 * Offsets are in 0..255 units, as android.graphics.ColorMatrix (and Compose's) expects.
 */
internal fun lookMatrix(
    saturation: Float,
    contrast: Float,
    r: Float = 1f,
    g: Float = 1f,
    b: Float = 1f,
): ColorMatrix {
    val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
    val s = saturation
    val sat = arrayOf(
        floatArrayOf(lr * (1 - s) + s, lg * (1 - s), lb * (1 - s)),
        floatArrayOf(lr * (1 - s), lg * (1 - s) + s, lb * (1 - s)),
        floatArrayOf(lr * (1 - s), lg * (1 - s), lb * (1 - s) + s),
    )
    val gains = floatArrayOf(r, g, b)
    val offset = (1 - contrast) * 127.5f
    val m = FloatArray(20)
    for (row in 0..2) {
        val k = contrast * gains[row]
        m[row * 5 + 0] = sat[row][0] * k
        m[row * 5 + 1] = sat[row][1] * k
        m[row * 5 + 2] = sat[row][2] * k
        m[row * 5 + 4] = offset * gains[row]
    }
    m[18] = 1f
    return ColorMatrix(m)
}

/* ---------------------------------------------------------------- the sample photo */

private const val SCENE_W = 480
private const val SCENE_H = 360

/** Draws the dusk lake once into a bitmap; every page then reuses it under a colour filter. */
private fun introSceneBitmap(): ImageBitmap {
    val bmp = ImageBitmap(SCENE_W, SCENE_H)
    CanvasDrawScope().draw(
        Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp),
        Size(SCENE_W.toFloat(), SCENE_H.toFloat()),
    ) { drawIntroScene() }
    return bmp
}

private fun DrawScope.drawIntroScene() {
    val w = size.width; val h = size.height
    drawRect(
        Brush.verticalGradient(
            0f to SpektraBrand.introSkyTop, 0.6f to SpektraBrand.introSkyMid, 1f to SpektraBrand.introSkyLow,
            endY = h * 0.62f,
        ),
    )
    drawCircle(SpektraBrand.introSun, radius = 20f, center = Offset(w * 0.68f, h * 0.46f))
    fun ridge(baseY: Float, step: Float, wave: (Float) -> Float, color: Color) {
        val p = Path().apply {
            moveTo(0f, baseY)
            var x = 0f
            while (x <= w) { lineTo(x, baseY - wave(x)); x += step }
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(p, color)
    }
    ridge(h * 0.55f, 12f, { x -> 26f * sin(x / 50f) + 12f * sin(x / 17f + 1f) }, SpektraBrand.introHillFar)
    ridge(h * 0.63f, 10f, { x -> 20f * sin(x / 70f + 2f) + 7f * sin(x / 12f) }, SpektraBrand.introHillNear)
    drawRect(
        Brush.verticalGradient(
            listOf(SpektraBrand.introLakeTop, SpektraBrand.introLakeLow), startY = h * 0.67f, endY = h,
        ),
        topLeft = Offset(0f, h * 0.67f), size = Size(w, h * 0.33f),
    )
    for (i in 0 until 18) {
        drawRect(
            SpektraBrand.introGlint,
            topLeft = Offset(w * 0.68f - 20f + sin(i.toFloat()) * 7f, h * 0.69f + i * 6f),
            size = Size(40f - i * 1.6f, 2.5f),
        )
    }
    for (i in 0 until 12) {
        val x = 20f + i * 20f + (i % 3) * 6f
        val th = 40f + (i * 37 % 32)
        drawPath(
            Path().apply { moveTo(x, h * 0.71f); lineTo(x + 9f, h * 0.71f - th); lineTo(x + 18f, h * 0.71f); close() },
            SpektraBrand.introTrees,
        )
    }
}

private fun DrawScope.photo(scene: ImageBitmap, look: IntroLook, topLeft: Offset, sz: Size, radius: Float, alpha: Float = 1f) {
    val clip = Path().apply { addRoundRect(RoundRect(topLeft.x, topLeft.y, topLeft.x + sz.width, topLeft.y + sz.height, CornerRadius(radius))) }
    clipPath(clip) {
        drawImage(
            scene,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(SCENE_W, SCENE_H),
            dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
            dstSize = IntSize(sz.width.toInt(), sz.height.toInt()),
            alpha = alpha,
            colorFilter = look.matrix?.let { ColorFilter.colorMatrix(it) },
        )
    }
}

private fun easeOut(t: Float): Float { val c = t.coerceIn(0f, 1f); return 1f - (1f - c).pow(3) }
private fun easeInOut(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return if (c < 0.5f) 4f * c * c * c else 1f - (-2f * c + 2f).pow(3) / 2f
}

private fun DrawScope.chip(measurer: TextMeasurer, text: String, x: Float, y: Float, color: Color, alignEnd: Boolean) {
    val layout = measurer.measure(text, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color))
    val padH = 10.dp.toPx(); val hgt = layout.size.height + 8.dp.toPx()
    val wid = layout.size.width + padH * 2
    val left = if (alignEnd) x - wid else x
    drawRoundRect(SpektraBrand.introChipScrim, Offset(left, y - hgt), Size(wid, hgt), CornerRadius(hgt / 2))
    drawText(layout, topLeft = Offset(left + padH, y - hgt + 4.dp.toPx()))
}

private fun DrawScope.fingertip(c: Offset, pressed: Boolean) {
    drawCircle(SpektraBrand.introTouchHalo, radius = (if (pressed) 20 else 24).dp.toPx(), center = c)
    drawCircle(SpektraBrand.introTouch, radius = (if (pressed) 10 else 12).dp.toPx(), center = c)
}

/* ---------------------------------------------------------------- page 1: wipe */

private fun DrawScope.drawWipe(scene: ImageBitmap, phase: Float, colors: IntroColors, measurer: TextMeasurer, digital: String) {
    val s = size.minDimension
    val x = s * 0.04f; val y = s * 0.14f; val w = s * 0.92f; val h = s * 0.72f; val r = s * 0.08f
    val k = 0.04f + 0.92f * easeInOut((sin(2f * PI.toFloat() * phase - 1.2f) + 1f) / 2f)
    photo(scene, IntroLook.DIGITAL, Offset(x, y), Size(w, h), r)
    clipRect(left = x, top = y, right = x + w * k, bottom = y + h) {
        photo(scene, IntroLook.PORTRA, Offset(x, y), Size(w, h), r)
    }
    val dx = x + w * k
    drawLine(SpektraBrand.introDivider, Offset(dx, y), Offset(dx, y + h), strokeWidth = 3.dp.toPx())
    drawCircle(SpektraBrand.introDivider, radius = 11.dp.toPx(), center = Offset(dx, y + h / 2))
    // The film look fills the left of the divider, so it is labelled on the left. The chips
    // sit on the photo, which is dark in either theme: fixed light text, not theme roles.
    chip(measurer, IntroLook.PORTRA.label, x + 10.dp.toPx(), y + h - 10.dp.toPx(), SpektraBrand.introChipAccent, alignEnd = false)
    chip(measurer, digital, x + w - 10.dp.toPx(), y + h - 10.dp.toPx(), SpektraBrand.introChipText, alignEnd = true)
}

/* ---------------------------------------------------------------- page 2: tap a film */

private val STRIP = listOf(IntroLook.PORTRA, IntroLook.EKTAR, IntroLook.VISION, IntroLook.HP5)

private fun DrawScope.drawTapAFilm(scene: ImageBitmap, phase: Float, colors: IntroColors, measurer: TextMeasurer) {
    val s = size.minDimension
    val cyc = phase * STRIP.size
    val i = floor(cyc).toInt().coerceIn(0, STRIP.lastIndex)
    val f = cyc - i
    val x = s * 0.04f; val y = s * 0.04f; val w = s * 0.92f; val h = s * 0.6f; val r = s * 0.07f
    photo(scene, STRIP[(i + STRIP.size - 1) % STRIP.size], Offset(x, y), Size(w, h), r)
    photo(scene, STRIP[i], Offset(x, y), Size(w, h), r, alpha = easeOut(f * 4f))
    val gap = s * 0.033f
    val tw = (w - gap * (STRIP.size - 1)) / STRIP.size
    val th = tw * 0.9f
    val ty = y + h + s * 0.06f
    STRIP.forEachIndexed { j, look ->
        val tx = x + j * (tw + gap)
        val on = j == i
        val pop = if (on) 1f + 0.06f * sin(f.times(4f).coerceIn(0f, 1f) * PI.toFloat()) else 1f
        scale(pop, pivot = Offset(tx + tw / 2, ty + th / 2)) {
            if (on) {
                val pad = 3.dp.toPx()
                drawRoundRect(
                    colors.accent, Offset(tx - pad, ty - pad), Size(tw + pad * 2, th + pad * 2),
                    CornerRadius(12.dp.toPx()), style = Stroke(width = 3.dp.toPx()),
                )
            }
            photo(scene, look, Offset(tx, ty), Size(tw, th), 10.dp.toPx())
        }
        val label = measurer.measure(
            look.label,
            TextStyle(fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) colors.accent else colors.textMuted),
        )
        drawText(label, topLeft = Offset(tx + tw / 2 - label.size.width / 2f, ty + th + 6.dp.toPx()))
    }
    fingertip(Offset(x + i * (tw + gap) + tw * 0.62f, ty + th * 0.66f), pressed = f < 0.2f)
}

/* ---------------------------------------------------------------- page 3: save it */

private fun DrawScope.drawSaveIt(scene: ImageBitmap, phase: Float, colors: IntroColors, measurer: TextMeasurer, saved: String) {
    val s = size.minDimension
    val x = s * 0.12f; val y = s * 0.06f; val w = s * 0.76f; val h = s * 0.57f
    photo(scene, IntroLook.PORTRA, Offset(x, y), Size(w, h), s * 0.07f)
    val k = (phase / 0.6f).coerceIn(0f, 1f)
    val bx = s * 0.12f; val bw = s * 0.76f; val by = s * 0.75f
    val stroke = 7.dp.toPx()
    val gapPx = 12.dp.toPx()
    if (bx + bw * k + gapPx < bx + bw) {
        drawLine(colors.track, Offset(bx + bw * k + gapPx, by), Offset(bx + bw, by), strokeWidth = stroke, cap = StrokeCap.Round)
    }
    if (k > 0f) {
        // Hand-drawn wavy indicator (the M3 Expressive wavy progress look); flattens when done.
        val amp = if (k < 1f) 3.5.dp.toPx() else 0f
        val wave = Path()
        var px = bx
        while (px <= bx + bw * k) {
            val py = by + sin(px / 9.dp.toPx() * 2f - phase * 40f) * amp
            if (px == bx) wave.moveTo(px, py) else wave.lineTo(px, py)
            px += 2f
        }
        drawPath(wave, colors.accent, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    if (k >= 1f) {
        val e = easeOut((phase - 0.6f) / 0.15f)
        val c = Offset(s * 0.5f, y + h / 2)
        val rad = s * 0.12f * e
        if (rad > 0f) {
            drawPath(cookie(c, rad, 9, 0.07f, phase * 2f), colors.accent)
            val check = Path().apply {
                moveTo(c.x - rad * 0.42f, c.y)
                lineTo(c.x - rad * 0.1f, c.y + rad * 0.33f)
                lineTo(c.x + rad * 0.46f, c.y - rad * 0.33f)
            }
            drawPath(check, colors.onAccent, style = Stroke(width = rad * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val label = measurer.measure(saved, TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.text))
        drawText(label, topLeft = Offset(s * 0.5f - label.size.width / 2f, s * 0.82f))
    }
}

/** A scalloped "cookie" outline (M3 Expressive shape family), drawn as a polar path. */
private fun cookie(c: Offset, radius: Float, lobes: Int, depth: Float, rotation: Float): Path = Path().apply {
    val n = 120
    for (step in 0..n) {
        val a = step.toFloat() / n * 2f * PI.toFloat()
        val rr = radius * (1f + depth * cos(lobes * (a + rotation)))
        val px = c.x + cos(a) * rr
        val py = c.y + sin(a) * rr
        if (step == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

