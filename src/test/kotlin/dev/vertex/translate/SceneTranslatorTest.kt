package dev.vertex.translate

import dev.vertex.frontend.LoadedProgram
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SceneTranslatorTest {
    private val sky = LoadedProgram(
        "gbuffers_skybasic",
        """#version 120
varying vec2 texcoord;
void main() { gl_Position = ftransform(); texcoord = gl_Vertex.xy; }
""",
        """#version 120
uniform sampler2D texture;
varying vec2 texcoord;
void main() { gl_FragColor = texture2D(texture, texcoord); }
""",
        null, listOf("texture"), listOf(0), emptySet(),
    )
    private val particle = sky.copy(
        name = "gbuffers_weather",
        vertexSource = sky.vertexSource.replace("gl_Vertex.xy", "gl_MultiTexCoord0.xy"),
    )

    @Test
    fun `sky stages map position and pack varyings`() {
        assertContains(LegacyTranslator.skyVertex(sky), "layout(location = 0) in vec3 Position;")
        assertContains(LegacyTranslator.skyVertex(sky), "layout(location = 2) out vec2 texcoord;")
        assertContains(LegacyTranslator.skyFragment(sky), "layout(location = 2) in vec2 texcoord;")
        val fragment = LegacyTranslator.skyFragment(sky)
        assertContains(fragment, "texture(Sampler0, texcoord)")
        kotlin.test.assertEquals(1, "uniform sampler2D Sampler0;".toRegex().findAll(fragment).count())
    }

    @Test
    fun `stars variant omits fog ABI`() {
        val vertex = LegacyTranslator.skyVertex(sky, includeFog = false)
        val fragment = LegacyTranslator.skyFragment(sky, includeFog = false)
        assertFalse(vertex.contains("minecraft:fog.glsl"))
        assertFalse(fragment.contains("minecraft:fog.glsl"))
    }

    @Test
    fun `particle stages expose the vanilla particle ABI`() {
        val vertex = LegacyTranslator.particleVertex(particle)
        val fragment = LegacyTranslator.particleFragment(particle)
        assertContains(vertex, "layout(location = 1) in vec2 UV0;")
        assertContains(vertex, "layout(location = 2) in vec4 Color;")
        assertContains(vertex, "layout(location = 3) in ivec2 UV2;")
        assertContains(fragment, "uniform sampler2D Sampler0;")
    }
}
