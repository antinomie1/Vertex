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

    @Test fun `modernizes projected texture calls in terrain stages`() {
        val program = LoadedProgram(
            "terrain-proj",
            "#version 120\nvoid main() { gl_Position = ftransform(); }",
            "#version 120\nuniform sampler2D texture; void main() { gl_FragData[0] = texture2DProj(texture, vec3(0.5)); }",
            null, emptyList(), listOf(0), emptySet(),
        )
        assertContains(LegacyTranslator.terrainFragment(program), "textureProj(Sampler0")
    }

    @Test fun `keeps shadow texture calls while rebinding legacy sampler`() {
        val program = LoadedProgram(
            "shadow",
            "#version 120\nvoid main() { gl_Position = ftransform(); }",
            "#version 120\nuniform sampler2D texture; void main() { gl_FragColor = texture(texture, vec2(0.5)); }",
            null, emptyList(), listOf(0), emptySet(),
        )
        val fragment = LegacyTranslator.shadowFragment(program)
        assertContains(fragment, "texture(Sampler0, vec2(0.5))")
        assertFalse(fragment.contains("Sampler0(Sampler0"))
    }

    @Test fun `maps shadow model projection builtin`() {
        val program = LoadedProgram(
            "shadow-mvp",
            "#version 120\nvoid main() { gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }",
            "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }",
            null, emptyList(), listOf(0), emptySet(),
        )
        val vertex = LegacyTranslator.shadowVertex(program)
        assertContains(vertex, "vertexShadowMvp * vec4(pos, 1.0)")
    }
}
