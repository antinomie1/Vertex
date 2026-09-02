package dev.vertex.translate

import java.nio.file.Files
import java.nio.file.Path

class ShaderPreprocessor(
    roots: List<Path>,
    defines: Map<String, String> = emptyMap(),
) {
    private val roots = roots.map { it.toAbsolutePath().normalize() }
    private val options = defines.toMap()
    private val optionNames = defines.keys
    private val symbols = (STANDARD_DEFINES + defines).toMutableMap()
    private val objectMacros = (STANDARD_DEFINES + defines).toMutableMap()

    init { require(this.roots.isNotEmpty()) }

    fun process(entry: Path): String = stripComments(expand(resolve(entry, null), ArrayDeque()))

    private fun expand(file: Path, stack: ArrayDeque<Path>): String {
        require(file !in stack) { "include cycle: ${(stack + file).joinToString(" -> ")}" }
        stack.addLast(file)
        val output = StringBuilder()
        val active = ArrayDeque<Conditional>()
        val lines = continuedLines(Files.readAllLines(file))
        lines.forEachIndexed { index, raw ->
            val line = index + 1
            val directive = DIRECTIVE.matchEntire(raw)?.destructured
            if (directive == null) {
                val commentedDefine = COMMENTED_DEFINE.matchEntire(raw)
                if (commentedDefine != null && active.all { it.enabled }) {
                    val name = commentedDefine.groupValues[1]
                    val override = options[name]
                    if (override != null && !override.equals("false", ignoreCase = true)) {
                        symbols[name] = override
                        objectMacros[name] = override
                        output.appendLine("#define $name $override")
                    } else output.appendLine()
                } else output.appendLine(if (active.all { it.enabled }) expandOptions(raw) else "")
                return@forEachIndexed
            }
            val (name, tailValue) = directive
            val tail = tailValue.trim()
            when (name) {
                "if", "ifdef", "ifndef" -> {
                    val parent = active.all { it.enabled }
                    val conditionSource = expressionTail(tail)
                    // Do not evaluate nested expressions inside an inactive
                    // branch. C preprocessors still balance the directives,
                    // but syntax in a branch excluded by its parent is inert.
                    val condition = parent && when (name) {
                        "ifdef" -> isDefined(conditionSource)
                        "ifndef" -> !isDefined(conditionSource)
                        else -> evaluate(file, line, conditionSource)
                    }
                    active.addLast(Conditional(parent, parent && condition, condition))
                    output.appendLine()
                }
                "elif" -> active.replaceLast(file, line) { current ->
                    val condition = current.parentEnabled && !current.branchTaken &&
                        evaluate(file, line, expressionTail(tail))
                    current.copy(enabled = condition, branchTaken = current.branchTaken || condition)
                }.also { output.appendLine() }
                "else" -> active.replaceLast(file, line) { current ->
                    current.copy(enabled = current.parentEnabled && !current.branchTaken, branchTaken = true)
                }.also { output.appendLine() }
                "endif" -> if (active.isEmpty()) fail(file, line, "unexpected #endif") else {
                    active.removeLast(); output.appendLine()
                }
                else -> if (active.all { it.enabled }) when (name) {
                    "include" -> {
                        val target = INCLUDE.matchEntire(tail)?.groupValues?.get(1)
                            ?: fail(file, line, "invalid #include")
                        val included = resolve(Path.of(target), file.parent)
                        output.appendLine("#line 1 \"${included.toString().replace("\\", "/")}\"")
                        output.append(expand(included, stack))
                        output.appendLine("#line ${line + 1} \"${file.toString().replace("\\", "/")}\"")
                    }
                    "define" -> {
                        val definition = DEFINE.matchEntire(tail) ?: fail(file, line, "invalid #define")
                        val name = definition.groupValues[1]
                        val functionLike = definition.groupValues[2].isNotEmpty()
                        // Do not substitute inline documentation into statements: once the
                        // comment is stripped it would also swallow the statement terminator.
                        val defaultValue = if (functionLike) "1" else definition.groupValues[3]
                            .substringBefore("//")
                            .trim()
                            .ifBlank { "1" }
                        val value = if (!functionLike) options[name] ?: defaultValue else defaultValue
                        symbols[name] = value
                        if (functionLike) objectMacros.remove(name) else objectMacros[name] = value
                        output.appendLine(if (!functionLike && name in options) "#define $name $value" else raw)
                    }
                    "undef" -> { symbols.remove(tail); objectMacros.remove(tail); output.appendLine(raw) }
                    else -> output.appendLine(raw)
                } else output.appendLine()
            }
        }
        if (active.isNotEmpty()) fail(file, lines.size, "unterminated conditional")
        stack.removeLast()
        return output.toString()
    }

    private fun resolve(path: Path, relativeTo: Path?): Path {
        val candidates = buildList {
            if (path.isAbsolute) add(path)
            if (relativeTo != null && !path.isAbsolute) add(relativeTo.resolve(path.toString()))
            roots.forEach { root ->
                add(root.resolve(if (path.isAbsolute) path.toString().removePrefix("/") else path.toString()))
            }
        }.map { it.toAbsolutePath().normalize() }
        return candidates.firstOrNull { candidate ->
            roots.any(candidate::startsWith) && Files.isRegularFile(candidate)
        } ?: throw IllegalArgumentException("shader include not found: $path")
    }

    private fun expandOptions(line: String): String {
        // Shader packs commonly expose settings as typed constants rather than
        // object-like macros.  Never substitute the declared identifier itself
        // (`const int shadowMapResolution = ...`); doing so turns valid GLSL into
        // `const int 2048 = ...` and makes the whole pack fall back to vanilla.
        CONSTANT_DECLARATION.matchEntire(line)?.let { declaration ->
            val name = declaration.groupValues[2]
            options[name]?.takeIf(String::isNotBlank)?.let { value ->
                return buildString(line.length + value.length) {
                    append(declaration.groupValues[1])
                    append(name)
                    append(declaration.groupValues[3])
                    append(value)
                    append(declaration.groupValues[5])
                }
            }
        }
        return IDENT.replace(line) { token ->
            options[token.value] ?: objectMacros[token.value] ?: token.value
        }
    }

    private fun isDefined(name: String): Boolean = name in symbols &&
        (name !in optionNames || symbols[name]?.equals("false", ignoreCase = true) != true)

    private fun expressionTail(value: String): String = value
        .substringBefore("//")
        .replace(Regex("/\\*.*?\\*/"), " ")
        .trim()

    /** Joins C-preprocessor continuations while retaining one output line per source line. */
    private fun continuedLines(source: List<String>): List<String> {
        val lines = source.toMutableList()
        var index = 0
        while (index < lines.size) {
            var combined = lines[index]
            var next = index + 1
            while (combined.trimEnd().endsWith('\\') && next < lines.size) {
                combined = combined.trimEnd().dropLast(1) + " " + lines[next].trimStart()
                lines[next] = ""
                next++
            }
            lines[index] = combined
            index = next
        }
        return lines
    }

    private fun evaluate(file: Path, line: Int, source: String): Boolean = try {
        Expression(source, symbols, ::isDefined).evaluate()
    } catch (failure: RuntimeException) {
        fail(file, line, "invalid #if expression '$source': ${failure.message}")
    }

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var block = false
        while (index < source.length) {
            val c = source[index]
            val next = source.getOrNull(index + 1)
            when {
                block && c == '*' && next == '/' -> { out.append("  "); block = false; index += 2 }
                block -> { out.append(if (c == '\n') '\n' else ' '); index++ }
                c == '/' && next == '*' -> {
                    val end = source.indexOf("*/", index + 2)
                    val comment = if (end >= 0) source.substring(index + 2, end) else ""
                    if (end >= 0 && DIRECTIVE_COMMENT.containsMatchIn(comment)) {
                        out.append(source, index, end + 2)
                        index = end + 2
                    } else {
                        out.append("  "); block = true; index += 2
                    }
                }
                c == '/' && next == '/' -> {
                    while (index < source.length && source[index] != '\n') { out.append(' '); index++ }
                }
                else -> { out.append(c); index++ }
            }
        }
        return out.toString()
    }

    private fun fail(file: Path, line: Int, message: String): Nothing =
        throw IllegalArgumentException("$file:$line: $message")

    private data class Conditional(val parentEnabled: Boolean, val enabled: Boolean, val branchTaken: Boolean)

    companion object {
        private val DIRECTIVE = Regex("""\s*#\s*(\w+)\b(.*)""")
        private val INCLUDE = Regex("""[<\"]([^>\"]+)[>\"]""")
        private val DEFINE = Regex("""([A-Za-z_]\w*)(\([^)]*\))?(?:\s+(.*))?""")
        private val COMMENTED_DEFINE = Regex("""\s*//\s*#\s*define\s+([A-Za-z_]\w*)\b.*""")
        private val IDENT = Regex("""\b[A-Za-z_]\w*\b""")
        private val CONSTANT_DECLARATION = Regex(
            """^(\s*(?:const\s+)?[A-Za-z_]\w*\s+)([A-Za-z_]\w*)(\s*=\s*)([^;]+)(;.*)$"""
        )
        private val DIRECTIVE_COMMENT = Regex("""\s*(?:DRAWBUFFERS|RENDERTARGETS)\s*:""")
        private val STANDARD_DEFINES = mapOf(
            "MC_VERSION" to "12603",
            "MC_GL_VERSION" to "460",
            "MC_GLSL_VERSION" to "460",
            "MC_HAND_DEPTH" to "0.125",
            "MC_RENDER_QUALITY" to "1.0",
            "MC_SHADOW_QUALITY" to "1.0",
            "MC_RENDER_STAGE_NONE" to "0",
            "MC_RENDER_STAGE_SKY" to "1",
            "MC_RENDER_STAGE_SUNSET" to "2",
            "MC_RENDER_STAGE_CUSTOM_SKY" to "3",
            "MC_RENDER_STAGE_SUN" to "4",
            "MC_RENDER_STAGE_MOON" to "5",
            "MC_RENDER_STAGE_STARS" to "6",
            "MC_RENDER_STAGE_VOID" to "7",
            "MC_RENDER_STAGE_TERRAIN_SOLID" to "8",
            "MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED" to "9",
            "MC_RENDER_STAGE_TERRAIN_CUTOUT" to "10",
            "MC_RENDER_STAGE_TERRAIN_TRANSLUCENT" to "17",
            "IS_IRIS" to "1",
            "IRIS_VERSION" to "10800",
        )
    }
}

