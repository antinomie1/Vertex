package dev.vertex.runtime

data class RenderScaleDecision(val scale: Float, val reason: String? = null)

object RenderScalePolicy {
    fun resolve(requested: Float, fragmentSources: Iterable<String>): RenderScaleDecision {
        require(requested in setOf(0.5f, 0.75f, 1f)) { "vertex.renderScale must be 0.5, 0.75, or 1.0" }
        val unsafe = fragmentSources.firstOrNull { source -> UNSAFE_CALLS.any(source::contains) }
        return if (requested < 1f && unsafe != null)
            RenderScaleDecision(1f, "pack uses ${UNSAFE_CALLS.first { it in unsafe }}") else RenderScaleDecision(requested)
    }

    private val UNSAFE_CALLS = listOf("texelFetch", "textureSize", "gl_FragCoord")
}
