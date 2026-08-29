package dev.vertex.translate

data class TerrainRequirements(
    val entity: Boolean,
    val midTexCoord: Boolean,
    val midBlock: Boolean,
    val tangent: Boolean,
)

/** Determines the smallest terrain vertex contract needed by a preprocessed pack shader. */
object TerrainRequirementScanner {
    fun scan(vertexSource: String): TerrainRequirements {
        val attributes = ATTRIBUTE.findAll(vertexSource).map { it.groupValues[1] }.toSet()
        return TerrainRequirements(
            entity = "mc_Entity" in attributes,
            midTexCoord = "mc_midTexCoord" in attributes,
            midBlock = "at_midBlock" in attributes,
            tangent = attributes.any { it == "at_tangent" || it == "tangent" },
        )
    }

    private val ATTRIBUTE = Regex("""\battribute\s+\w+\s+(\w+)\s*;""")
}
