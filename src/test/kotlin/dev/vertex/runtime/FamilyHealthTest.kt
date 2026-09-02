package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FamilyHealthTest {
    @Test fun `failure disables only its family and is recorded once`() {
        val initial = ProgramFamily.entries.associateWith { TierDecision(RenderTier.TIER_2, "ready") }
        val health = FamilyHealth(initial)

        assertTrue(health.disable(ProgramFamily.SCREEN_CHAIN, "bad composite"))
        assertFalse(health.disable(ProgramFamily.SCREEN_CHAIN, "again"))
        assertEquals(RenderTier.TIER_0, health.tier(ProgramFamily.SCREEN_CHAIN))
        assertEquals(RenderTier.TIER_2, health.tier(ProgramFamily.TERRAIN_OPAQUE))
        assertEquals(listOf(FamilyFailure(ProgramFamily.SCREEN_CHAIN, "bad composite")), health.failures())
    }

    @Test fun `new pack epoch restores negotiated tiers`() {
        val initial = ProgramFamily.entries.associateWith { TierDecision(RenderTier.TIER_2, "ready") }
        val health = FamilyHealth(initial)
        health.disable(ProgramFamily.SCREEN_CHAIN, "bad pack")
        health.downgrade(ProgramFamily.DYNAMIC_WORLD, RenderTier.TIER_1, "unsupported program")

        health.reset()

        assertEquals(RenderTier.TIER_2, health.tier(ProgramFamily.SCREEN_CHAIN))
        assertEquals(RenderTier.TIER_2, health.tier(ProgramFamily.DYNAMIC_WORLD))
        assertTrue(health.failures().isEmpty())
    }
}
