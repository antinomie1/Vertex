package dev.vertex.translate

import dev.vertex.frontend.ColorNumericType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
            "uniform sampler2DShadow shadowtex0; void main() { gl_FragColor = shadow2D(shadowtex0, vec3(0.5)); }",
        )
        assertContains(translated, "#define shadow2D")
        assertContains(translated, "uniform sampler2D shadowtex0")
        assertContains(translated, "<= texture")
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

    @Test
    fun `emits integer outputs matching attachment numeric types`() {
        val translated = LegacyFragmentTranslator.translate(
            "void main() { gl_FragData[0] = vec4(7.0); gl_FragData[1] = vec4(-2.0); }",
            listOf(ColorNumericType.UINT, ColorNumericType.SINT),
        )
        assertContains(translated, "out uvec4 vertexFragColor0")
        assertContains(translated, "vertexFragColor0 = uvec4(vec4(7.0))")
        assertContains(translated, "out ivec4 vertexFragColor1")
    }

    @Test
    fun `preserves correctly typed modern integer outputs`() {
        val source = "layout(location = 0) out uvec4 data; void main() { data = uvec4(3); }"
        val translated = LegacyFragmentTranslator.translate(source, listOf(ColorNumericType.UINT))
        assertContains(translated, "out uvec4 data")
        assertFailsWith<IllegalArgumentException> {
            LegacyFragmentTranslator.translate(source, listOf(ColorNumericType.FLOAT))
        }
    }
}
