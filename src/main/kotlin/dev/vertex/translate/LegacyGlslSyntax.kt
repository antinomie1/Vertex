package dev.vertex.translate

/** Small, provider-independent rewrites for GLSL 1.20 array syntax. */
object LegacyGlslSyntax {
    fun translate(source: String): String = source
        // GLSL 1.20 permits the array extent before the variable name. GLSL 330
        // keeps the extent on the declarator instead.
        .replace(ARRAY_TYPE_DECLARATION) { match ->
            "${match.groupValues[1]} ${match.groupValues[3]}[${match.groupValues[2]}] ${match.groupValues[4]}"
        }
        // glslang accepts unsized constructors for a declared array and infers
        // the element count from the initializer.
        .replace(ARRAY_CONSTRUCTOR) { match -> "${match.groupValues[1]}[]${match.groupValues[2]}" }

    private val ARRAY_TYPE_DECLARATION = Regex(
        """\b(float|int|bool|vec[234]|ivec[234]|uvec[234]|mat[234])\s*\[\s*(\d+)\s*]\s+([A-Za-z_]\w*)\s*(=\s*)""",
    )
    private val ARRAY_CONSTRUCTOR = Regex(
        """\b(float|int|bool|vec[234]|ivec[234]|uvec[234]|mat[234])\s*\[\s*\d+\s*](\s*\()""",
    )
}
