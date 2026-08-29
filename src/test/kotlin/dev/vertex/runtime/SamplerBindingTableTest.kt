package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SamplerBindingTableTest {
    @Test
    fun `canonical aliases and extended color targets are deterministic`() {
        assertEquals(0, SamplerBindingTable.slot("tex"))
        assertEquals(0, SamplerBindingTable.slot("gtexture"))
        assertEquals(6, SamplerBindingTable.slot("depthtex0"))
        assertEquals(16, SamplerBindingTable.slot("colortex8"))
        assertEquals(23, SamplerBindingTable.slot("colortex15"))
    }

    @Test
    fun `reflection mismatch fails loudly`() {
        val plan = SamplerBindingTable.plan(listOf("colortex0", "depthtex0"))
        assertFailsWith<IllegalArgumentException> {
            SamplerBindingTable.validate(plan, mapOf("colortex0" to 8, "depthtex0" to 5))
        }
    }
}
