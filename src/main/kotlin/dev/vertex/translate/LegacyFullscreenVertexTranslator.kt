package dev.vertex.translate

/** Rewrites legacy fullscreen vertex programs while preserving their varying calculations. */
object LegacyFullscreenVertexTranslator {
    private val varying = Regex("""\bvarying\s+(\w+)\s+(\w+)\s*;""")

    fun translate(source: String): String {
        var location = 0
        var body = source
            .replace(Regex("""^\s*#version[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*#extension[^\n]*""", RegexOption.MULTILINE), "")
        body = varying.replace(body) {
            "layout(location = ${location++}) out ${it.groupValues[1]} ${it.groupValues[2]};"
        }
        body = body
            .replace(Regex("""\bftransform\s*\(\s*\)"""), "vec4(vertexUv * 2.0 - 1.0, 0.0, 1.0)")
            .replace(Regex("""\bgl_MultiTexCoord0\b"""), "vec4(vertexUv, 0.0, 1.0)")
            .replace(Regex("""\bgl_TextureMatrix\s*\[\s*0\s*]"""), "mat4(1.0)")
        val main = Regex("""void\s+main\s*\(\s*\)\s*\{""").find(body)
            ?: throw IllegalArgumentException("fullscreen vertex shader has no main()")
        body = body.replaceRange(
            main.range,
            main.value + "\n    vec2 vertexUv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));",
        )
        require("vertexUv" in body) { "fullscreen vertex shader has no main()" }
        return "#version 330\n#extension GL_ARB_separate_shader_objects : require\n" + body.trimStart()
    }
}
