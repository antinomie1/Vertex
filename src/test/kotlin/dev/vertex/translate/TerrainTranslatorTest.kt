package dev.vertex.translate

import dev.vertex.frontend.LoadedProgram
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TerrainTranslatorTest {
    @Test fun `keeps pack varying interface and translates fixed-function vertex inputs`() {
        val program = LoadedProgram(
            "terrain",
            """
                #version 120
                varying vec4 tint;
                void main() { gl_Position = ftransform(); tint = gl_Color; }
            """.trimIndent(),
            """
                #version 120
                uniform sampler2D texture;
                varying vec4 tint;
                void main() { gl_FragData[0] = texture2D(texture, vec2(0.5)) * tint; }
            """.trimIndent(),
            null, emptyList(), listOf(0), emptySet(),
        )
        val vertex = LegacyTranslator.terrainVertex(program)
        val fragment = LegacyTranslator.terrainFragment(program)
        assertContains(vertex, "layout(location = 3) out vec4 tint;")
        assertContains(vertex, "ProjMat * ModelViewMat * vec4(pos, 1.0)")
        assertContains(fragment, "layout(location = 3) in vec4 tint;")
        assertContains(fragment, "texture(Sampler0, vec2(0.5))")
        assertFalse(fragment.contains("Sampler0(Sampler0"))
    }
}
