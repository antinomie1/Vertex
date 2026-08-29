package dev.vertex.translate

import dev.vertex.frontend.ColorNumericType

/** Stateless GLSL 1.20 fragment syntax modernization; runtime bindings are validated separately. */
object LegacyFragmentTranslator {
    private val varying = Regex("""\bvarying\s+(\w+)\s+(\w+)\s*;""")
    private val fragData = Regex("""\bgl_FragData\s*\[\s*(\d+)\s*]""")

    fun translate(source: String, outputTypes: List<ColorNumericType> = emptyList()): String {
        var body = source
            .replace(Regex("""^\s*#version[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*#extension[^\n]*""", RegexOption.MULTILINE), "")
            .replace(BUFFER_DIRECTIVE, "")
            .replace(Regex("""\buniform\s+sampler2DShadow\b"""), "uniform sampler2D")
        body = LegacyUniformTranslator.translate(body)
        var location = 0
        body = varying.replace(body) { match ->
            "layout(location = ${location++}) in ${match.groupValues[1]} ${match.groupValues[2]};"
        }
        body = body.replace(Regex("""\btexture(?:2D|3D)\s*\("""), "texture(")

        val legacyOutputs = fragData.findAll(body).map { it.groupValues[1].toInt() }.toMutableSet()
        if ("gl_FragColor" in body) legacyOutputs += 0
        val modernOutputs = MODERN_OUTPUT.findAll(body).associate { it.groupValues[1].toInt() to it.groupValues[2] }
        val outputs = legacyOutputs + modernOutputs.keys
        require(outputs.all { it in 0..15 }) { "fragment output location must be in 0..15" }
        require(outputs.isNotEmpty()) { "fragment shader has no color output" }
        require(legacyOutputs.intersect(modernOutputs.keys).isEmpty()) { "fragment output location declared twice" }
        modernOutputs.forEach { (location, type) ->
            val expected = outputType(outputTypes.getOrElse(location) { ColorNumericType.FLOAT })
            require(type == expected) { "fragment output $location is $type; attachment requires $expected" }
        }
        body = ASSIGNMENT.replace(body) { match ->
            val location = match.groupValues[1].toInt()
            "vertexFragColor$location = ${cast(outputTypes.getOrElse(location) { ColorNumericType.FLOAT }, match.groupValues[2])};"
        }
        body = FRAG_COLOR_ASSIGNMENT.replace(body) { match ->
            "vertexFragColor0 = ${cast(outputTypes.getOrElse(0) { ColorNumericType.FLOAT }, match.groupValues[1])};"
        }
        body = fragData.replace(body) { "vertexFragColor${it.groupValues[1]}" }
            .replace(Regex("""\bgl_FragColor\b"""), "vertexFragColor0")

        return buildString {
            appendLine("#version 330")
            appendLine("#extension GL_ARB_separate_shader_objects : require")
            if ("shadow2D" in body) appendLine("#define shadow2D(s, c) vec4(float((c).z <= texture((s), (c).xy).r))")
            legacyOutputs.sorted().forEach { location ->
                appendLine("layout(location = $location) out ${outputType(outputTypes.getOrElse(location) { ColorNumericType.FLOAT })} vertexFragColor$location;")
            }
            append(body.trimStart())
        }
    }

    private fun cast(type: ColorNumericType, expression: String) = when (type) {
        ColorNumericType.FLOAT -> expression
        ColorNumericType.SINT -> "ivec4($expression)"
        ColorNumericType.UINT -> "uvec4($expression)"
    }

    private fun outputType(type: ColorNumericType) = when (type) {
        ColorNumericType.FLOAT -> "vec4"; ColorNumericType.SINT -> "ivec4"; ColorNumericType.UINT -> "uvec4"
    }

    private val ASSIGNMENT = Regex("""\bgl_FragData\s*\[\s*(\d+)\s*]\s*=\s*([^;]+);""")
    private val FRAG_COLOR_ASSIGNMENT = Regex("""\bgl_FragColor\s*=\s*([^;]+);""")
    private val MODERN_OUTPUT = Regex("""layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*out\s+(vec4|ivec4|uvec4)\s+\w+\s*;""")

    private val BUFFER_DIRECTIVE = Regex(
        """^\s*const\s+(?:int|bool|vec4)\s+\w+(?:Format|Clear|ClearColor)\s*=.*;\s*$""",
        RegexOption.MULTILINE,
    )
}
