package dev.vertex.translate

/** Rewrites legacy fullscreen vertex programs while preserving their varying calculations. */
object LegacyFullscreenVertexTranslator {
    private val varying = Regex("""\bvarying\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+([^;]+);""")

    fun translate(source: String): String {
        var location = 0
        var body = source
            .replace(Regex("""^\s*#version[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*#extension[^\n]*""", RegexOption.MULTILINE), "")
        body = LegacyUniformTranslator.translate(body)
        body = varying.replace(body) { match ->
            match.groupValues[2].split(',').joinToString("\n") { raw ->
                val declaration = raw.trim()
                val assigned = location
                location += locationWidth(match.groupValues[1], declaration)
                "layout(location = $assigned) out ${match.groupValues[1]} $declaration;"
            }
        }
        body = body
            .let(::modernizeTextureCalls)
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "vec4(vertexUv * 2.0 - 1.0, 0.0, 1.0)")
            .replace(Regex("""\bgl_Vertex\b"""), "vec4(vertexUv * 2.0 - 1.0, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(vertexUv, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord[12]\b"""), "vec4(0.0)")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*[01]\s*]"""), "mat4(1.0)")
            .replace(Regex("""\bgl_ModelViewProjectionMatrix\b|\bgl_ModelViewMatrix\b|\bgl_ProjectionMatrix\b"""), "mat4(1.0)")
            .replace(Regex("""\bgl_NormalMatrix\b"""), "mat3(1.0)")
            .replace(Regex("""\bgl_Color\b"""), "vec4(1.0)")
            .replace(Regex("""\bgl_Normal\b"""), "vec3(0.0, 0.0, 1.0)")
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(body)
            ?: throw IllegalArgumentException("fullscreen vertex shader has no main()")
        body = body.replaceRange(
            main.range,
            main.value + "\n    vec2 vertexUv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));",
        )
        require("vertexUv" in body) { "fullscreen vertex shader has no main()" }
        return "#version 450\n#extension GL_ARB_separate_shader_objects : require\n" + body.trimStart()
    }

    private val TEXTURE_CALL = Regex("""\b(texture2DGradARB|texture2DGradEXT|texture2DProj|texture2DLod|texture2D|texture3D|textureCube)\s*\(""")

    private fun modernizeTextureCalls(source: String) = source.replace(TEXTURE_CALL) {
        (when (it.groupValues[1]) {
            "texture2DProj" -> "textureProj"
            "texture2DLod" -> "textureLod"
            "texture2DGradARB", "texture2DGradEXT" -> "textureGrad"
            else -> "texture"
        }) + "("
    }

    private fun locationWidth(type: String, declaration: String): Int {
        val columns = Regex("""(?:d?mat)([234])(?:x[234])?""").matchEntire(type)
            ?.groupValues?.get(1)?.toInt() ?: 1
        val expression = Regex("""\[\s*([^]]+)\s*]""").find(declaration)?.groupValues?.get(1) ?: return columns
        val extent = expression.trim().toIntOrNull()
            ?: Regex("""(\d+)\s*/\s*(\d+)""").matchEntire(expression.trim())?.destructured
                ?.let { (left, right) -> left.toInt() / right.toInt() }
            ?: Regex("""(\d+)\s*\*\s*(\d+)""").matchEntire(expression.trim())?.destructured
                ?.let { (left, right) -> left.toInt() * right.toInt() }
            ?: 1
        return columns * extent.coerceAtLeast(1)
    }
}
