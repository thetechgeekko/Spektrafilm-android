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
    fun onlyKnobFaderPillAndArcGaugeDragVertically() {
        assertEquals(
            setOf(InstrumentKind.KNOB, InstrumentKind.FADER, InstrumentKind.PILL, InstrumentKind.ARC_GAUGE),
            InstrumentKind.entries.filter { it.vertical }.toSet(),
        )
    }
}
