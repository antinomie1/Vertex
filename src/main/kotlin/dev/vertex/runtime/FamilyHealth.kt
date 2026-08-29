package dev.vertex.runtime

data class FamilyFailure(val family: ProgramFamily, val reason: String)

/** Session-local circuit breakers: one broken shader family cannot poison the others. */
class FamilyHealth(initial: Map<ProgramFamily, TierDecision>) {
    @Volatile private var decisions = initial.toMap()
    private val failures = mutableListOf<FamilyFailure>()

    fun tier(family: ProgramFamily) = decisions.getValue(family).tier
    fun snapshot(): Map<ProgramFamily, TierDecision> = decisions
    @Synchronized fun failures(): List<FamilyFailure> = failures.toList()

    @Synchronized fun disable(family: ProgramFamily, reason: String): Boolean {
        if (decisions.getValue(family).tier == RenderTier.TIER_0) return false
        decisions = decisions + (family to TierDecision(RenderTier.TIER_0, reason))
        failures += FamilyFailure(family, reason)
        return true
    }
}
