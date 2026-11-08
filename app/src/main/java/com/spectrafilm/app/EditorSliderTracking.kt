/*
 * Spektrafilm for Android — editor slider drag tracking, the moving-slider readout and the
 * sideways photo scrub. GPLv3. Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273). Every holder is remembered once, so identities
 * stay stable across recompositions exactly as before.
 */
package com.spectrafilm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

internal class EditorSliderTracking(
    val interacting: MutableState<Boolean>,
    val sliderReadout: MutableState<SliderReadout?>,
    val lastControlAlive: MutableState<State<Boolean>?>,
    val scrubArmed: MutableState<Boolean>,
    val sliderInteraction: SliderInteraction,
    val photoScrub: PhotoScrub,
)

@Composable
internal fun rememberEditorSliderTracking(viewerVm: EditorViewerViewModel): EditorSliderTracking {
    // Slider drag tracking (Lightroom's ICBSliderTrackingBegin/End): true while a slider is
    // being dragged, so the live DRAFT pass runs only during a drag and a discrete edit
    // (switch/dropdown) goes straight to the crisp settle render — no draft flicker, and the
    // continuous-draft cost is bounded to actual drags. Remembered so its identity is stable
    // across recompositions; provided to sliders via LocalSliderInteraction below.
    val interacting = remember { mutableStateOf(false) }
    // What the moving slider currently reads. Held as State and handed to the readout by
    // REFERENCE, never by value: a drag writes this every frame, and reading `.value` here
    // in EditorScreen would recompose that entire composable at 60 Hz while the
    // engine is already running a draft render on the same cores.
    val sliderReadout = remember { mutableStateOf<SliderReadout?>(null) }
    // The last control a drag reported, which a sideways drag on the photo keeps adjusting
    // (PhotoScrub). A plain holder, not State, for the same reason as sliderReadout: every
    // drag frame writes it. What is observed is the control's own liveness State, which only
    // changes hands when another control is dragged.
    val lastControl = remember { arrayOfNulls<SliderReadout>(1) }
    val lastControlAlive = remember { mutableStateOf<State<Boolean>?>(null) }
    // The photo drag is ARMED only for a moment after a control was used (PHOTO_SCRUB_ARM_MS
    // after the finger last lifted from it, or from a photo drag). Unarmed, a sideways drag on
    // the photo is the swipe to the next photo again. Without this, once any control had been
    // touched every later swipe meant for the next photo changed that control instead: a
    // half-screen flick swung Exposure from 0 to -7.5 EV on the phone. [scrubArmToken] restarts
    // the clock; it changes on release only, never per drag frame.
    val scrubArmed = remember { mutableStateOf(false) }
    val scrubArmToken = remember { mutableLongStateOf(0L) }
    LaunchedEffect(scrubArmToken.longValue) {
        if (scrubArmToken.longValue == 0L) return@LaunchedEffect
        delay(PHOTO_SCRUB_ARM_MS)
        // Never disarm under a finger: that would drop the photo drag mid-gesture.
        while (interacting.value) delay(100)
        scrubArmed.value = false
    }
    val sliderInteraction = remember {
        SliderInteraction(
            onChange = { readout ->
                interacting.value = true
                sliderReadout.value = readout
                val edit = readout?.edit
                if (edit?.move != null && edit.alive != null) {
                    lastControl[0] = readout
                    if (lastControlAlive.value !== edit.alive) lastControlAlive.value = edit.alive
                    scrubArmed.value = true
                }
            },
            onFinished = {
                interacting.value = false
                scrubArmToken.longValue += 1
            },
            // A second finger on the slider row peeks at clipping (the canvas button
            // latches the same overlay without a gesture).
            onMultiTouch = { down -> viewerVm.onAction(EditorViewerAction.SetClipPeek(down)) },
        )
    }
    // Sideways drag on the photo adjusts the last control: the HUD shows it as on the control
    // itself, and the release settles one history step.
    val photoScrub = remember {
        var target: SliderReadout? = null
        var startValue = 0f
        var lastValue: Float? = null
        PhotoScrub(
            start = {
                val r = lastControl[0]
                val v = r?.edit?.live?.invoke()
                if (r == null || v == null) {
                    false
                } else {
                    target = r
                    startValue = v
                    lastValue = null
                    true
                }
            },
            drag = { dx, widthPx ->
                val r = target
                val e = r?.edit
                if (r != null && e != null) {
                    val v = scrubValue(startValue, dx, widthPx, e.range, e.step)
                    if (v != lastValue) {
                        lastValue = v
                        e.move?.invoke(v)
                    }
                    interacting.value = true
                    // The same edit, re-based on the scrubbed value, so the lingering HUD can
                    // still be tapped to type an exact value (Codex on #545).
                    sliderReadout.value = SliderReadout(r.label, formatValue(v, e.decimals), e.rebased(v))
                }
            },
            end = {
                val e = target?.edit
                val v = lastValue
                if (e != null && v != null) e.set(v)
                target = null
                interacting.value = false
                scrubArmToken.longValue += 1
            },
        )
    }
    return remember {
        EditorSliderTracking(
            interacting = interacting,
            sliderReadout = sliderReadout,
            lastControlAlive = lastControlAlive,
            scrubArmed = scrubArmed,
            sliderInteraction = sliderInteraction,
            photoScrub = photoScrub,
        )
    }
}