/** Shared evaluator for shaders.properties program.enabled expressions. */
object ShaderExpression {
    fun evaluate(source: String, symbols: Map<String, String>): Boolean = Expression(
        source.substringBefore("//").trim(),
        symbols,
        { name -> name in symbols && symbols[name]?.equals("false", ignoreCase = true) != true },
    ).evaluate()
}

private inline fun <T> ArrayDeque<T>.replaceLast(file: Path, line: Int, transform: (T) -> T) {
    if (isEmpty()) throw IllegalArgumentException("$file:$line: conditional branch without #if")
    addLast(transform(removeLast()))
}

private class Expression(
    source: String,
    private val symbols: Map<String, String>,
    private val isDefined: (String) -> Boolean,
    private val resolving: MutableSet<String> = mutableSetOf(),
) {
    private val tokens = tokenize(source)
    private var at = 0

    fun evaluate(): Boolean {
        val result = or(true)
        require(at == tokens.size) { "invalid preprocessor expression near '${tokens.drop(at).joinToString(" ")}'" }
        return result != 0L
    }

    private fun numeric(): Long {
        val result = or(true)
        require(at == tokens.size) { "invalid macro expression near '${tokens.drop(at).joinToString(" ")}'" }
        return result
    }

    private fun or(evaluate: Boolean): Long {
        var value = and(evaluate)
        while (take("||")) {
            val right = and(evaluate && value == 0L)
            if (evaluate) value = bool(value != 0L || right != 0L)
        }
        return value
    }

    private fun and(evaluate: Boolean): Long {
        var value = bitwiseOr(evaluate)
        while (take("&&")) {
            val right = bitwiseOr(evaluate && value != 0L)
            if (evaluate) value = bool(value != 0L && right != 0L)
        }
        return value
    }

    private fun bitwiseOr(evaluate: Boolean): Long {
        var value = bitwiseXor(evaluate)
        while (take("|")) {
            val right = bitwiseXor(evaluate)
            if (evaluate) value = value or right
        }
        return value
    }

    private fun bitwiseXor(evaluate: Boolean): Long {
        var value = bitwiseAnd(evaluate)
        while (take("^")) {
            val right = bitwiseAnd(evaluate)
            if (evaluate) value = value xor right
        }
        return value
    }

    private fun bitwiseAnd(evaluate: Boolean): Long {
        var value = equality(evaluate)
        while (take("&")) {
            val right = equality(evaluate)
            if (evaluate) value = value and right
        }
        return value
    }

    private fun equality(evaluate: Boolean): Long {
        var value = relation(evaluate)
        while (true) value = when {
            take("==") -> relation(evaluate).let { if (evaluate) bool(value == it) else 0L }
            take("!=") -> relation(evaluate).let { if (evaluate) bool(value != it) else 0L }
            else -> return value
        }
    }

    private fun relation(evaluate: Boolean): Long {
        var value = shift(evaluate)
        while (true) value = when {
            take("<") -> shift(evaluate).let { if (evaluate) bool(value < it) else 0L }
            take("<=") -> shift(evaluate).let { if (evaluate) bool(value <= it) else 0L }
            take(">") -> shift(evaluate).let { if (evaluate) bool(value > it) else 0L }
            take(">=") -> shift(evaluate).let { if (evaluate) bool(value >= it) else 0L }
            else -> return value
        }
    }

    private fun shift(evaluate: Boolean): Long {
        var value = addition(evaluate)
        while (true) value = when {
            take("<<") -> addition(evaluate).let { if (evaluate) value shl (it.toInt() and 63) else 0L }
            take(">>") -> addition(evaluate).let { if (evaluate) value shr (it.toInt() and 63) else 0L }
            else -> return value
        }
    }

    private fun addition(evaluate: Boolean): Long {
        var value = multiplication(evaluate)
        while (true) value = when {
            take("+") -> multiplication(evaluate).let { if (evaluate) value + it else 0L }
            take("-") -> multiplication(evaluate).let { if (evaluate) value - it else 0L }
            else -> return value
        }
    }

    private fun multiplication(evaluate: Boolean): Long {
        var value = unary(evaluate)
        while (true) value = when {
            take("*") -> unary(evaluate).let { if (evaluate) value * it else 0L }
            take("/") -> unary(evaluate).let {
                if (!evaluate) 0L else {
                    require(it != 0L) { "division by zero" }
                    value / it
                }
            }
            take("%") -> unary(evaluate).let {
                if (!evaluate) 0L else {
                    require(it != 0L) { "division by zero" }
                    value % it
                }
            }
            else -> return value
        }
    }

    private fun unary(evaluate: Boolean): Long = when {
        take("!") -> unary(evaluate).let { if (evaluate) bool(it == 0L) else 0L }
        take("~") -> unary(evaluate).let { if (evaluate) it.inv() else 0L }
        take("+") -> unary(evaluate)
        take("-") -> unary(evaluate).let { if (evaluate) -it else 0L }
        take("defined") -> {
            val wrapped = take("(")
            val name = next()
            require(IDENTIFIER.matches(name)) { "defined() expects an identifier" }
            if (wrapped) require(take(")"))
            if (evaluate) bool(isDefined(name)) else 0L
        }
        take("(") -> or(evaluate).also { require(take(")")) }
        else -> if (evaluate) value(next()) else next().let { 0L }
    }

    private fun value(token: String): Long {
        literal(token)?.let { return it }
        val replacement = symbols[token]?.trim() ?: return 0L
        if (!IDENTIFIER.matches(token) || !resolving.add(token)) return 0L
        return try {
            literal(replacement) ?: Expression(replacement, symbols, isDefined, resolving).numeric()
        } finally {
            resolving.remove(token)
        }
    }

    private fun literal(token: String): Long? {
        if (token.equals("true", ignoreCase = true)) return 1L
        if (token.equals("false", ignoreCase = true)) return 0L
        val value = token.trimEnd { it in "uUlLfF" }
        return when {
            value.startsWith("0x", ignoreCase = true) -> value.substring(2).toLongOrNull(16)
            value.startsWith("0b", ignoreCase = true) -> value.substring(2).toLongOrNull(2)
            value.contains('.') || value.contains('e', true) -> value.toDoubleOrNull()?.toLong()
            value.length > 1 && value.startsWith('0') -> value.substring(1).toLongOrNull(8)
            else -> value.toLongOrNull()
        }
    }

    private fun next(): String = tokens.getOrNull(at++) ?: error("unexpected end of preprocessor expression")
    private fun take(token: String): Boolean = tokens.getOrNull(at) == token && (++at > 0)
    private fun bool(value: Boolean) = if (value) 1L else 0L

    private companion object {
        val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
        val TOKEN = Regex(
            """0[xX][0-9a-fA-F]+[uUlL]*|0[bB][01]+[uUlL]*|(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?[uUlLfF]*|[A-Za-z_]\w*|&&|\|\||==|!=|<=|>=|<<|>>|[()!~<>+*/%&^|?:-]""",
        )

        fun tokenize(source: String): List<String> {
            val result = mutableListOf<String>()
            var index = 0
            while (index < source.length) {
                if (source[index].isWhitespace()) {
                    index++
                    continue
                }
                val match = TOKEN.matchAt(source, index)
                    ?: throw IllegalArgumentException("unexpected token '${source.substring(index).take(16)}'")
                result += match.value
                index = match.range.last + 1
            }
            return result
        }
    }
}
