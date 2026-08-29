package dev.vertex.runtime

data class RenderScaleDecision(val scale: Float, val reason: String? = null)

object RenderScalePolicy {
    fun resolve(requested: Float, fragmentSources: Iterable<String>): RenderScaleDecision {
        require(requested in setOf(0.5f, 0.75f, 1f)) { "vertex.renderScale must be 0.5, 0.75, or 1.0" }
        return if (requested < 1f && fragmentSources.any { "texelFetch" in it })
            RenderScaleDecision(1f, "pack uses texelFetch") else RenderScaleDecision(requested)
    }
}
