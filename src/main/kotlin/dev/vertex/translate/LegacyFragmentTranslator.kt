package dev.vertex.translate

import dev.vertex.frontend.ColorNumericType

/** Stateless GLSL 1.20 fragment syntax modernization; runtime bindings are validated separately. */
object LegacyFragmentTranslator {
    private val varying = Regex("""\bvarying\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+([^;]+);""")
    private val fragData = Regex("""\bgl_FragData\s*\[\s*(\d+)\s*]""")

    fun translate(source: String, outputTypes: List<ColorNumericType> = emptyList()): String {
        val shadowSamplers = SHADOW_SAMPLER.findAll(source).map { it.groupValues[1] }.toSet()
        var body = source
            .replace(Regex("""^\s*#version[^\n]*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*#extension[^\n]*""", RegexOption.MULTILINE), "")
            .replace(BUFFER_DIRECTIVE, "")
            .replace(Regex("""\buniform\s+sampler2DShadow\b"""), "uniform sampler2D")
        shadowSamplers.forEach { sampler ->
            body = body.replace(
                Regex("""\btexture\s*\(\s*${Regex.escape(sampler)}\s*,"""),
                "vertexShadowCompare($sampler,",
            )
        }
        body = LegacyUniformTranslator.translate(body)
        var location = 0
        body = varying.replace(body) { match ->
            match.groupValues[2].split(',').joinToString("\n") { raw ->
                val declaration = raw.trim()
                val assigned = location
                location += locationWidth(match.groupValues[1], declaration)
                "layout(location = $assigned) in ${match.groupValues[1]} $declaration;"
            }
        }
        body = body.replace(TEXTURE_CALL) { textureFunction(it.groupValues[1]) + "(" }

        val legacyOutputs = fragData.findAll(body).map { it.groupValues[1].toInt() }.toMutableSet()
        if ("gl_FragColor" in body) legacyOutputs += 0
        val modernOutputs = MODERN_OUTPUT.findAll(body).associate { it.groupValues[1].toInt() to it.groupValues[2] }
        val outputs = legacyOutputs + modernOutputs.keys
        require(outputs.all { it in 0..15 }) { "fragment output location must be in 0..15" }
        require(outputs.isNotEmpty()) { "fragment shader has no color output" }
        require(legacyOutputs.intersect(modernOutputs.keys).isEmpty()) { "fragment output location declared twice" }
        modernOutputs.forEach { (location, type) ->
            val expected = outputTypes.getOrElse(location) { ColorNumericType.FLOAT }
            require(numericType(type) == expected) { "fragment output $location is $type; attachment requires ${outputType(expected)}" }
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
        body = LegacyShadowCompare.inject(body)

        return buildString {
            appendLine("#version 450")
            appendLine("#extension GL_ARB_separate_shader_objects : require")
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

    private fun numericType(type: String): ColorNumericType = when {
        type == "uint" || type.startsWith("uvec") -> ColorNumericType.UINT
        type == "int" || type.startsWith("ivec") -> ColorNumericType.SINT
        else -> ColorNumericType.FLOAT
    }

    private val ASSIGNMENT = Regex("""\bgl_FragData\s*\[\s*(\d+)\s*]\s*=\s*([^;]+);""")
    private val FRAG_COLOR_ASSIGNMENT = Regex("""\bgl_FragColor\s*=\s*([^;]+);""")
    private val MODERN_OUTPUT = Regex("""layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*out\s+(float|int|uint|[iu]?vec[234])\s+\w+\s*;""")
    private val TEXTURE_CALL = Regex("""\b(texture2DGradARB|texture2DGradEXT|texture2DProj|texture2DLod|texture2D|texture3D|textureCube)\s*\(""")
    private val SHADOW_SAMPLER = Regex("""\buniform\s+sampler2DShadow\s+([A-Za-z_]\w*)\s*;""")

    private fun textureFunction(name: String) = when (name) {
        "texture2DProj" -> "textureProj"
        "texture2DLod" -> "textureLod"
        "texture2DGradARB", "texture2DGradEXT" -> "textureGrad"
        else -> "texture"
    }

    private val BUFFER_DIRECTIVE = Regex(
        """^\s*const\s+(?:int|bool|vec4)\s+\w+(?:Format|Clear|ClearColor)\s*=.*;\s*$""",
        RegexOption.MULTILINE,
    )

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
