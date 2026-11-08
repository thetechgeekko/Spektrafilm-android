package com.spectrafilm.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** The drag mapping and drawing maths shared by the instrument controls. */
class InstrumentMathTest {
    private val range = 0f..4f

    @Test
    fun fullTravelSweepsTheWholeRange() {
        assertEquals(4f, instrumentDragValue(0f, 180f, 180f, range, fine = false), 1e-6f)
        assertEquals(2f, instrumentDragValue(0f, 90f, 180f, range, fine = false), 1e-6f)
    }

    @Test
    fun aSecondFingerSlowsTheDragToAQuarter() {
        assertEquals(1f, instrumentDragValue(0f, 180f, 180f, range, fine = true), 1e-6f)
    }

    @Test
    fun theValueStaysInsideTheRange() {
        assertEquals(4f, instrumentDragValue(3f, 1000f, 180f, range, fine = false), 1e-6f)
        assertEquals(0f, instrumentDragValue(1f, -1000f, 180f, range, fine = false), 1e-6f)
        // A degenerate travel never divides by zero.
        assertEquals(4f, instrumentDragValue(9f, 10f, 0f, range, fine = false), 1e-6f)
    }

    @Test
    fun fractionIsPositionInTheRange() {
        assertEquals(0.5f, instrumentFraction(0f, -100f..100f), 1e-6f)
        assertEquals(1f, instrumentFraction(200f, -100f..100f), 1e-6f)
        assertEquals(0f, instrumentFraction(1f, 1f..1f), 1e-6f)
    }

    @Test
    fun gradientInterpolatesBetweenEvenStops() {
        val stops = listOf(Color.Black, Color.White, Color.Red)
        assertEquals(Color.Black, gradientAt(stops, 0f))
        assertEquals(Color.White, gradientAt(stops, 0.5f))
        assertEquals(Color.Red, gradientAt(stops, 1f))
        assertEquals(Color.Red, gradientAt(stops, 2f))
    }

    @Test
    fun stepWaveMovesOneStopPerStopWidthAndClamps() {
        assertEquals(2, stepWaveIndex(2, 30f, 36f, 6))
        assertEquals(3, stepWaveIndex(2, 36f, 36f, 6))
        assertEquals(1, stepWaveIndex(2, -40f, 36f, 6))
        assertEquals(5, stepWaveIndex(2, 1000f, 36f, 6))
        assertEquals(0, stepWaveIndex(2, -1000f, 36f, 6))
        assertEquals(0, stepWaveIndex(0, 50f, 36f, 0))
    }

    @Test
    fun onlyTheDialAndColumnKindsDragVertically() {
        assertEquals(
            setOf(
                InstrumentKind.KNOB, InstrumentKind.FADER, InstrumentKind.PILL, InstrumentKind.ARC_GAUGE,
                InstrumentKind.FILL_KNOB,
            ),
            InstrumentKind.entries.filter { it.vertical }.toSet(),
        )
    }

    @Test
    fun iconSliderStopsSpanTheTrackEvenly() {
        assertEquals(10f, iconSliderStopX(0, 5, 10f, 110f), 1e-4f)
        assertEquals(60f, iconSliderStopX(2, 5, 10f, 110f), 1e-4f)
        assertEquals(110f, iconSliderStopX(4, 5, 10f, 110f), 1e-4f)
        // One option sits in the middle rather than dividing by zero.
        assertEquals(60f, iconSliderStopX(0, 1, 10f, 110f), 1e-4f)
    }

    @Test
    fun aTapPicksTheNearestStopAndClamps() {
        // 120 wide with 10 inset: stops at 10, 35, 60, 85, 110.
        assertEquals(0, iconSliderIndexAt(0f, 120f, 10f, 5))
        assertEquals(1, iconSliderIndexAt(40f, 120f, 10f, 5))
        assertEquals(2, iconSliderIndexAt(60f, 120f, 10f, 5))
        assertEquals(4, iconSliderIndexAt(200f, 120f, 10f, 5))
        assertEquals(0, iconSliderIndexAt(60f, 120f, 10f, 1))
        assertEquals(0, iconSliderIndexAt(60f, 10f, 10f, 5))
    }

    @Test
    fun aValueOffTheListShowsTheNearestOption() {
        val sizes = listOf(256, 384, 512, 640, 768, 1024)
        assertEquals(3, nearestOptionIndex(sizes, 640))
        assertEquals(3, nearestOptionIndex(sizes, 600))
        assertEquals(0, nearestOptionIndex(sizes, 128))
        assertEquals(5, nearestOptionIndex(sizes, 900))
        assertEquals(0, nearestOptionIndex(emptyList(), 640))
    }
}
