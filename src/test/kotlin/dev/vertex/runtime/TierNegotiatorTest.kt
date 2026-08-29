package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierNegotiatorTest {
    private val full = DeviceCapabilities(true, true, true, true, true, true, true, true)

    @Test
    fun `missing injection capability forces every family to tier zero`() {
        val decisions = TierNegotiator.negotiate(full.copy(deviceHookAvailable = false))
        assertTrue(decisions.values.all { it.tier == RenderTier.TIER_0 })
        assertTrue(decisions.values.all { "device hook" in it.reason })
    }

    @Test
    fun `conflicts downgrade only their affected families`() {
        val decisions = TierNegotiator.negotiate(
            full,
            CompatibilityProbe(sodiumTerrainConflict = true, dynamicCaptureAvailable = false),
        )
        assertEquals(RenderTier.TIER_1, decisions.getValue(ProgramFamily.TERRAIN_OPAQUE).tier)
        assertEquals(RenderTier.TIER_1, decisions.getValue(ProgramFamily.DYNAMIC_WORLD).tier)
        assertEquals(RenderTier.TIER_2, decisions.getValue(ProgramFamily.HAND).tier)
        assertEquals(RenderTier.TIER_2, decisions.getValue(ProgramFamily.SCREEN_CHAIN).tier)
    }

    @Test
    fun `capable but unimplemented families remain on the game path`() {
        val decisions = TierNegotiator.negotiate(
            full,
            implementedTier2 = setOf(ProgramFamily.TERRAIN_OPAQUE, ProgramFamily.SCREEN_CHAIN),
        )
        assertEquals(RenderTier.TIER_2, decisions.getValue(ProgramFamily.TERRAIN_OPAQUE).tier)
        assertEquals(RenderTier.TIER_1, decisions.getValue(ProgramFamily.HAND).tier)
    }

    @Test
    fun `runtime compatibility failure is a safe tier zero fallback`() {
        val decisions = TierNegotiator.negotiate(full.copy(runtimeCompatible = false))
        assertTrue(decisions.values.all { it.tier == RenderTier.TIER_0 })
        assertTrue(decisions.values.all { "runtime compatibility" in it.reason })
    }
}
