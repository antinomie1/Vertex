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
}
