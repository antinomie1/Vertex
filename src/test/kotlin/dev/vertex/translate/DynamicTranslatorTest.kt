package dev.vertex.translate

import dev.vertex.frontend.LoadedProgram
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class DynamicTranslatorTest {
    private val program = LoadedProgram(
        "gbuffers_entities",
        """#version 120
varying vec2 texcoord;
void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.xy; }
""",
        """#version 120
uniform sampler2D texture;
varying vec2 texcoord;
void main() { gl_FragColor = texture2D(texture, texcoord); }
""",
        null, emptyList(), listOf(0), emptySet(),
    )

    @Test
    fun `dynamic stages use entity ABI and preserve varying locations`() {
        val vertex = LegacyTranslator.dynamicVertex(program)
        val fragment = LegacyTranslator.dynamicFragment(program)
        assertContains(vertex, "layout(location = 5) in vec3 Normal;")
        assertContains(vertex, "layout(location = 2) out vec2 texcoord;")
        assertContains(fragment, "layout(location = 2) in vec2 texcoord;")
        assertContains(fragment, "texture(Sampler0, texcoord)")
    }

    @Test
    fun `dynamic stages reject secondary render targets`() {
        val invalid = program.copy(fragmentSource = program.fragmentSource.replace("gl_FragColor", "gl_FragData[1]"))
        assertFailsWith<IllegalArgumentException> { LegacyTranslator.dynamicFragment(invalid) }
    }
}
