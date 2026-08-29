package dev.vertex.translate

/** Stateless GLSL 1.20 fragment syntax modernization; runtime bindings are validated separately. */
object LegacyFragmentTranslator {
    private val varying = Regex("""\bvarying\s+(\w+)\s+(\w+)\s*;""")
    private val fragData = Regex("""\bgl_FragData\s*\[\s*(\d+)\s*]""")

    fun translate(source: String): String {
        var body = source
            .replace(Regex("""^\s*#version[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*#extension[^\n]*""", RegexOption.MULTILINE), "")
        var location = 0
        body = varying.replace(body) { match ->
            "layout(location = ${location++}) in ${match.groupValues[1]} ${match.groupValues[2]};"
        }
        body = body.replace(Regex("""\btexture(?:2D|3D)\s*\("""), "texture(")

        val outputs = fragData.findAll(body).map { it.groupValues[1].toInt() }.toMutableSet()
        require(outputs.all { it in 0..15 }) { "gl_FragData location must be in 0..15" }
        if ("gl_FragColor" in body) outputs += 0
        require(outputs.isNotEmpty()) { "fragment shader has no legacy color output" }
        body = fragData.replace(body) { "vertexFragColor${it.groupValues[1]}" }
            .replace(Regex("""\bgl_FragColor\b"""), "vertexFragColor0")

        return buildString {
            appendLine("#version 330")
            appendLine("#extension GL_ARB_separate_shader_objects : require")
            if ("shadow2D" in body) appendLine("#define shadow2D(s, c) vec4(texture((s), (c)))")
            outputs.sorted().forEach { appendLine("layout(location = $it) out vec4 vertexFragColor$it;") }
            append(body.trimStart())
        }
    }
}
