package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeCompatibilityTest {
    private val classes: (String) -> Boolean = { true }

    @Test
    fun `accepts supported release and snapshot versions`() {
        assertTrue(RuntimeCompatibility.check("26.2", 25, classes).compatible)
        assertTrue(RuntimeCompatibility.check("26.3-snapshot-9", 25, classes).compatible)
    }

    @Test
    fun `rejects unsupported runtime without throwing`() {
        val report = RuntimeCompatibility.check("26.1", 24, classes)
        assertFalse(report.compatible)
        assertTrue(report.reasons.any { "below 26.2" in it })
        assertTrue(report.reasons.any { "below 25" in it })
    }

    @Test
    fun `reports missing implementation classes`() {
        val report = RuntimeCompatibility.check("26.2", 25) { it.endsWith("api.device.GpuDevice") }
        assertFalse(report.compatible)
        assertTrue(report.reasons.any { "FrontendGpuDevice" in it })
    }
}
