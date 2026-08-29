package dev.vertex.translate

import kotlin.test.Test
import kotlin.test.assertEquals

class TerrainRequirementsTest {
    @Test fun `scans only declared legacy attributes`() {
        val source = """
            attribute vec4 mc_Entity;
            attribute vec4 mc_midTexCoord;
            attribute vec3 at_midBlock;
            varying vec3 tangent;
        """.trimIndent()
        assertEquals(TerrainRequirements(true, true, true, false), TerrainRequirementScanner.scan(source))
    }
}
