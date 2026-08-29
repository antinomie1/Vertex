package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryBudgetGovernorTest {
    @Test
    fun `degrades shadows before oversized noncritical targets`() {
        val request = listOf(
            ImageAllocation("scene", 100, 100, 4, ImageClass.CRITICAL),
            ImageAllocation("shadowtex0", 400, 400, 4, ImageClass.SHADOW),
            ImageAllocation("colortex7", 400, 400, 4, ImageClass.NON_CRITICAL),
        )
        val plan = MemoryBudgetGovernor.plan(request, 300_000, 100, 100)
        assertEquals(200, plan.allocations[1].width)
        assertTrue(plan.allocations[2].width in 140..142)
        assertEquals(2, plan.degradations.size)
    }

    @Test
    fun `refuses to silently shrink critical targets`() {
        assertFailsWith<IllegalArgumentException> {
            MemoryBudgetGovernor.plan(
                listOf(ImageAllocation("scene", 100, 100, 4, ImageClass.CRITICAL)),
                1_000, 100, 100,
            )
        }
    }

    @Test
    fun `repeatedly reduces pathological shadow maps before rejecting the pack`() {
        val plan = MemoryBudgetGovernor.plan(
            listOf(ImageAllocation("shadowtex0", 8192, 8192, 4, ImageClass.SHADOW)),
            16L * 1024 * 1024, 854, 480,
        )
        assertEquals(2048, plan.allocations.single().width)
        assertEquals(2, plan.degradations.size)
    }
}
