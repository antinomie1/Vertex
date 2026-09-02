package dev.vertex.frontend

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Resolves one immutable pack for the session; ZIP files stay mounted until shutdown. */
object PackRuntime {
    private var root: Path? = null
    private var archive: FileSystem? = null
    private var configFile: Path? = null
    @Volatile private var enabled = true
    @Volatile private var dimension = DimensionShaderRoots.OVERWORLD

    data class Settings(
        val enabled: Boolean,
        val pack: String?,
        val renderScale: Float,
        val shadowResolution: Int,
    )

    @Synchronized
    fun root(gameDir: Path): Path = root ?: select(gameDir).also { root = it }

    fun isEnabled() = enabled

    fun dimension() = System.getProperty("vertex.dimension")?.takeIf(String::isNotBlank) ?: dimension

    /** Returns true only when render programs must be rebuilt for a new dimension. */
    @Synchronized
    fun activateDimension(identifier: String?): Boolean {
        if (System.getProperty("vertex.dimension")?.isNotBlank() == true) return false
        val next = identifier?.takeIf(String::isNotBlank) ?: DimensionShaderRoots.OVERWORLD
        if (next == dimension) return false
        dimension = next
        return true
    }

    @Synchronized
    fun initialize(gameDir: Path) {
        configFile = gameDir.resolve("config/vertex-shaders.properties")
        val current = settings(gameDir)
        enabled = current.enabled
        if (System.getProperty("vertex.renderScale") == null)
            System.setProperty("vertex.renderScale", current.renderScale.toString())
        if (System.getProperty("vertex.shadowResolution") == null)
            System.setProperty("vertex.shadowResolution", current.shadowResolution.toString())
    }

    fun settings(gameDir: Path): Settings {
        val file = gameDir.resolve("config/vertex-shaders.properties")
        val props = Properties()
        if (Files.isRegularFile(file)) Files.newInputStream(file).use(props::load)
        val pack = System.getProperty("vertex.pack")?.takeIf(String::isNotBlank)
            ?: props.getProperty("pack")?.takeIf(String::isNotBlank)
        return Settings(
            enabled = System.getProperty("vertex.shadersEnabled")?.toBooleanStrictOrNull()
                ?: props.getProperty("enabled")?.toBooleanStrictOrNull() ?: true,
            pack = pack,
            renderScale = (System.getProperty("vertex.renderScale") ?: props.getProperty("renderScale"))
                ?.toFloatOrNull()?.takeIf { it in setOf(.5f, .75f, 1f) } ?: 1f,
            shadowResolution = (System.getProperty("vertex.shadowResolution") ?: props.getProperty("shadowResolution"))
                ?.toIntOrNull()?.takeIf { it in 256..8192 && it.countOneBits() == 1 } ?: 2048,
        )
    }

    @Synchronized
    fun apply(gameDir: Path, settings: Settings) {
        configFile = gameDir.resolve("config/vertex-shaders.properties")
        val previousPack = settings(gameDir).pack
        val samePack = samePack(gameDir, previousPack, settings.pack)
        val optionString = if (samePack) {
            System.getProperty("vertex.options")
                ?: options().asSequence().sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        } else ""
        if (!samePack) System.clearProperty("vertex.options")
        enabled = settings.enabled
        // OFF is a real vanilla mode: do not leave a stale pack path around for
        // the next launch or for any late resource lookup.
        val pack = settings.pack.takeIf { settings.enabled }
        if (pack == null) System.clearProperty("vertex.pack")
        else System.setProperty("vertex.pack", pack)
        System.setProperty("vertex.shadersEnabled", settings.enabled.toString())
        System.setProperty("vertex.renderScale", settings.renderScale.toString())
        System.setProperty("vertex.shadowResolution", settings.shadowResolution.toString())
        val file = gameDir.resolve("config/vertex-shaders.properties")
        file.parent?.let(Files::createDirectories)
        Properties().also {
            it["enabled"] = settings.enabled.toString()
            pack?.let { value -> it["pack"] = value }
            if (optionString.isNotBlank()) it["options"] = optionString
            it["renderScale"] = settings.renderScale.toString()
            it["shadowResolution"] = settings.shadowResolution.toString()
            Files.newOutputStream(file).use { output -> it.store(output, "Vertex shader settings") }
        }
        close()
    }

    private fun samePack(gameDir: Path, first: String?, second: String?): Boolean {
        if (first == null || second == null) return first == second
        fun resolve(value: String): Path {
            val path = Path.of(value)
            return (if (path.isAbsolute) path else gameDir.resolve("shaderpacks").resolve(path))
                .toAbsolutePath().normalize()
        }
        return resolve(first) == resolve(second)
    }

    fun options(): Map<String, String> = (System.getProperty("vertex.options")
        ?: runCatching {
            val file = configFile ?: return@runCatching null
            if (Files.isRegularFile(file)) Properties().also { Files.newInputStream(file).use(it::load) }
                .getProperty("options") else null
        }.getOrNull())
        ?.split(',')
        ?.mapNotNull { entry ->
            val (key, value) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            key.trim().takeIf(String::isNotEmpty)?.let { it to value.trim() }
        }?.toMap().orEmpty()

    @Synchronized
    fun applyOptions(gameDir: Path, values: Map<String, String>) {
        val encoded = values.asSequence()
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        if (encoded.isBlank()) System.clearProperty("vertex.options")
        else System.setProperty("vertex.options", encoded)
        val file = gameDir.resolve("config/vertex-shaders.properties")
        file.parent?.let(Files::createDirectories)
        val props = Properties()
        if (Files.isRegularFile(file)) Files.newInputStream(file).use(props::load)
        if (encoded.isBlank()) props.remove("options") else props["options"] = encoded
        Files.newOutputStream(file).use { props.store(it, "Vertex shader settings") }
    }

    @Synchronized
    fun close() {
        archive?.close()
        archive = null
        root = null
    }

    private fun select(gameDir: Path): Path {
        val requested = settings(gameDir).pack
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
