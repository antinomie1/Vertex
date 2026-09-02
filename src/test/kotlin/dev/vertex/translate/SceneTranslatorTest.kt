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
        assertContains(LegacyTranslator.skyVertex(sky), "gl_Position = vertexFullscreenSky(Position);")
        assertContains(LegacyTranslator.skyFragment(sky), "layout(location = 2) in vec2 texcoord;")
        val fragment = LegacyTranslator.skyFragment(sky)
        assertContains(fragment, "texture(Sampler0, texcoord)")
        kotlin.test.assertEquals(1, "uniform sampler2D Sampler0;".toRegex().findAll(fragment).count())
    }

    @Test
    fun `stars variant omits fog ABI`() {
        val vertex = LegacyTranslator.skyVertex(sky, includeFog = false)
        val fragment = LegacyTranslator.skyFragment(sky, includeFog = false)
        assertFalse(vertex.contains("vertexFullscreenSky"))
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

    @Test
    fun `block stages expose the compact block ABI`() {
        val vertex = LegacyTranslator.blockVertex(particle.copy(
            vertexSource = particle.vertexSource.replace(
                "#version 120",
                "#version 120\nattribute vec4 at_tangent;\nattribute vec2 mc_midTexCoord;",
            ).replace(
                "gl_Position = ftransform();",
                "gl_Position = ftransform(); vec3 normal = gl_NormalMatrix * gl_Normal; " +
                    "vec4 tangent = at_tangent; texcoord = mc_midTexCoord; vec2 light = gl_MultiTexCoord2.xy;",
            ),
        ))
        assertContains(vertex, "layout(location = 3) in ivec2 UV2;")
        assertContains(vertex, "vec4(UV0, 0.0, 1.0)")
        assertContains(vertex, "mat3(ModelViewMat) * vec3(0.0, 1.0, 0.0)")
        assertContains(vertex, "vec4 tangent = vec4(1.0, 0.0, 0.0, 1.0)")
        assertContains(vertex, "vec2(UV2) / 256.0")
        assertFalse(vertex.contains("layout(location = 3) in ivec2 UV1;"))
        assertFalse(vertex.contains("gl_Normal"))
    }

    @Test
    fun `auxiliary stages match colored line and textured sky formats`() {
        val colored = LegacyTranslator.coloredVertex(sky.copy(
            name = "gbuffers_line",
            vertexSource = sky.vertexSource.replace("gl_Vertex.xy", "gl_Color.rgb + gl_Normal"),
        ), includeNormal = true)
        assertContains(colored, "layout(location = 1) in vec4 Color;")
        assertContains(colored, "layout(location = 2) in vec3 Normal;")

        val textured = LegacyTranslator.texturedSkyVertex(particle.copy(name = "gbuffers_skytextured"))
        assertContains(textured, "layout(location = 1) in vec2 UV0;")
        assertContains(textured, "vec4(UV0, 0.0, 1.0)")
    }

    @Test
    fun `maps the short base texture alias in dynamic programs`() {
        val fragment = LegacyTranslator.dynamicFragment(particle.copy(
            fragmentSource = particle.fragmentSource
                .replace("uniform sampler2D texture;", "uniform sampler2D tex;")
                .replace("texture2D(texture, texcoord)", "texture2D(tex, texcoord)"),
            samplers = listOf("tex"),
        ))
        assertContains(fragment, "uniform sampler2D Sampler0;")
        assertContains(fragment, "texture(Sampler0, texcoord)")
    }
}
