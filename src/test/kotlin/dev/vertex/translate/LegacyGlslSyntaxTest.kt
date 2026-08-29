package dev.vertex.translate

import kotlin.test.Test
import kotlin.test.assertContains

class LegacyGlslSyntaxTest {
    @Test
    fun rewritesLegacyArrayDeclarationsAndConstructors() {
        val result = LegacyGlslSyntax.translate(
            "vec3[2] colors = vec3[2](vec3(1.0), vec3(0.0)); vec2 offsets[2] = vec2[2](vec2(0.0));",
        )
        assertContains(result, "vec3 colors[2] = vec3[](vec3(1.0), vec3(0.0))")
        assertContains(result, "vec2 offsets[2] = vec2[](vec2(0.0))")
    }
}
