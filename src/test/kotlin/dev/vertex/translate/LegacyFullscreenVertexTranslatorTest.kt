package dev.vertex.translate

import kotlin.test.Test
import kotlin.test.assertContains

class LegacyFullscreenVertexTranslatorTest {
    @Test
    fun `preserves multiple varying calculations on a vertex index triangle`() {
        val translated = LegacyFullscreenVertexTranslator.translate(
            """#version 120
                |varying vec2 uv;
                |varying vec4 tint;
                |void main() {
                |  gl_Position = ftransform();
                |  uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                |  tint = vec4(1.0);
                |}
            """.trimMargin(),
        )
        assertContains(translated, "layout(location = 0) out vec2 uv;")
        assertContains(translated, "layout(location = 1) out vec4 tint;")
        assertContains(translated, "gl_VertexIndex")
        assertContains(translated, "mat4(1.0) * vec4(vertexUv")
    }
}
