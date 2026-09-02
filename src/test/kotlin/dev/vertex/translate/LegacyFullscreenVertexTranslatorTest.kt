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

    @Test
    fun `accepts precision-qualified varyings`() {
        val translated = LegacyFullscreenVertexTranslator.translate(
            "varying lowp vec2 uv; void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }",
        )
        assertContains(translated, "layout(location = 0) out vec2 uv;")
    }

    @Test
    fun `expands comma-separated varyings`() {
        val translated = LegacyFullscreenVertexTranslator.translate(
            "varying vec3 sunVec, upVec; void main() { sunVec = vec3(1.0); upVec = sunVec; gl_Position = ftransform(); }",
        )
        assertContains(translated, "layout(location = 0) out vec3 sunVec;")
        assertContains(translated, "layout(location = 1) out vec3 upVec;")
    }

    @Test
    fun `maps legacy fullscreen builtins and texture calls`() {
        val translated = LegacyFullscreenVertexTranslator.translate(
            "uniform sampler2D tex; void main() { gl_Position = gl_Vertex; gl_FragCoord = texture2D(tex, gl_MultiTexCoord1.xy); }",
        )
        assertContains(translated, "vec4(vertexUv * 2.0 - 1.0, 0.0, 1.0)")
        assertContains(translated, "texture(tex, vec4(0.0).xy)")
    }

    @Test
    fun `reserves all locations occupied by matrices and arrays`() {
        val translated = LegacyFullscreenVertexTranslator.translate(
            "varying mat3 basis; varying vec3 samples[4]; varying float tail; void main() { gl_Position = ftransform(); }",
        )
        assertContains(translated, "layout(location = 0) out mat3 basis;")
        assertContains(translated, "layout(location = 3) out vec3 samples[4];")
        assertContains(translated, "layout(location = 7) out float tail;")
    }
}
