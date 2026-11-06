package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePreviewTest {
    private val gib = 1_073_741_824L

    private fun profile(
        ramGib: Long = 6,
        lowRam: Boolean = false,
        perfClass: Int = 0,
        cores: Int = 8,
        bigCores: Int = 0,
        aggregateKHz: Long = 0,
        displayPx: Int = 2400,
    ) = DeviceRenderProfile(ramGib * gib, lowRam, perfClass, cores, bigCores, aggregateKHz, displayPx)

    @Test fun missingTelemetryKeepsTheOldFixedDefaults() {
        val e = autoPreviewEdges(DeviceRenderProfile(0, false, 0, 0, 0, 0, 0))
        assertEquals(PreviewTier.BASELINE, e.tier)
        assertEquals(640, e.previewEdge)
        assertEquals(DRAFT_RENDER_MAX_PX, e.draftEdge)
    }

    @Test fun ramPicksTheBaseTier() {
        assertEquals(PreviewTier.LOW, autoPreviewEdges(profile(ramGib = 4)).tier)
        assertEquals(PreviewTier.BASELINE, autoPreviewEdges(profile(ramGib = 6)).tier)
        assertEquals(PreviewTier.HIGH, autoPreviewEdges(profile(ramGib = 8)).tier)
        assertEquals(PreviewTier.MAX, autoPreviewEdges(profile(ramGib = 12)).tier)
    }

    @Test fun lowRamFlagForcesLowTier() {
        val e = autoPreviewEdges(profile(ramGib = 12, lowRam = true))
        assertEquals(PreviewTier.LOW, e.tier)
        assertEquals(512, e.previewEdge)
        assertEquals(256, e.draftEdge)
    }

    @Test fun weakCpuStepsDownOneTier() {
        assertEquals(PreviewTier.HIGH, autoPreviewEdges(profile(ramGib = 12, cores = 4)).tier)
        assertEquals(
            PreviewTier.BASELINE,
            autoPreviewEdges(profile(ramGib = 8, aggregateKHz = 8_000_000L)).tier,
        )
        // Never below LOW.
        assertEquals(PreviewTier.LOW, autoPreviewEdges(profile(ramGib = 3, cores = 4)).tier)
    }

    @Test fun strongCpuStepsUpButNotToMax() {
        assertEquals(PreviewTier.HIGH, autoPreviewEdges(profile(ramGib = 6, perfClass = 33)).tier)
        assertEquals(
            PreviewTier.BASELINE,
            autoPreviewEdges(profile(ramGib = 4, bigCores = 4, aggregateKHz = 25_000_000L)).tier,
        )
        // MAX is reachable from RAM only.
        assertEquals(PreviewTier.HIGH, autoPreviewEdges(profile(ramGib = 8, perfClass = 34)).tier)
    }

    @Test fun previewIsCappedBySmallScreens() {
        val e = autoPreviewEdges(profile(ramGib = 12, displayPx = 800))
        assertEquals(768, e.previewEdge)
        assertTrue(e.draftEdge <= e.previewEdge)
        assertEquals(PREVIEW_EDGE_MIN, autoPreviewEdges(profile(displayPx = 100)).previewEdge)
    }

    @Test fun edgesStayInsideTheSettingRanges() {
        for (ram in listOf(0L, 2L, 4L, 6L, 8L, 16L)) for (cores in listOf(0, 2, 8)) for (px in listOf(0, 300, 3000)) {
            val e = autoPreviewEdges(profile(ramGib = ram, cores = cores, displayPx = px))
            assertTrue(e.previewEdge in PREVIEW_EDGE_MIN..PREVIEW_EDGE_MAX)
            assertTrue(e.draftEdge in DRAFT_EDGE_MIN..DRAFT_EDGE_MAX)
            assertTrue(e.draftEdge <= e.previewEdge)
        }
    }

    @Test fun calibratorStepsDownOnASlowDraft() {
        val c = DraftEdgeCalibrator(384)
        assertEquals(320, c.record(384, 200))
        assertEquals(256, c.record(320, 200))
    }

    @Test fun calibratorNeedsAStreakToStepUp() {
        val c = DraftEdgeCalibrator(256)
        assertEquals(256, c.record(256, 10))
        assertEquals(256, c.record(256, 10))
        assertEquals(320, c.record(256, 10))
    }

    @Test fun middlingDraftResetsTheFastStreak() {
        val c = DraftEdgeCalibrator(256)
        c.record(256, 10)
        c.record(256, 10)
        c.record(256, 80) // between fast and slow
        assertEquals(256, c.record(256, 10))
    }

    @Test fun calibratorRespectsBounds() {
        val low = DraftEdgeCalibrator(128)
        assertEquals(128, low.record(128, 999))
        val high = DraftEdgeCalibrator(512, maxEdge = 512)
        repeat(6) { high.record(512, 1) }
        assertEquals(512, high.edge)
        assertEquals(448, DraftEdgeCalibrator(512, maxEdge = 448).edge)
    }

    @Test fun staleTimingForAnOldEdgeIsIgnored() {
        val c = DraftEdgeCalibrator(384)
        c.record(384, 500) // -> 320
        assertEquals(320, c.record(384, 500))
    }
}
