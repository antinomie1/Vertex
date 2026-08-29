package dev.vertex.translate

import java.nio.file.Files
import java.nio.file.Path

class ShaderPreprocessor(
    roots: List<Path>,
    defines: Map<String, String> = emptyMap(),
) {
    private val roots = roots.map { it.toAbsolutePath().normalize() }
    private val symbols = defines.toMutableMap()

    init { require(this.roots.isNotEmpty()) }

    fun process(entry: Path): String = expand(resolve(entry, null), ArrayDeque())

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
                if (active.all { it.enabled }) output.appendLine(expandSymbols(raw))
                return@forEachIndexed
            }
            val (name, tailValue) = directive
            val tail = tailValue.trim()
            when (name) {
                "if", "ifdef", "ifndef" -> {
                    val parent = active.all { it.enabled }
                    val condition = when (name) {
                        "ifdef" -> tail in symbols
                        "ifndef" -> tail !in symbols
                        else -> Expression(tail, symbols).evaluate()
                    }
                    active.addLast(Conditional(parent, parent && condition, condition))
                }
                "elif" -> active.replaceLast(file, line) { current ->
                    val condition = !current.branchTaken && Expression(tail, symbols).evaluate()
                    current.copy(enabled = current.parentEnabled && condition, branchTaken = current.branchTaken || condition)
                }
                "else" -> active.replaceLast(file, line) { current ->
                    current.copy(enabled = current.parentEnabled && !current.branchTaken, branchTaken = true)
                }
                "endif" -> if (active.isEmpty()) fail(file, line, "unexpected #endif") else active.removeLast()
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
                        val parts = tail.split(Regex("\\s+"), limit = 2)
                        if (parts.isEmpty() || !IDENT.matches(parts[0])) fail(file, line, "invalid #define")
                        symbols[parts[0]] = parts.getOrElse(1) { "1" }
                    }
                    "undef" -> symbols.remove(tail)
                    else -> output.appendLine(raw)
                }
            }
        }
        if (active.isNotEmpty()) fail(file, lines.size, "unterminated conditional")
        stack.removeLast()
        return output.toString()
    }

    private fun resolve(path: Path, relativeTo: Path?): Path {
        val candidates = buildList {
            if (path.isAbsolute) add(path)
            if (relativeTo != null) add(relativeTo.resolve(path))
            roots.forEach { add(it.resolve(path)) }
        }.map { it.toAbsolutePath().normalize() }
        return candidates.firstOrNull { candidate ->
            roots.any(candidate::startsWith) && Files.isRegularFile(candidate)
        } ?: throw IllegalArgumentException("shader include not found: $path")
    }

    private fun expandSymbols(line: String): String = IDENT.replace(line) { symbols[it.value] ?: it.value }

    private fun fail(file: Path, line: Int, message: String): Nothing =
        throw IllegalArgumentException("$file:$line: $message")

    private data class Conditional(val parentEnabled: Boolean, val enabled: Boolean, val branchTaken: Boolean)

    companion object {
        private val DIRECTIVE = Regex("""\s*#\s*(\w+)\b(.*)""")
        private val INCLUDE = Regex("""[<\"]([^>\"]+)[>\"]""")
        private val IDENT = Regex("""\b[A-Za-z_]\w*\b""")
    }
}

private inline fun <T> ArrayDeque<T>.replaceLast(file: Path, line: Int, transform: (T) -> T) {
    if (isEmpty()) throw IllegalArgumentException("$file:$line: conditional branch without #if")
    addLast(transform(removeLast()))
}

private class Expression(source: String, private val symbols: Map<String, String>) {
    private val tokens = Regex("defined|[A-Za-z_]\\w*|0[xX][0-9a-fA-F]+|\\d+|&&|\\|\\||==|!=|<=|>=|[()!<>]")
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
        take("defined") -> {
            val wrapped = take("("); val name = next(); if (wrapped) require(take(")")); bool(name in symbols)
        }
        take("(") -> or().also { require(take(")")) }
        else -> value(next())
    }
    private fun value(token: String): Long = token.toLongOrNull() ?: token.removePrefix("0x").removePrefix("0X")
        .takeIf { token.startsWith("0x", true) }?.toLong(16) ?: symbols[token]?.toLongOrNull() ?: 0L
    private fun next(): String = tokens.getOrNull(at++) ?: error("unexpected end of preprocessor expression")
    private fun take(token: String): Boolean = tokens.getOrNull(at) == token && (++at > 0)
    private fun bool(value: Boolean) = if (value) 1L else 0L
}
