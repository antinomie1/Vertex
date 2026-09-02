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

    @Test
    fun rewritesCompatibilityOnlyUintInitializersAndSamplerNames() {
        val result = LegacyGlslSyntax.translate(
            "const uint steps = 12; vec4 filter(sampler2D sampler) { return texture(sampler, vec2(0.0)); }",
        )
        assertContains(result, "const uint steps = uint(12);")
        assertContains(result, "sampler2D vertexSampler")
        assertContains(result, "texture(vertexSampler")
    }

    @Test
    fun lowersThreeDimensionalSamplingForTwoDimensionalBackends() {
        val result = LegacyGlslSyntax.translate(
            "#version 120\nuniform sampler3D lut; uint id; vec4 f(vec3 p) { return id == 0 ? texture(lut, p) : vec4(1.0); }",
        )
        assertContains(result, "uniform sampler2D lut")
        assertContains(result, "vertexTexture3D(lut")
        assertContains(result, "id == 0u")
        assertContains(result, "vec4 vertexTexture3D")
    }
}
