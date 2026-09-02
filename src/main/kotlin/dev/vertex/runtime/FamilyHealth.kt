package dev.vertex.runtime

data class FamilyFailure(val family: ProgramFamily, val reason: String)

/** Session-local circuit breakers: one broken shader family cannot poison the others. */
class FamilyHealth(initial: Map<ProgramFamily, TierDecision>) {
    private val baseline = initial.toMap()
    @Volatile private var decisions = baseline
    private val failures = mutableListOf<FamilyFailure>()

    fun tier(family: ProgramFamily) = decisions.getValue(family).tier
    fun snapshot(): Map<ProgramFamily, TierDecision> = decisions
    @Synchronized fun failures(): List<FamilyFailure> = failures.toList()

    /** Starts a fresh shader-pack epoch without re-probing immutable device capabilities. */
    @Synchronized fun reset() {
        decisions = baseline
        failures.clear()
    }

    @Synchronized fun disable(family: ProgramFamily, reason: String): Boolean {
        if (decisions.getValue(family).tier == RenderTier.TIER_0) return false
        decisions = decisions + (family to TierDecision(RenderTier.TIER_0, reason))
        failures += FamilyFailure(family, reason)
        return true
    }

    /** Keep the game-owned path available when an optional Tier 2 bridge is absent. */
    @Synchronized
    fun downgrade(family: ProgramFamily, tier: RenderTier, reason: String): Boolean {
        val current = decisions.getValue(family)
        if (current.tier <= tier) return false
        decisions = decisions + (family to TierDecision(tier, reason))
        failures += FamilyFailure(family, reason)
        return true
    }
}
