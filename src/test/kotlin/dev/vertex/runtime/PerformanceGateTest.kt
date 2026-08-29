package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PerformanceGateTest {
    @Test fun `rejects a regression over three percent on either percentile`() {
        val baseline = PerformanceBaseline(1_000, 2_000)
        assertNull(PerformanceGate.compare(baseline, Percentiles(600, 1_030, 2_060)))
        assertNotNull(PerformanceGate.compare(baseline, Percentiles(600, 1_031, 2_000)))
        assertNotNull(PerformanceGate.compare(baseline, Percentiles(600, 900, 2_061)))
    }

    @Test fun `baseline has a compact stable representation`() {
        val baseline = PerformanceBaseline(123, 456)
        assertEquals(baseline, PerformanceBaseline.decode(baseline.encode()))
        assertNull(PerformanceBaseline.decode("broken"))
    }
}
