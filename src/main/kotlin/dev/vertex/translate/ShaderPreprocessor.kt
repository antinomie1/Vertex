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
    private val symbols = defines.toMutableMap()
    private val objectMacros = defines.toMutableMap()

    init { require(this.roots.isNotEmpty()) }

    fun process(entry: Path): String = stripComments(expand(resolve(entry, null), ArrayDeque()))

    private fun expand(file: Path, stack: ArrayDeque<Path>): String {
        require(file !in stack) { "include cycle: ${(stack + file).joinToString(" -> ")}" }
        stack.addLast(file)
        val output = StringBuilder()
        val active = ArrayDeque<Conditional>()
        val lines = Files.readAllLines(file)
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
                    val condition = when (name) {
                        "ifdef" -> isDefined(tail)
                        "ifndef" -> !isDefined(tail)
                        else -> Expression(tail, symbols, ::isDefined).evaluate()
                    }
                    active.addLast(Conditional(parent, parent && condition, condition))
                    output.appendLine()
                }
                "elif" -> active.replaceLast(file, line) { current ->
                    val condition = !current.branchTaken && Expression(tail, symbols, ::isDefined).evaluate()
                    current.copy(enabled = current.parentEnabled && condition, branchTaken = current.branchTaken || condition)
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
    }
}

private inline fun <T> ArrayDeque<T>.replaceLast(file: Path, line: Int, transform: (T) -> T) {
    if (isEmpty()) throw IllegalArgumentException("$file:$line: conditional branch without #if")
    addLast(transform(removeLast()))
}

private class Expression(
    source: String,
    private val symbols: Map<String, String>,
    private val isDefined: (String) -> Boolean,
) {
    private val tokens = Regex("defined|[A-Za-z_]\\w*|0[xX][0-9a-fA-F]+|\\d+|&&|\\|\\||==|!=|<=|>=|[()!<>-]")
        .findAll(source).map { it.value }.toList()
    private var at = 0

    fun evaluate(): Boolean {
        val result = or()
        require(at == tokens.size) { "invalid preprocessor expression near '${tokens.drop(at).joinToString(" ")}'" }
        return result != 0L
    }

    private fun or(): Long { var v = and(); while (take("||")) { val r = and(); v = bool(v != 0L || r != 0L) }; return v }
    private fun and(): Long { var v = equality(); while (take("&&")) { val r = equality(); v = bool(v != 0L && r != 0L) }; return v }
    private fun equality(): Long {
        var v = relation()
        while (true) v = when { take("==") -> bool(v == relation()); take("!=") -> bool(v != relation()); else -> return v }
    }
    private fun relation(): Long {
        var v = unary()
        while (true) v = when {
            take("<") -> bool(v < unary()); take("<=") -> bool(v <= unary())
            take(">") -> bool(v > unary()); take(">=") -> bool(v >= unary()); else -> return v
        }
    }
    private fun unary(): Long = when {
        take("!") -> bool(unary() == 0L)
        take("-") -> -unary()
        take("defined") -> {
            val wrapped = take("("); val name = next(); if (wrapped) require(take(")")); bool(isDefined(name))
        }
        take("(") -> or().also { require(take(")")) }
        else -> value(next())
    }
    private fun value(token: String): Long = literal(token) ?: symbols[token]?.let(::literal) ?: 0L

    private fun literal(token: String): Long? = when {
        token.equals("true", ignoreCase = true) -> 1L
        token.equals("false", ignoreCase = true) -> 0L
        token.startsWith("0x", ignoreCase = true) -> token.substring(2).toLongOrNull(16)
        else -> token.toLongOrNull()
    }
    private fun next(): String = tokens.getOrNull(at++) ?: error("unexpected end of preprocessor expression")
    private fun take(token: String): Boolean = tokens.getOrNull(at) == token && (++at > 0)
    private fun bool(value: Boolean) = if (value) 1L else 0L
}
