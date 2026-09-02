package dev.vertex.translate

/** Emulates a linearly filtered sampler2DShadow using an ordinary depth sampler. */
object LegacyShadowCompare {
    fun lower(source: String): String {
        val samplers = SHADOW_SAMPLER.findAll(source).map { it.groupValues[1] }.toSet()
        var lowered = source.replace(Regex("""\bsampler2DShadow\b"""), "sampler2D")
        samplers.forEach { sampler ->
            lowered = lowered.replace(
                Regex("""\btexture\s*\(\s*${Regex.escape(sampler)}\s*,"""),
                "vertexShadowCompare($sampler,",
            )
        }
        return inject(lowered)
    }

    fun inject(source: String): String {
        if ("shadow2D" !in source && "vertexShadowCompare" !in source) return source
        return PRELUDE + source
    }

    private const val PRELUDE = """
float vertexShadowCompare(sampler2D shadowMap, vec3 shadowCoord) {
    vec2 texel = 1.0 / vec2(textureSize(shadowMap, 0));
    vec2 cell = shadowCoord.xy / texel - 0.5;
    vec2 base = floor(cell);
    vec2 weight = fract(cell);
    vec2 uv = (base + 0.5) * texel;
    float c00 = float(shadowCoord.z <= texture(shadowMap, uv).r);
    float c10 = float(shadowCoord.z <= texture(shadowMap, uv + vec2(texel.x, 0.0)).r);
    float c01 = float(shadowCoord.z <= texture(shadowMap, uv + vec2(0.0, texel.y)).r);
    float c11 = float(shadowCoord.z <= texture(shadowMap, uv + texel).r);
    return mix(mix(c00, c10, weight.x), mix(c01, c11, weight.x), weight.y);
}
#define shadow2D(s, c) vec4(vertexShadowCompare((s), (c)))
#define shadow2DProj(s, c) vec4(vertexShadowCompare((s), vec3((c).xy / (c).w, (c).z / (c).w)))
"""
    private val SHADOW_SAMPLER = Regex("""\buniform\s+sampler2DShadow\s+([A-Za-z_]\w*)\s*;""")
}
