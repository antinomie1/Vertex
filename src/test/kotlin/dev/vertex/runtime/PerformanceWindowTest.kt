package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceWindowTest {
    @Test
    fun `reports stable percentiles and evicts oldest samples`() {
        val window = PerformanceWindow(4)
        listOf(40L, 10L, 30L, 20L, 50L).forEach(window::record)
        val result = window.snapshot()
        assertEquals(4, result.samples)
        assertEquals(20, result.p50Micros)
        assertEquals(50, result.p99Micros)
    }
}
