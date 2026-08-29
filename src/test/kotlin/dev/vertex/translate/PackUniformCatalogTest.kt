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
        assertEquals(0, "uniform float viewWidth".toRegex().findAll(translated).count())
        assertEquals(setOf("viewWidth", "cameraPosition"),
            LegacyUniformTranslator.uniforms("uniform float viewWidth; uniform vec3 cameraPosition;"))
    }

    @Test
    fun `rejects unknown or mistyped uniforms before GPU compilation`() {
        assertFailsWith<IllegalArgumentException> { LegacyUniformTranslator.translate("uniform float mystery;") }
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
}
