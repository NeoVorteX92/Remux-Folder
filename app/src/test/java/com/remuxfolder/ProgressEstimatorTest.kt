package com.remuxfolder

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressEstimatorTest {
    @Test fun calculatesPercentSpeedAndEtaFromStatistics() {
        val estimate = ProgressEstimator.estimate(30_000, 60.0, "2.0x")
        assertEquals(50, estimate.percent)
        assertEquals(15L, estimate.etaSeconds)
        assertEquals("2.0x", estimate.speed)
    }
}
