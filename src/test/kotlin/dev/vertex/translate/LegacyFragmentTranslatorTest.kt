package dev.vertex.translate

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class LegacyFragmentTranslatorTest {
    @Test
    fun `assigns varying and output locations deterministically`() {
        val translated = LegacyFragmentTranslator.translate(
            """#version 120
                |varying vec2 uv;
                |varying vec4 tint;
                |void main() {
                |  gl_FragData[2] = tint;
                |  gl_FragData[0] = texture2D(tex, uv);
                |}
            """.trimMargin(),
        )
        assertContains(translated, "layout(location = 0) in vec2 uv;")
        assertContains(translated, "layout(location = 1) in vec4 tint;")
        assertContains(translated, "layout(location = 0) out vec4 vertexFragColor0;")
        assertContains(translated, "layout(location = 2) out vec4 vertexFragColor2;")
        assertEquals(1, Regex("layout\\(location = 2\\) out").findAll(translated).count())
    }

    @Test
    fun `preserves legacy shadow vector return and frag color`() {
        val translated = LegacyFragmentTranslator.translate(
            "void main() { gl_FragColor = shadow2D(shadowtex0, vec3(0.5)); }",
        )
        assertContains(translated, "#define shadow2D")
        assertContains(translated, "vertexFragColor0 = shadow2D")
    }

    @Test
    fun `consumes framebuffer constants before GLSL compilation`() {
        val translated = LegacyFragmentTranslator.translate("""
            const int colortex1Format = RGBA16F;
            const bool colortex1Clear = false;
            const vec4 colortex1ClearColor = vec4(1.0);
            void main() { gl_FragColor = vec4(1.0); }
        """.trimIndent())

        assertEquals(false, "colortex1" in translated)
    }
}
