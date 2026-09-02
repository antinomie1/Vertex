package dev.vertex.translate

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackUniformCatalogTest {
    @Test
    fun `rewrites known loose uniforms into one fixed block`() {
        val translated = LegacyUniformTranslator.translate(
            "uniform float viewWidth;\nuniform vec3 cameraPosition;\nvoid main() {}",
        )
        assertContains(translated, "uniform VertexPackUniforms")
        assertContains(translated, "float viewWidth;")
        assertContains(translated, "float vertexUniform_timeAngle;")
        assertEquals(0, "uniform float viewWidth".toRegex().findAll(translated).count())
        assertEquals(setOf("viewWidth", "cameraPosition"),
            LegacyUniformTranslator.uniforms("uniform float viewWidth; uniform vec3 cameraPosition;"))
    }

    @Test
    fun `gives custom uniforms safe fallbacks but rejects mistyped builtins`() {
        val translated = LegacyUniformTranslator.translate(
            "uniform float mystery; uniform bool packFlag = true; uniform vec3 view_light_dir;",
        )
        assertContains(translated, "float mystery = 0.0;")
        assertContains(translated, "bool packFlag = true;")
        assertContains(translated, "vec3 view_light_dir = normalize(shadowLightPosition);")
        assertFailsWith<IllegalArgumentException> { LegacyUniformTranslator.translate("uniform int viewWidth;") }
    }

    @Test
    fun `accepts boolean integer vector and matrix catalog members`() {
        val source = "uniform bool hideGUI; uniform ivec3 currentDate; uniform mat3 gbufferNormal;"
        assertEquals(setOf("hideGUI", "currentDate", "gbufferNormal"), LegacyUniformTranslator.uniforms(source))
    }

    @Test
    fun `accepts precision-qualified pack uniforms`() {
        assertEquals(setOf("viewWidth"), LegacyUniformTranslator.uniforms("uniform highp float viewWidth;"))
    }

    @Test
    fun `accepts comma-separated pack uniforms`() {
        assertEquals(setOf("near", "far", "timeAngle"), LegacyUniformTranslator.uniforms(
            "uniform float near, far; uniform float timeAngle;",
        ))
    }

    @Test
    fun `translates legacy fog state without exposing unrelated names`() {
        val translated = LegacyUniformTranslator.translate(
            "uniform float far; float fog = (far - gl_Fog.start) * gl_Fog.scale;",
        )
        assertContains(translated, "float fogStart;")
        assertContains(translated, "float fogEnd;")
        assertContains(translated, "float vertexUniform_timeAngle;")
        assertContains(translated, "(1.0 / max(fogEnd - fogStart, 0.0001))")
    }
}
