package dev.vertex.runtime

/** The three execution levels defined by DESIGN.md section 2. */
enum class RenderTier { TIER_0, TIER_1, TIER_2 }

enum class ProgramFamily {
    TERRAIN_OPAQUE,
    TERRAIN_WATER,
    DYNAMIC_WORLD,
    HAND,
    SKY_WEATHER,
    EXTERNAL_WORLD,
    SCREEN_CHAIN,
}

data class DeviceCapabilities(
    val deviceHookAvailable: Boolean,
    val descriptorIndexing: Boolean,
    val updateAfterBind: Boolean,
    val timelineSemaphore: Boolean,
    val multiDrawIndirect: Boolean,
    val multiDrawIndirectCount: Boolean,
    val dynamicRendering: Boolean,
    val synchronization2: Boolean,
) {
    val supportsTier2: Boolean
        get() = deviceHookAvailable && descriptorIndexing && updateAfterBind &&
            timelineSemaphore && multiDrawIndirect && multiDrawIndirectCount &&
            dynamicRendering && synchronization2
}

data class CompatibilityProbe(
    val sodiumTerrainConflict: Boolean = false,
    val translucentSortingConflict: Boolean = false,
    val dynamicCaptureAvailable: Boolean = true,
    val externalWorldRendererPresent: Boolean = false,
)

data class TierDecision(val tier: RenderTier, val reason: String)

/** Pure, deterministic negotiation so startup failure can always fall back safely. */
object TierNegotiator {
    fun negotiate(
        capabilities: DeviceCapabilities,
        compatibility: CompatibilityProbe = CompatibilityProbe(),
        implementedTier2: Set<ProgramFamily> = ProgramFamily.entries.toSet(),
    ): Map<ProgramFamily, TierDecision> {
        if (!capabilities.supportsTier2) {
            val reason = missingCapabilities(capabilities).joinToString(", ")
            return ProgramFamily.entries.associateWith {
                TierDecision(RenderTier.TIER_0, "missing required capability: $reason")
            }
        }

        fun full(reason: String = "all required capabilities available") =
            TierDecision(RenderTier.TIER_2, reason)
        fun game(reason: String) = TierDecision(RenderTier.TIER_1, reason)

        val decisions = mapOf(
            ProgramFamily.TERRAIN_OPAQUE to if (compatibility.sodiumTerrainConflict)
                game("terrain renderer conflict") else full(),
            ProgramFamily.TERRAIN_WATER to if (compatibility.translucentSortingConflict)
                game("translucent sorting conflict") else full(),
            ProgramFamily.DYNAMIC_WORLD to if (!compatibility.dynamicCaptureAvailable)
                game("dynamic BufferSource capture unavailable") else full(),
            ProgramFamily.HAND to full(),
            ProgramFamily.SKY_WEATHER to full(),
            ProgramFamily.EXTERNAL_WORLD to if (compatibility.externalWorldRendererPresent)
                game("external world renderer must retain its own draw path") else full(),
            ProgramFamily.SCREEN_CHAIN to full(),
        )
        return decisions.mapValues { (family, decision) ->
            if (family !in implementedTier2 && decision.tier == RenderTier.TIER_2)
                game("Tier 2 route is not implemented yet") else decision
        }
    }

    private fun missingCapabilities(c: DeviceCapabilities): List<String> = buildList {
        if (!c.deviceHookAvailable) add("device hook")
        if (!c.descriptorIndexing) add("descriptor indexing")
        if (!c.updateAfterBind) add("update-after-bind")
        if (!c.timelineSemaphore) add("timeline semaphore")
        if (!c.multiDrawIndirect) add("multi-draw indirect")
        if (!c.multiDrawIndirectCount) add("multi-draw indirect count")
        if (!c.dynamicRendering) add("dynamic rendering")
        if (!c.synchronization2) add("synchronization2")
    }
}
