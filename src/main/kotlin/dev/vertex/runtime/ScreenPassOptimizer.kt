package dev.vertex.runtime

/** Conservative zero-boundary case: a pass whose sole effect is copying colortex0 to itself. */
object ScreenPassOptimizer {
    fun isIdentityCopy(fragment: String, outputs: List<Int>, samplers: List<String>): Boolean {
        if (outputs != listOf(0) || samplers.distinct() != listOf("colortex0")) return false
        val body = Regex("""void\s+main\s*\(\s*\)\s*\{([\s\S]*)}""").find(fragment)?.groupValues?.get(1)
            ?: return false
        val statement = body.replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "").filterNot(Char::isWhitespace)
        return statement == "gl_FragData[0]=texture2D(colortex0,texcoord);" ||
            statement == "gl_FragColor=texture2D(colortex0,texcoord);"
    }
}
