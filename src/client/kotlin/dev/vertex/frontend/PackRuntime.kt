package dev.vertex.frontend

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/** Resolves one immutable pack for the session; ZIP files stay mounted until shutdown. */
object PackRuntime {
    private var root: Path? = null
    private var archive: FileSystem? = null

    @Synchronized
    fun root(gameDir: Path): Path = root ?: select(gameDir).also { root = it }

    fun options(): Map<String, String> = System.getProperty("vertex.options")
        ?.split(',')
        ?.mapNotNull { entry ->
            val (key, value) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            key.trim().takeIf(String::isNotEmpty)?.let { it to value.trim() }
        }?.toMap().orEmpty()

    @Synchronized
    fun close() {
        archive?.close()
        archive = null
        root = null
    }

    private fun select(gameDir: Path): Path {
        val requested = System.getProperty("vertex.pack")?.takeIf(String::isNotBlank)
            ?: return SamplePack.ensure(gameDir.resolve("shaderpacks"))
        val raw = Path.of(requested)
        val path = (if (raw.isAbsolute) raw else gameDir.resolve("shaderpacks").resolve(raw))
            .toAbsolutePath().normalize()
        require(Files.exists(path)) { "shader pack does not exist: $path" }
        val candidate = if (Files.isDirectory(path)) path else {
            archive = FileSystems.newFileSystem(path)
            archive!!.getPath("/")
        }
        return findPackRoot(candidate)
    }

    private fun findPackRoot(candidate: Path): Path {
        if (Files.isDirectory(candidate.resolve("shaders"))) return candidate
        Files.list(candidate).use { children ->
            return children.filter { Files.isDirectory(it.resolve("shaders")) }.findFirst()
                .orElseThrow { IllegalArgumentException("shader pack has no shaders directory: $candidate") }
        }
    }
}
