package dev.vertex.frontend

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Resolves the Iris dimension directory for a Minecraft dimension identifier. */
object DimensionShaderRoots {
    const val OVERWORLD = "minecraft:overworld"

    fun ordered(packRoot: Path, dimension: String? = null): List<Path> {
        val shaders = packRoot.resolve("shaders")
        if (!Files.isDirectory(shaders)) return listOf(shaders)
        val worlds = Files.list(shaders).use { children ->
            children.filter { Files.isDirectory(it) && WORLD.matches(it.fileName.toString()) }
                .sorted().toList()
        }
        if (worlds.isEmpty()) return listOf(shaders)

        val requested = dimension ?: OVERWORLD
        val selectedName = mappedDirectory(shaders, requested)
            ?: DEFAULTS[requested]
            ?: wildcardDirectory(shaders)
        val selected = worlds.firstOrNull { it.fileName.toString() == selectedName }
        // A missing program may fall back to the shared shader directory, but
        // never to a different dimension: a Nether pass must not borrow an
        // Overworld or End program merely because that file happens to exist.
        return listOfNotNull(selected, shaders).distinct()
    }

    fun selected(packRoot: Path, dimension: String? = null): Path? =
        ordered(packRoot, dimension).firstOrNull { it != packRoot.resolve("shaders") }

    private fun mappedDirectory(shaders: Path, dimension: String): String? {
        val properties = properties(shaders)
        return properties.stringPropertyNames().asSequence()
            .filter { it.startsWith("dimension.") }
            .sorted()
            .firstOrNull { key ->
                properties.getProperty(key).tokens().any { it == dimension }
            }?.removePrefix("dimension.")
    }

    private fun wildcardDirectory(shaders: Path): String? {
        val properties = properties(shaders)
        return properties.stringPropertyNames().asSequence()
            .filter { it.startsWith("dimension.") }
            .sorted()
            .firstOrNull { key -> "*" in properties.getProperty(key).tokens() }
            ?.removePrefix("dimension.")
    }

    private fun properties(shaders: Path) = Properties().apply {
        val file = shaders.resolve("dimension.properties")
        if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(::load)
    }

    private fun String.tokens() = trim().split(Regex("\\s+")).filter(String::isNotBlank)

    private val WORLD = Regex("world-?\\d+")
    private val DEFAULTS = mapOf(
        OVERWORLD to "world0",
        "minecraft:the_nether" to "world-1",
        "minecraft:the_end" to "world1",
    )
}
