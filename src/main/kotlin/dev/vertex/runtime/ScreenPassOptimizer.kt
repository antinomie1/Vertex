package dev.vertex.runtime

/** Conservative zero-boundary case: a pass whose sole effect is copying colortex0 to itself. */
object ScreenPassOptimizer {
    fun isIdentityCopy(fragment: String, outputs: List<Int>, samplers: List<String>): Boolean {
        if (outputs != listOf(0) || samplers.map(::canonical).distinct() != listOf("colortex0")) return false
        val body = Regex("""void\s+main\s*\(\s*\)\s*\{([^{}]*)}""").find(fragment)?.groupValues?.get(1)
            ?: return false
        val statement = body.replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "").filterNot(Char::isWhitespace)
        return statement in setOf(
            "gl_FragData[0]=texture2D(colortex0,texcoord);",
            "gl_FragData[0]=texture(colortex0,texcoord);",
            "gl_FragColor=texture2D(colortex0,texcoord);",
            "gl_FragColor=texture(colortex0,texcoord);",
        ) || statement in setOf(
            "gl_FragData[0]=texture2D(gcolor,texcoord);",
            "gl_FragData[0]=texture(gcolor,texcoord);",
            "gl_FragColor=texture2D(gcolor,texcoord);",
            "gl_FragColor=texture(gcolor,texcoord);",
        )
    }

    private fun canonical(name: String) = if (name == "gcolor") "colortex0" else name
}
